#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
SCRIPT_DIR=${0%/*}
cd "$SCRIPT_DIR/.."
. scripts/common.sh
init_logs preinstall
[ ! -f "$BACKUP/transaction" ] && [ ! -d "$BACKUP/previous-mod" ] || { fail 'Resolve the prior EV transaction before reinstalling'; exit 1; }
find_usb || true
select_profile || { export_report; exit 1; }
check_runtime || { export_report; exit 1; }
check_native || { export_report; exit 1; }
check_plan || { export_report; exit 1; }
echo 'Checks passed. NEMESIS will install the signed EV mod through HMG-API.'
export_report
