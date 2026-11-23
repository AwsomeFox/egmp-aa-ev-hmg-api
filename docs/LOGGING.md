# Logs and USB diagnostics

HMG-API runs the mod's collector. It captures HMG-API/CarPlayEV, selected iAP2 errors and fatal runtime errors into:

`/data/nemesis/logs/carplay.ev-routing/runtime/runtime.log` and `.1`, at 512 KiB each (about 1 MiB total).

The runtime logs can be pulled with adb; NEMESIS Manager's copy-logs function can also export that directory. For the optional NEMESIS wrapper, install/recovery actions are recorded in `/data/nemesis/logs/carplay.ev-routing/install.log`.

To watch while connected:

```sh
adb logcat -v threadtime -s HMG-API:I CarPlayEV:I AndroidRuntime:E
```

`native_send=0` means the stock native sender accepted the message; it does not independently prove receipt/acceptance by the iPhone. Sent SOC, energy, range, warning, full-range estimate, auxiliary watts/source and contract model IDs are logged.

When the optional NEMESIS wrapper can identify its actual USB source, it writes `NEMESIS-report-carplay.ev-routing/` there, including product, expected/observed hashes and input copies of Iap2ServerApp APK/ODEX and libiap2session.so. Keep those OEM files private; send them only intentionally for compatibility analysis. USB removal or an unidentified source prevents USB export, not installation logging. Nothing is uploaded automatically.

Native HMG-API imports do not run the NEMESIS USB-report hook. They do not automatically copy OEM inputs onto USB. HMG-API owns package installation logs; the runtime collector above still runs.

If the native-library hash is unsupported, the mod stays inactive and emits a `CarPlayEV: native hash mismatch; EV routing inactive` error. It includes `libiap2session.so`'s expected hash, actual hash and an explicit `match` or `MISMATCH` status. An unreadable library is reported as `actual=unavailable status=MISMATCH`. Include that complete line when reporting compatibility problems. `libiap2client.so` is not used or checked.

The optional NEMESIS installer reports the same condition before changing EV files as `HASH_MISMATCH file=<name> expected=<hash> actual=<hash>` followed by `FAIL: Native library hash mismatch`. Those lines are also preserved in its install log and USB diagnostic report when available.
