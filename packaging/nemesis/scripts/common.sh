#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
MOD=egmp-carplay-ev-routing
HMG=/system/bin/hmgapid
DATA=/data/hmgapi
BACKUP=/data/nemesis/backup/carplay.ev-routing
LOGS=/data/nemesis/logs/carplay.ev-routing
STAGE=/data/local/tmp/nemesis/stage/carplay.ev-routing
UUID=b45fb2d9-e6e8-83a2-bce9-cd5c40d2232d
USB=
USB_SOURCE=

fail() { echo "FAIL: $*" >&2; return 1; }
init_logs() { mkdir -p "$LOGS" && echo "=== $1 $(date) ===" >> "$LOGS/install.log"; }
run() {
    local _rc
    if "$@" > "$LOGS/command.tmp" 2>&1; then _rc=0; else _rc=$?; fi
    cat "$LOGS/command.tmp"
    cat "$LOGS/command.tmp" >> "$LOGS/install.log"
    return "$_rc"
}
hash() { local _line; _line=$(md5 "$1") || return 1; set -- $_line; echo "$1"; }
mod_state() {
    local _id _state _rest
    "$HMG" mods > "$LOGS/mods.tmp" || return 1
    while read -r _id _state _rest; do
        if [ "$_id" = "$1" ]; then echo "$_state"; return 0; fi
    done < "$LOGS/mods.tmp"
    echo absent
}
version_ok() {
    local _version="$1" _beta
    case "$_version" in
        1.0.0-beta.*)
            _beta=${_version#1.0.0-beta.}
            case "$_beta" in ''|*[!0-9]*) return 1 ;; esac
            [ "$_beta" -ge 6 ] ;;
        1.*) case "$_version" in *[!0-9.]*) return 1 ;; *) return 0 ;; esac ;;
        *) return 1 ;;
    esac
}
select_profile() {
    local _prefix _firmware _zip _md5 _extra
    PRODUCT=$(getprop ro.build.product)
    while read -r _prefix _firmware _zip _md5 _extra; do
        # v0.5.1 selects only by the allowlisted model prefix. HMG-API map
        # resolution and the exact native hashes remain the safety gates.
        case "$PRODUCT" in "$_prefix"*)
            PAYLOAD=payload/$_zip; EXPECTED=$_md5
            [ "$(hash "$PAYLOAD")" = "$EXPECTED" ] || return 1
            echo "product=$PRODUCT payload=$_zip" >> "$LOGS/install.log"
            return 0 ;;
        esac
    done < payload/profiles.tsv
    fail "Unsupported product: $PRODUCT"
}
check_runtime() {
    local _line _version _state
    [ -x "$HMG" ] || { fail 'Install HMG-API through NEMESIS first'; return 1; }
    _line=$("$HMG" version) || return 1
    _version=${_line#hmgapid }
    version_ok "$_version" || { fail 'HMG-API beta6 or newer must be running; update and reboot first'; return 1; }
    [ ! -f "$DATA/state/journal.json" ] || { fail 'Resolve the existing HMG-API trial/unfinished apply first'; return 1; }
    _state=$(mod_state wireless-carplay-aa) || return 1
    [ "$_state" = enabled ] || { fail 'Install and enable the HMG-API Wireless CarPlay + Android Auto mod first'; return 1; }
    run "$HMG" engine verify-map || return 1
}
check_native() {
    local _name _want _actual _message _bad=0
    while read -r _name _want; do
        _actual=$(hash "/system/lib/$_name") || _actual=unreadable
        echo "file=$_name expected=$_want actual=$_actual" >> "$LOGS/install.log"
        if [ "$_actual" != "$_want" ]; then
            _message="HASH_MISMATCH file=$_name expected=$_want actual=$_actual"
            echo "$_message"
            echo "$_message" >> "$LOGS/install.log"
            _bad=1
        fi
    done < payload/native-md5.txt
    [ "$_bad" = 0 ] || { fail 'Native library hash mismatch; EV Routing was not installed and no EV files were changed'; return 1; }
}
check_plan() {
    local _kind _path _rest
    run "$HMG" engine plan || return 1
    if grep -F 'hooks are off on this car' "$LOGS/command.tmp" >/dev/null ||
       grep -F "$MOD won't load:" "$LOGS/command.tmp" >/dev/null; then
        fail 'EV hooks were refused by HMG-API'; return 1
    fi
    while read -r _kind _path _rest; do
        case "$_kind" in
            'change'|'update'|'restore')
                [ "$_path" = /system/lib/libcarplayev.so ] || [ "$_path" = /system/etc/carplay.ev-routing/profile.json ] || { fail 'Apply other pending HMG-API changes first'; return 1; } ;;
            'setting'|'bind'|'unbind') fail 'Apply other pending HMG-API changes first'; return 1 ;;
        esac
    done < "$LOGS/command.tmp"
}
# Source-USB discovery follows the caller's argv, never an arbitrary mounted drive.
find_usb() {
    local _arg _exe _op _rest
    [ -r "/proc/$PPID/cmdline" ] || return 1
    {
        IFS= read -r -d '' _exe || return 1
        IFS= read -r -d '' _op || return 1
        case "$_exe/$_op" in */nemesis/install|nemesis/install) ;; *) return 1 ;; esac
        while IFS= read -r -d '' _arg; do
            case "$_arg" in
                /storage/usb[012]/*.zip) _rest=${_arg#/storage/}; USB=/storage/${_rest%%/*}; USB_SOURCE=$_arg ;;
                /mnt/media_rw/usb[012]/*.zip) _rest=${_arg#/mnt/media_rw/}; USB=/mnt/media_rw/${_rest%%/*}; USB_SOURCE=$_arg ;;
            esac
        done
    } < "/proc/$PPID/cmdline"
    [ -n "$USB" ] && [ -f "$USB_SOURCE" ]
}
export_report() {
    local _destination _file
    [ -n "$USB" ] && [ -f "$USB_SOURCE" ] || return 0
    _destination="$USB/NEMESIS-report-carplay.ev-routing"
    mkdir -p "$_destination" || return 0
    cp "$LOGS/install.log" "$_destination/install.log" || true
    # Input copies stay on the installing USB, for private compatibility analysis.
    for _file in /system/app/Iap2ServerApp.apk /system/app/Iap2ServerApp.odex /system/lib/libiap2session.so; do
        [ -f "$_file" ] || continue
        cp "$_file" "$_destination/${_file##*/}" || true
    done
    echo "USB report: $_destination"
}
restore_previous() {
    local _rc=0
    run "$HMG" engine recover || return 1
    if [ -f "$BACKUP/previous-mod/mod.json" ]; then
        run "$HMG" engine install "$BACKUP/previous-mod" --uuid "$UUID" --force || return 1
        if [ "$(cat "$BACKUP/previous-state")" = enabled ]; then
            run "$HMG" enable "$MOD" || return 1
        else run "$HMG" disable "$MOD" || return 1; fi
    else
        if [ -d "$DATA/mods/$MOD" ]; then run "$HMG" engine remove "$MOD" || return 1; fi
    fi
    check_plan && run "$HMG" engine apply || _rc=1
    return "$_rc"
}
