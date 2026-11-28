# Logging and diagnostics

## Recommended: HMG-API's problem report

HMG-API 1.1.0-rc.6 and newer can export a focused diagnostic report without a computer:

1. Insert a USB stick and leave it connected.
2. Open **HMG-API → Logs → Record a problem**.
3. Leave **Record new log lines** enabled. The optional crash-dump, Android crash-report and kernel-log switches can normally stay off.
4. Select **Start recording**, reproduce the problem, return to the same page and select **Stop**.
5. Remove the USB stick after HMG-API confirms that the report was saved. The text file is in `HMG-API reports/`.

For a problem that already happened, turn **Record new log lines** off and select **Collect now**. HMG-API also keeps a copy under `/data/hmgapi/public/reports/`, which can be retrieved with adb if USB export is unavailable.

The report includes the head-unit model and software, installed mods, HMG-API state and errors, and selected log lines for CarPlay, Android Auto, audio, Bluetooth, Wi-Fi and HMG-API. HMG-API removes device addresses, phone numbers, VINs, serial numbers, device IDs and GPS positions. CarPlay or Android Auto messages can still contain a phone name or media title, and optional crash reports can contain text the crashed process was handling. Review the file before sharing it.

This feature was added in HMG-API 1.1.0-rc.6. Earlier supported versions can view the log in the HMG-API app but do not have **Record a problem**; update HMG-API when practical.

## What EV Routing logs mean

Battery-profile selection is logged once per service start. Automatic selection reports the BMS energy capacity, selected profile and difference. A manual override identifies itself as the source.

An unexpected BMS capacity uses HMG-API's error channel and appears red in the log viewer. Identical retry failures are suppressed until the condition changes. The error reports the nearest profile, difference and ±1,000 Wh tolerance when applicable.

`status sent: result=0` means the stock native sender accepted the message. It does not independently prove that the iPhone received or accepted it. The recurring status line contains SOC, energy, range, an optional full-range estimate, auxiliary watts/source and the low-battery state.

If the native-library hash is unsupported, the mod stays inactive and logs `CarPlayEV: native hash mismatch; EV routing inactive`. The same line includes `libiap2session.so`'s expected hash, actual hash and an explicit `match` or `MISMATCH` status. An unreadable library is reported as `actual=unavailable status=MISMATCH`. Include that complete line when reporting compatibility problems. `libiap2client.so` is not used or checked.

## Fallbacks for older HMG-API versions

To watch the relevant messages over adb:

```sh
adb logcat -v threadtime -s HMG-API:I CarPlayEV:I AndroidRuntime:E
```

The mod's rotating collector also keeps runtime logs in:

```text
/data/nemesis/logs/carplay.ev-routing/runtime/runtime.log
/data/nemesis/logs/carplay.ev-routing/runtime/runtime.log.1
```

These can be pulled with adb or exported with NEMESIS Manager's copy-logs function. Prefer HMG-API's problem report when it is available because it gathers the relevant HMG-API state, filters unrelated applications and scrubs common identifiers.

The optional legacy NEMESIS wrapper has separate install/recovery diagnostics. It may write `NEMESIS-report-carplay.ev-routing/` to USB and can include OEM APK, ODEX or library inputs. Treat those files as private and share them only intentionally. Native HMG-API installation does not create that legacy report. Nothing is uploaded automatically.
