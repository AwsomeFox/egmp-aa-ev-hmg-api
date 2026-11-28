#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
SCRIPT_DIR=${0%/*}
cd "$SCRIPT_DIR/.."
. scripts/common.sh
init_logs preuninstall
# NEMESIS stages an update before calling the old preuninstall hook.
if [ -f "$STAGE/hmgapi-wrapper-v1" ]; then echo 'NEMESIS update: retaining the HMG-API mod for replacement.'; exit 0; fi
[ ! -f "$BACKUP/transaction" ] && [ ! -d "$BACKUP/previous-mod" ] || { fail 'Resolve the recovery snapshot before uninstalling'; exit 1; }
[ -f "$BACKUP/managed" ] || { echo 'No successfully managed EV installation to remove.'; exit 0; }
[ ! -f "$DATA/state/journal.json" ] || { fail 'Resolve the HMG-API trial/unfinished apply first'; exit 1; }
if [ -d "$DATA/mods/$MOD" ]; then
    run "$HMG" disable "$MOD"
    check_plan
    run "$HMG" engine apply
    run "$HMG" engine remove "$MOD"
else check_plan; run "$HMG" engine apply; fi
sync
echo 'EV mod removed through HMG-API. Wireless CarPlay retained. Fully reboot the HU.'
