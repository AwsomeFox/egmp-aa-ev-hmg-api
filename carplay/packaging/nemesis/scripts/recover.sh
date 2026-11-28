#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
SCRIPT_DIR=${0%/*}
cd "$SCRIPT_DIR/.."
. scripts/common.sh
init_logs recovery
[ -f "$BACKUP/transaction" ] || { echo 'No interrupted EV transaction.'; exit 0; }
if [ "$(cat "$BACKUP/transaction")" = committed ]; then
    echo managed > "$BACKUP/managed"
elif [ "$(cat "$BACKUP/transaction")" != preparing ]; then restore_previous; fi
rm -rf "$BACKUP/previous-mod"
rm -f "$BACKUP/transaction"
sync
echo 'EV transaction recovered. Fully reboot the HU.'
