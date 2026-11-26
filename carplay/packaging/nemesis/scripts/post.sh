#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
SCRIPT_DIR=${0%/*}
cd "$SCRIPT_DIR/.."
. scripts/common.sh
init_logs postinstall
find_usb || true
trap 'export_report' EXIT
select_profile
check_runtime
check_native
mkdir -p "$BACKUP"
umask 077
[ ! -d "$BACKUP/previous-mod" ] || { fail 'A prior recovery snapshot exists; resolve it first'; exit 1; }
mod_state "$MOD" > "$BACKUP/previous-state"
echo preparing > "$BACKUP/transaction"
if [ -d "$DATA/mods/$MOD" ]; then cp -R "$DATA/mods/$MOD" "$BACKUP/previous-mod"; fi
echo pending > "$BACKUP/transaction"
sync
if ! run "$HMG" engine install "$PAYLOAD" --uuid "$UUID"; then
    echo 'Signed mod installation failed; restoring the previous EV state.'
    if restore_previous; then rm -rf "$BACKUP/previous-mod"; rm -f "$BACKUP/transaction"; fi
    exit 1
fi
activate() {
    if [ "$(cat "$BACKUP/previous-state")" = disabled ]; then run "$HMG" disable "$MOD"
    else run "$HMG" enable "$MOD"; fi
}
if activate && check_plan && run "$HMG" engine apply && sync; then
    echo committed > "$BACKUP/transaction"
    echo managed > "$BACKUP/managed"
    rm -rf "$BACKUP/previous-mod"
    rm -f "$BACKUP/transaction"
    echo 'EV routing installed through HMG-API. Remove the USB and fully reboot the HU.'
else
    echo 'Installation failed; restoring the previous EV mod through HMG-API.'
    if restore_previous; then rm -rf "$BACKUP/previous-mod"; rm -f "$BACKUP/transaction"; echo 'Previous EV state restored.'
    else echo "RECOVERY REQUIRED: keep $BACKUP and the HMG-API logs; do not force removal."; fi
    exit 1
fi
