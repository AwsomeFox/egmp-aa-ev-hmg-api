#!/bin/sh
# The offload service script in a sandbox: a fake hmgapid that names one
# drive's egmp-aa-ev-logs folder (what HMG-API answers for mod.json "usb"),
# GPM's evlog folder with two logs, two passes. Logs are copied, grown logs
# copied again, nothing copied to a drive without the folder.
# Then "USB drive only" (ev_log_usb_only): a fake logcat plays the logger's
# feed (EvUsbFeed.java), and the rows land in a file on the drive, with the
# header, under a name no log has yet; nothing new in GPM's folder.
set -e
cd "$(dirname "$0")/../.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/src" "$T/usb0/egmp-aa-ev-logs" "$T/usb1"
cat > "$T/bin/hmgapid" <<SH
#!/bin/sh
[ "\$1" = usb ] && [ "\$2" = egmp-aa-ev ] && [ ! -f "$T/nodrive" ] && echo "$T/usb0/egmp-aa-ev-logs"
[ "\$1" = setting-get ] && cat "$T/settings" 2>/dev/null
exit 0
SH
printf '#!/bin/sh\nexit 0\n' > "$T/bin/am"
chmod 755 "$T/bin/hmgapid" "$T/bin/am"
printf '# egmp-aa-ev-log v2\nt_s,soc_half_pct\n0,92\n' > "$T/src/egmp-NEEV23-20261103-001.csv"
printf '# egmp-aa-ev-log v2\nt_s,soc_half_pct\n0,80\n' > "$T/src/egmp-NEEV23-20261103-002.csv"
fail=0
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fail=1; fi; }
PATH="$T/bin:$PATH" EVLOG_SRC="$T/src" EVLOG_POLL_S=0 EVLOG_PASSES=1 sh hmgapi-mod/scripts/offload.sh > "$T/out" 2>&1
check "first log copied" 'cmp -s "$T/src/egmp-NEEV23-20261103-001.csv" "$T/usb0/egmp-aa-ev-logs/egmp-NEEV23-20261103-001.csv"'
check "second log copied" 'cmp -s "$T/src/egmp-NEEV23-20261103-002.csv" "$T/usb0/egmp-aa-ev-logs/egmp-NEEV23-20261103-002.csv"'
check "no temp files left" '[ -z "$(ls -A "$T/usb0/egmp-aa-ev-logs" | grep "^\.")" ]'
check "drive without the folder untouched" '[ -z "$(ls -A "$T/usb1")" ]'
echo "5,79" >> "$T/src/egmp-NEEV23-20261103-002.csv"
PATH="$T/bin:$PATH" EVLOG_SRC="$T/src" EVLOG_POLL_S=0 EVLOG_PASSES=1 sh hmgapi-mod/scripts/offload.sh >> "$T/out" 2>&1
check "grown log copied again" 'cmp -s "$T/src/egmp-NEEV23-20261103-002.csv" "$T/usb0/egmp-aa-ev-logs/egmp-NEEV23-20261103-002.csv"'
check "logged the copies" 'grep -q "copied 2 log(s)" "$T/out"'

# Android toolbox's listing layout, with one trace line per invocation.
cat > "$T/bin/ls" <<SH
#!/bin/sh
echo list >> "$T/listings"
shift
for file do
    [ -f "\$file" ] || continue
    printf '%s owner group %s 2026-11-03 12:00 %s\n' -rw-r--r-- "\$(wc -c < "\$file")" "\${file##*/}"
done
SH
chmod 755 "$T/bin/ls"
poll_three_times() {
    PATH="$T/bin:$PATH" EVLOG_SRC="$T/src" EVLOG_POLL_S=0 EVLOG_PASSES=3 sh hmgapi-mod/scripts/offload.sh >> "$T/out" 2>&1
}
poll_three_times
check "unchanged logs use two listings per poll" '[ "$(wc -l < "$T/listings")" -eq 6 ]'

cat > "$T/bin/sleep" <<SH
#!/bin/sh
if [ -f "$T/nodrive" ]; then
    rm "$T/nodrive"
else
    touch "$T/nodrive"
    echo "5,91" >> "$T/src/egmp-NEEV23-20261103-001.csv"
fi
SH
chmod 755 "$T/bin/sleep"
: > "$T/listings"
poll_three_times
check "no listing while the drive is absent" '[ "$(wc -l < "$T/listings")" -eq 4 ]'
check "an older log that grew while disconnected is copied on reconnect" 'cmp -s "$T/src/egmp-NEEV23-20261103-001.csv" "$T/usb0/egmp-aa-ev-logs/egmp-NEEV23-20261103-001.csv"'
rm "$T/bin/ls" "$T/bin/sleep"

# ---- USB drive only ----
D="$T/usb0/egmp-aa-ev-logs"
cat > "$T/bin/logcat" <<SH
#!/bin/sh
cat "$T/feed"
SH
chmod 755 "$T/bin/logcat"
echo '{"ev_log_usb_only": true}' > "$T/settings"
before=$(ls "$T/src" | sort)
feed() { PATH="$T/bin:$PATH" EVLOG_SRC="$T/src" EVLOG_POLL_S=0 EVLOG_PASSES=1 sh hmgapi-mod/scripts/offload.sh >> "$T/out" 2>&1; }
cat > "$T/feed" <<'F'
--------- beginning of /dev/log/main
R 7000 0,91,60000
B 7000 egmp-NEEV23-20261103-
H # egmp-aa-ev-log v2
H # product=NEEV.USA.0000.V015.012.260507
H t_s,soc_half_pct,energy_wh
E
R 7000 5,92,60100
R 7000 10,92,60050
B 7000 egmp-NEEV23-20261103-
H # egmp-aa-ev-log v2
H # product=NEEV.USA.0000.V015.012.260507
H t_s,soc_half_pct,energy_wh
E
R 7000 15,92,60000
R 9000 0,90,59000
B 9000 egmp-NEEV23-20261103-
H # egmp-aa-ev-log v2
H t_s,soc_half_pct,energy_wh
E
R 9000 5,90,58900
B 9500 ../../evil-
H # nope
E
R 9500 0,1,1
R 9000 10,90,58\800
F
feed
printf '# egmp-aa-ev-log v2\n# product=NEEV.USA.0000.V015.012.260507\nt_s,soc_half_pct,energy_wh\n5,92,60100\n10,92,60050\n15,92,60000\n' > "$T/want3"
printf '# egmp-aa-ev-log v2\nt_s,soc_half_pct,energy_wh\n5,90,58900\n' > "$T/want4"
check "usb only: rows written to the drive, with the header, under the next free name" 'cmp -s "$T/want3" "$D/egmp-NEEV23-20261103-003.csv"'
check "usb only: a new period gets its own file, and its rows wait for its header" 'cmp -s "$T/want4" "$D/egmp-NEEV23-20261103-004.csv"'
check "usb only: a prefix that isn't a log name writes nothing" '[ "$(ls "$D" | sort | tr "\n" " ")" = "egmp-NEEV23-20261103-001.csv egmp-NEEV23-20261103-002.csv egmp-NEEV23-20261103-003.csv egmp-NEEV23-20261103-004.csv " ] && [ -z "$(ls "$T" | grep evil)" ]'
check "usb only: nothing written to the head unit's folder" '[ "$(ls "$T/src" | sort)" = "$before" ]'
check "usb only: the head unit's older logs still on the drive" 'cmp -s "$T/src/egmp-NEEV23-20261103-002.csv" "$D/egmp-NEEV23-20261103-002.csv"'
check "usb only: said where it logs" 'grep -q "logging to $D/egmp-NEEV23-20261103-003.csv" "$T/out"'
# no drive: rows are dropped, nothing is written anywhere
touch "$T/nodrive"
snap=$(ls -l "$D" "$T/src")
feed
check "usb only, no drive: nothing written" '[ "$(ls -l "$D" "$T/src")" = "$snap" ]'
rm "$T/nodrive"
# ev_log off wins: the plain copy loop (and it ends after its pass)
echo '{"ev_log": false, "ev_log_usb_only": true}' > "$T/settings"
feed
check "ev_log off: no feed read" '[ "$(ls -l "$D" "$T/src")" = "$snap" ]'
[ $fail -eq 0 ] || cat "$T/out"
exit $fail
