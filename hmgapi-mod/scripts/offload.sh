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
# With the mod's "EV logs: to a USB drive only" setting (ev_log_usb_only), GPM
# writes no files at all: its logger sends each row to the system log (tag
# EvLogUsb, see EvUsbFeed.java for the lines) and this script appends them to
# a file in the drive's folder, so nothing is written to the head unit. With
# no drive in, the rows are dropped. Logs from before the setting are still
# copied, each time a drive is found.
#
# Runs as root (reading GPM's files, writing the drive). Its output is the
# service's log in HMG-API (hmgapid service-log offload). Only the head unit's
# own toolbox commands (no sed/awk/stat/wc).
# EVLOG_PASSES=N stops after N passes (host sandbox test; unset = forever).

MOD=egmp-aa-ev
FOLDER=egmp-aa-ev-logs
SRC=${EVLOG_SRC:-/data/data/com.google.android.projection.sink/files/evlog}
POLL_S=${EVLOG_POLL_S:-20}
FEED_TAG=EvLogUsb
MAX_ROWS=20000      # a file on the drive: about 4 MB, as on the head unit
DRIVES="/storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2"

log() {
    echo "$(date) $1"
}

# the head unit's own pop-up
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}

# Look up known CSV names together. Android toolbox prints regular files as
# mode owner group size YYYY-MM-DD HH:MM basename.
file_sizes() {
    local _dir=$1 _mode _owner _group _size _date _time _name
    shift
    (cd "$_dir" && ls -ld "$@") 2>/dev/null |
        while read -r _mode _owner _group _size _date _time _name; do
            case "$_mode:$_date:$_time" in
                -?????????:????-??-??:??:??) ;;
                *) continue ;;
            esac
            case "$_size" in ''|*[!0-9]*) continue ;; esac
            echo -n "${_name##*/}=$_size "
        done
}

# 0 when the drive's copy is already current; compare bytes if a size is missing.
up_to_date() {
    local _name=${1##*/} _src_sizes=$3 _dst_sizes=$4 _size
    [ -f "$2" ] || return 1
    case "$_src_sizes" in
        *" $_name="*)
            _size=${_src_sizes#*" $_name="}
            _size=${_size%% *}
            case "$_dst_sizes" in
                *" $_name=$_size "*) return 0 ;;
                *" $_name="*) return 1 ;;
            esac
            ;;
    esac
    cmp "$1" "$2" >/dev/null 2>&1
}

copy_batch() {
    local _dst=$1 _n _f _tmp _src_sizes= _dst_sizes=
    shift
    # Unusual names are passed alone and use the direct comparison.
    case "$1" in
        *[!A-Za-z0-9_./-]*) ;;
        *)
            _dst_sizes=" $(file_sizes "$_dst" "$@")"
            _src_sizes=" $(file_sizes "$SRC" "$@")"
            ;;
    esac
    for _n do
        _n=${_n#./}
        _f="$SRC/$_n"
        up_to_date "$_f" "$_dst/$_n" "$_src_sizes" "$_dst_sizes" && continue
        _tmp="$_dst/.$_n.tmp"
        rm -f "$_tmp" 2>/dev/null
        if cp "$_f" "$_tmp" 2>/dev/null && cmp "$_f" "$_tmp" >/dev/null 2>&1 \
                && mv "$_tmp" "$_dst/$_n" 2>/dev/null; then
            COPIED=$((COPIED + 1))
        else
            rm -f "$_tmp" 2>/dev/null
            log "copy failed: $_n -> $_dst"
            FAILED=$((FAILED + 1))
        fi
    done
}

# Copy new/grown logs in bounded batches to keep command lines short.
offload_to() {
    local _dst="$1/$FOLDER" _f _n
    COPIED=0
    FAILED=0
    set --
    for _f in "$SRC"/*.csv; do
        # Files the logger hasn't made readable stay private to GPM.
        [ -f "$_f" ] && [ -r "$_f" ] || continue
        _n=./${_f##*/}
        case "$_n" in
            *[!A-Za-z0-9_./-]*) copy_batch "$_dst" "$_n"; continue ;;
        esac
        set -- "$@" "$_n"
        if [ "$#" -eq 128 ]; then
            copy_batch "$_dst" "$@"
            set --
        fi
    done
    [ "$#" -eq 0 ] || copy_batch "$_dst" "$@"
    if [ "$COPIED" -gt 0 ]; then
        sync
        log "copied $COPIED log(s) to $_dst"
    fi
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

# ---- "USB drive only": the rows from the system log to the drive ----

# The owner's saved settings (only what they changed): USB only is on when
# ev_log_usb_only is true and ev_log isn't false.
usb_only() {
    case "$(hmgapid setting-get 2>/dev/null)" in
        *'"ev_log": false'*|*'"ev_log":false'*) return 1 ;;
        *'"ev_log_usb_only": true'*|*'"ev_log_usb_only":true'*) return 0 ;;
    esac
    return 1
}

# HB_*: the header block being read; HDR_*: the last complete one;
# CUR_*: the file being written (CUR_ID: the period its rows belong to)
HB_OPEN= HB_ID= HB_PREFIX= HB_TEXT=
HDR_ID= HDR_PREFIX= HDR_TEXT=
CUR_FILE= CUR_DIR= CUR_ID= CUR_ROWS=0
NODRIVE=0 FEED_DRIVE=

# a new file for period $1 on the first drive that has the folder; 1 if none
open_file() {
    local _f _dst= _d _seq=1 _n
    for _f in $(folders); do
        _dst=$_f
        break
    done
    [ -n "$_dst" ] && [ -d "$_dst" ] || return 1
    _d=${_dst%/*}
    # the head unit's own logs first, so a new file never takes one's name
    offload_to "$_d"
    while true; do
        case ${#_seq} in
            1) _n=00$_seq ;;
            2) _n=0$_seq ;;
            *) _n=$_seq ;;
        esac
        _n=$HDR_PREFIX$_n.csv
        [ -e "$_dst/$_n" ] || [ -e "$SRC/$_n" ] || break
        _seq=$((_seq + 1))
    done
    if ! echo "$HDR_TEXT" 2>/dev/null > "$_dst/$_n"; then
        log "can't write $_dst/$_n"
        return 1
    fi
    CUR_DIR=$_dst
    CUR_FILE=$_dst/$_n
    CUR_ID=$1
    CUR_ROWS=0
    NODRIVE=0
    log "logging to $CUR_FILE"
    if [ "$FEED_DRIVE" != "$_d" ]; then
        FEED_DRIVE=$_d
        toast "AA EV logs: logging to the USB drive (egmp-aa-ev-logs). Turn the car off before removing it."
    fi
}

# one row of period $1 to the drive
write_row() {
    if [ -n "$CUR_FILE" ]; then
        if [ ! -d "$CUR_DIR" ]; then
            log "the drive is gone: $CUR_DIR"
            CUR_FILE=
            FEED_DRIVE=
        elif [ "$1" != "$CUR_ID" ] || [ "$CUR_ROWS" -ge "$MAX_ROWS" ]; then
            CUR_FILE=
        fi
    fi
    if [ -z "$CUR_FILE" ]; then
        # no drive: look on the first row, then every 4th (rows come every 5 s)
        NODRIVE=$((NODRIVE + 1))
        [ $((NODRIVE % 4)) = 1 ] || return 0
        open_file "$1" || return 0
    fi
    if echo "$2" 2>/dev/null >> "$CUR_FILE"; then
        CUR_ROWS=$((CUR_ROWS + 1))
    else
        log "write failed: $CUR_FILE"
        CUR_FILE=
        FEED_DRIVE=
    fi
}

# one line of the feed (EvUsbFeed.java: B <period> <prefix>, H <header line>, E, R <period> <row>)
feed() {
    local _rest _id
    case "$1" in
        *\\*) ;;
        "B "*)
            _rest=${1#B }
            HB_ID=${_rest%% *}
            HB_PREFIX=${_rest#* }
            HB_TEXT=
            HB_OPEN=1
            # the prefix becomes a file name: egmp-<model>-<date>-
            case "$HB_PREFIX" in
                egmp-*-) ;;
                *) HB_OPEN= ;;
            esac
            case "$HB_PREFIX" in
                *[!A-Za-z0-9-]*) HB_OPEN= ;;
            esac
            ;;
        "H "*)
            [ -n "$HB_OPEN" ] || return 0
            if [ -z "$HB_TEXT" ]; then
                HB_TEXT=${1#H }
            else
                HB_TEXT="$HB_TEXT
${1#H }"
            fi
            ;;
        E)
            [ -n "$HB_OPEN" ] && [ -n "$HB_TEXT" ] || return 0
            HB_OPEN=
            HDR_ID=$HB_ID
            HDR_PREFIX=$HB_PREFIX
            HDR_TEXT=$HB_TEXT
            # once a minute: what's written so far is on the drive
            [ -z "$CUR_FILE" ] || sync
            ;;
        "R "*)
            _rest=${1#R }
            _id=${_rest%% *}
            # rows of a period whose header hasn't come yet (it's sent every minute) are skipped
            [ -n "$HDR_TEXT" ] && [ "$_id" = "$HDR_ID" ] || return 0
            write_row "$_id" "${_rest#* }"
            ;;
    esac
}

log "offload started"
if usb_only; then
    log "USB drive only: rows go straight to a drive with the $FOLDER folder, none to the head unit"
    while true; do
        logcat -v raw -s "$FEED_TAG:I" 2>/dev/null | while IFS= read -r _l; do
            feed "$_l"
        done
        [ -z "$EVLOG_PASSES" ] || exit 0
        log "logcat ended: starting it again"
        sleep 5
    done
fi
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
