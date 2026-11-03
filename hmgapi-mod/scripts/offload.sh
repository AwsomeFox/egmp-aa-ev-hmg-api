#!/system/bin/sh
# egmp-aa-ev (HMG-API mod): the "offload" service. Copies EV logs to a USB
# drive (docs/LOGGING.md).
#
# GPM writes one CSV per head-unit "on" period into its own files dir and
# appends to it once a minute (EvDriveLog/EvLogger, running in GPM through
# the mod's hooks). Every POLL_S this copies any log that is new or has grown
# to <drive>/egmp-aa-ev-logs/ on every mounted USB drive that HAS that folder
# (the folder is the opt-in: drives without it are never written to). Which
# drives have it, HMG-API says (mod.json "usb": ["egmp-aa-ev-logs"]). The copy
# goes to a .tmp name, is verified with cmp, then renamed, so a pulled drive
# never ends up with a half-written log. Logs stay on the head unit (capped by
# EvLogger) and are never deleted from here.
#
# Runs as root (reading GPM's files, writing the drive). Its output is the
# service's log in HMG-API (hmgapid service-log offload). Only the head unit's
# own toolbox commands (no sed/awk/stat/wc).
# EVLOG_PASSES=N stops after N passes (host sandbox test; unset = forever).

MOD=egmp-aa-ev
FOLDER=egmp-aa-ev-logs
SRC=${EVLOG_SRC:-/data/data/com.google.android.projection.sink/files/evlog}
POLL_S=${EVLOG_POLL_S:-20}
DRIVES="/storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2"

log() {
    echo "$(date) $1"
}

# the head unit's own pop-up
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}

# file size via ls -l (no stat/wc on the unit); empty if missing or if the
# listing isn't the expected toolbox layout (perms owner group size ...)
size_of() {
    local _l
    _l=$(ls -l "$1" 2>/dev/null) || return 0
    set -- $_l
    case "$4" in
        ''|*[!0-9]*) ;;
        *) echo "$4" ;;
    esac
}

# 0 when the drive's copy is already current
up_to_date() {
    local _ss _ds
    _ss=$(size_of "$1")
    _ds=$(size_of "$2")
    if [ -n "$_ss" ] && [ -n "$_ds" ]; then
        [ "$_ss" = "$_ds" ]
        return
    fi
    [ -f "$2" ] && cmp "$1" "$2" >/dev/null 2>&1
}

# copies new/grown logs; sets COPIED and FAILED
offload_to() {
    local _dst="$1/$FOLDER" _f _n _tmp _done=0 _fail=0
    for _f in "$SRC"/*.csv; do
        # readable only if the logger made it so (files from before that
        # change stay private to GPM; skip them quietly)
        [ -f "$_f" ] && [ -r "$_f" ] || continue
        _n=${_f##*/}
        up_to_date "$_f" "$_dst/$_n" && continue
        _tmp="$_dst/.$_n.tmp"
        rm -f "$_tmp" 2>/dev/null
        if cp "$_f" "$_tmp" 2>/dev/null && cmp "$_f" "$_tmp" >/dev/null 2>&1 \
                && mv "$_tmp" "$_dst/$_n" 2>/dev/null; then
            _done=$((_done + 1))
        else
            rm -f "$_tmp" 2>/dev/null
            log "copy failed: $_n -> $_dst"
            _fail=$((_fail + 1))
        fi
    done
    if [ "$_done" -gt 0 ]; then
        sync
        log "copied $_done log(s) to $_dst"
    fi
    COPIED=$_done
    FAILED=$_fail
}

# drive removed: announce again on the next insertion (no sed on the unit)
forget() {
    local _w _new=" "
    for _w in $ANNOUNCED; do
        [ "$_w" = "$1" ] || _new="$_new$_w "
    done
    ANNOUNCED=$_new
}

# One pop-up per drive insertion (the first pass that sees the folder), so a
# session still logging with a drive in (cars with two data ports) can't
# keep popping up. ANNOUNCED holds the drives seen since they were inserted.
ANNOUNCED=" "
announce() {
    case "$ANNOUNCED" in *" $1 "*) return 0 ;; esac
    ANNOUNCED="$ANNOUNCED$1 "
    if [ "$FAILED" -gt 0 ]; then
        toast "AA EV logs: $COPIED copied, $FAILED failed. See the mod log in HMG-API."
    elif [ "$COPIED" -gt 0 ]; then
        toast "AA EV logs: $COPIED copied to the USB drive (egmp-aa-ev-logs). Safe to remove."
    else
        toast "AA EV logs: the USB drive is already up to date."
    fi
}

# The drives that have the folder: HMG-API's answer (mod.json "usb"), or a
# look at the usual mount points when hmgapid doesn't answer.
folders() {
    local _l _d
    _l=$(hmgapid usb "$MOD" 2>/dev/null)
    if [ $? -eq 0 ]; then
        echo "$_l"
        return
    fi
    for _d in $DRIVES; do
        [ -d "$_d/$FOLDER" ] && echo "$_d/$FOLDER"
    done
}

log "offload started"
while true; do
    _have=" "
    for _f in $(folders); do
        _d=${_f%/*}
        _have="$_have$_d "
        offload_to "$_d"
        announce "$_d"
    done
    for _d in $ANNOUNCED; do
        case "$_have" in *" $_d "*) ;; *) forget "$_d" ;; esac
    done
    if [ -n "$EVLOG_PASSES" ]; then
        EVLOG_PASSES=$((EVLOG_PASSES - 1))
        [ "$EVLOG_PASSES" -le 0 ] && exit 0
    fi
    sleep "$POLL_S"
done
