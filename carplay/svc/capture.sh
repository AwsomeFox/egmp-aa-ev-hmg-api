#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# HMG-API supplies a private service data directory. Keep the agreed ~1 MiB ring.
umask 022
LOG=/data/nemesis/logs/carplay.ev-routing/runtime
mkdir -p "$LOG" || exit 1
exec /system/bin/logcat -b main -b system -v threadtime -f "$LOG/runtime.log" -r 512 -n 1 HMG-API:I CarPlayEV:I Iap2:I Iap2Service:I AndroidRuntime:E libc:F '*:S'
