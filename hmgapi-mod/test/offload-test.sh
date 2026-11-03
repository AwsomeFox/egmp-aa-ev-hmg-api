#!/bin/sh
# The offload service script in a sandbox: a fake hmgapid that names one
# drive's egmp-aa-ev-logs folder (what HMG-API answers for mod.json "usb"),
# GPM's evlog folder with two logs, two passes. Logs are copied, grown logs
# copied again, nothing copied to a drive without the folder.
set -e
cd "$(dirname "$0")/../.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/src" "$T/usb0/egmp-aa-ev-logs" "$T/usb1"
cat > "$T/bin/hmgapid" <<SH
#!/bin/sh
[ "\$1" = usb ] && [ "\$2" = egmp-aa-ev ] && echo "$T/usb0/egmp-aa-ev-logs"
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
[ $fail -eq 0 ] || cat "$T/out"
exit $fail
