# Validation and limits

On the target Ioniq 5, the HMG-API port loads, registers the stock status listener, and produces repeated successful live native sends. That test predates v0.5.1's BMS-capacity resolver. SOC/range are read through HSCAN; capacity and auxiliary power use read-only HEV transactions. Energy/full-range are derived from BMS nominal capacity. EV6 integration/model accuracy and the capacity resolver still require on-car validation.

Offline checks cover Java producer fixtures/lifecycle, native ARM serialization, exact-byte recipe refusal, HMG-API hook plans/signatures, NEMESIS metadata/dependencies, and mocked wrapper install/update/remove/recovery. Build evidence is generated under `out/`; On 2026-11-03, the user confirmed installation, Apply, reboot, working CarPlay and EV data with the final single-ZIP package and its fixed active-profile path. The optional NEMESIS wrapper still needs its own HU test.

Version 0.4.3 experimentally mitigates repeated route recalculation by sending status every ten seconds and changing the one-minute, 25 W-rounded auxiliary average only at minute boundaries. We still send both contracts each cycle. Apple's expected update/contract policy is not established, so this behavior requires on-car validation. Reconnect/long-drive reliability and energy-model calibration need further testing.

The shared-identity implementation requires HMG-API 1.1.0-rc.3 or newer and Wireless CarPlay + Android Auto 2.2.0-rc.4 or newer. Wireless rc.4 is the first release containing the shared CarPlay identification API; HMG-API rc.3 is its core dependency. This integration is covered by off-car compilation, hook-plan, signing and package-engine checks but still requires an on-car release test.

## Making-mods guide review

Checked against the upstream [Making mods guide](https://github.com/VysseCodes/HMG-API/wiki/Making-mods) for beta6:

| Check | Result |
|---|---|
| Declared hooks, native recipe, helper file, service and wireless dependency | Checked against beta6’s reference and shared hook planner. |
| Packaging and author continuity | The multi-vehicle payload verifies with the established key; its archive format follows upstream `mkmodpkg.sh`. Our builder publishes that native ZIP format directly; equivalent payloads are also placed in optional NEMESIS test wrappers. |
| HU commands | Upstream `tools/check-scripts.sh` passes the payload and every NEMESIS script. |
| Actual package engine | The same ZIP installs, updates and removes for NEEV23, NEEV and CVEV in the host engine; Apply selects the correct profile and removal clears it; alternate regions/firmware are accepted, while unknown models, wrong authors and altered signed content are refused. |
| Recovery and removal | 29 package/lifecycle checks pass; OEM files remain unchanged in the isolated tests. Reboot is required to discard in-process native changes. |

Author-list registration is optional and has not been requested. First installs may show an unfamiliar-author warning. No upstream issue or package submission was made by this review. The user’s final-package HU confirmation does not establish EV6 compatibility, long-drive reliability or resolve the Maps update-cadence issue above.
