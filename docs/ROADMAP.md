# Roadmap

Planned additions, roughly in priority order. Each links to what we already know. Items 1–3 are built and verified on the car (2022 Ioniq 5, 2026-10-29, v0.2.0).

## 0. HMG-API mod (built, not yet on a car)

HMG Leafia Plus is archived; its successor HMG-API hooks the car's apps while they run instead of replacing their files. `hmgapi-mod/` is this project as an HMG-API mod: the energy model, preconditioning and logs, with firmware support through HMG-API's hook maps (US Ioniq 5 260507 or newer, and US Ioniq 6 260903). Next: test the complete mod on both cars, collect Ioniq 6 logs to replace the conservative Ioniq 5 road-load baseline, and add a recipe for each other Android Auto library build. See [HMG-API-COMPARISON](HMG-API-COMPARISON.md).

## 0.1 Maps' battery % matches the dash (done, 0.7.0)

Android Auto sensor 26 carries a curve from Maps' own battery % (energy ÷ capacity) to the car's. Found in Maps' code and confirmed on the car (2026-11-10); details in [ENERGY-MODEL](ENERGY-MODEL.md#battery--curve-sensor-26-2026-11-10). Next: the Ioniq 6 recipe with sensor 26, and the 2 % zero point from low-SOC logs.

## 1. Automatic battery preconditioning (done)

When Google Maps on Android Auto is guiding to a DC fast charger, ask the car to precondition the battery, as the car's own navigation does. No buttons.

- **How:** `IHEVService` transactions 62/65/66 (`setBattCondEvStation`, `setBattCondRouteDis`, `setBattCondRemainTime`), the same inputs the car's own navigation uses, with distance and time from Maps' navigation messages (0x8006/0x8007, via Leafia's `NAVDUMP` hook). Java only, in GPM, mirroring the manual Launchpad app's command profile. Details in [PRECONDITIONING.md](PRECONDITIONING.md).
- **Status (2026-10-29): verified on the car.** Always on, no switch (the car's battery-conditioning setting is the off control). Triggers when the next stop is a charging stop above 50 kW at most 60 min away. On short routes the request goes REQUESTED → ACTIVE (the car's indicator comes on); ending the route cancels it (Off within ~2.5 s); on a 58-min route it stays REQUESTED while the BMS waits to heat.
- **Still open:** a real multi-stop drive (is the single 0x8007 entry always the next stop?) and a cold-morning drive to a charger.

## 2. EV logging for model calibration (logging done, analysis next)

The model is calibrated from a few owner anchor points. Real logged drives and charging sessions let us fit it properly (cold penalty split, drivetrain losses, real capacity).

- **Goal:** the head unit logs on its own, and the logs are copied off when a USB drive is plugged in. No ADB, no laptop in the car.
- **Status (2026-10-29): v1 verified on the car.** `EvLogger` wrote format v1 every 5 s during Android Auto sessions. **v2 (built, not yet on the car):** `EvDriveLog` logs whenever the head unit is on (driving, charging, parked; `aa_active` column), samples every 1 s, writes a row every 5 s and appends to the file once a minute; charging, climate, drive-mode, altitude-change and Maps charging-plan columns ([LOGGING.md](LOGGING.md)); 100 MB folder cap. `svc/evlog-offload.sh` copies new logs to any drive with an `egmp-aa-ev-logs/` folder and shows a head-unit pop-up once per insertion (6 logs copied in the first test). On this car the phone and the drive share the one USB data port, so logs are copied when the drive is plugged in after a session. Submitted through the `ev-log` issue form; `tools/evlog.py` checks and merges them.
- **Still to build:** the analysis tool that turns logs into Wh/mi against speed and temperature and refits the cold tables (`COLD_DELTA_A`, aux table). That is the input to item 4.

## 3. Real usable capacity (done)

The model assumed the new-car 77.4 kWh. At ~105k miles the real usable capacity is lower, so Maps overestimated range.

- **How:** usable capacity = BMS remaining energy ÷ display SOC. HEV flag 0x31 `ENERGYMAP_1000MS` is the same signal as the nav packet's `EV_AVLBL_ENRG`, in 2 Wh steps like `BMS_EVENERGYCAPA` ([DATA-SOURCES.md](DATA-SOURCES.md) §1.1). The energy sent to Maps is ENERGYMAP × 2 directly; capacity is learned (preferring ≥ 60% SOC samples, since energy reaches 0 near display 2%) and kept across sessions. The charge curve and time tables are scaled to it. No setting.
- **Status (2026-10-29): verified on the car.** ~62.9 kWh usable on the owner's pack (from BMS energy ÷ SOC), and with the tables scaled to it Maps' charge times look right to the owner.
- **Not capacity:** BMS SOH (hscan 0xd6, reads 95) is a health index (resistance, balance); logged only. `BMS_EVENERGYCAPA` (0x35) is a constant 38700 (77.4 kWh nominal).
- **Open question:** recent Electrify America sessions suggest ~70–71 kWh (several deep sessions delivered more than 62.9 kWh would allow). Settle it with a logged DC charge: ΔENERGYMAP × 2 vs charger kWh × ~0.95 (DATA-SOURCES.md probe P2), and ENERGYMAP × 2 ÷ SOC near full.

## 4. Self-adjusting model (next)

Use the EV logs (item 2) to correct the model on the car: compare predicted and actual energy use per drive and adjust road load / aux power, so the model follows the actual car, season and tyres without manual recalibration. Start offline (the analysis tool refits the tables from submitted logs), then consider a slow on-car correction with tight bounds.

## 5. More cars (0.6.0: every E-GMP car HMG-API maps)

0.6.0 installs on every E-GMP model HMG-API can map (Ioniq 5, EV6, Ioniq 6), any region and firmware; HMG-API's map decides whether hooks run, the library recipe whether the energy model can, and an EV check (a battery SOC and a 50-90 kWh pack) whether it should. The EV6 US 260507 has the Ioniq 5's Android Auto library build (`48ee793a`), so it gets the full feature set with the Ioniq 5 model.

- **Library recipes for the other builds:** the Canadian cars (`a0bea1c4`, which the Wireless mod already has a recipe for) and the Ioniq 5 US 260903 (build unknown; a Car Report shows it). Each needs one copy of the stock library (kept private) and `tools/mkrecipe.py`; the library keeps its C++ symbol names, so `mkrecipe.py` could find the three sites itself.
- **Per-variant starting models:** road load from the EPA test-car list (target coefficients for every Ioniq 5, EV6, Ioniq 6, GV60 variant), picked by the pack's nominal size (HEV `BMS_EVENERGYCAPA`), the product code and the VIN's model characters; charge curves for the 58 and 84 kWh packs.
- **Sensor 26 (`RAW_EV_TRIP_SETTINGS`):** one opaque blob in the same sensor batch as 23, read by Maps. Needs a fresh Maps decompile to recover its layout, then one byte in each recipe (accept types up to 26) and the same raw send.

## To verify (existing features)

- Supercharger charge times: does Maps use the 250 and 325 kW table rows for Tesla stations (V3 and "V3.5"), and the full-speed curve for 500 kW V4 cabinets? Does it interpolate between rows or snap to the nearest? See [ENERGY-MODEL](ENERGY-MODEL.md#tesla-superchargers-2026-11-11).
- Per-connector charge limits (`1.12`): does `{TESLA_SUPERCHARGER (8), 97000}` change Maps' Supercharger times? If so, it replaces the listed-power rows.
- Cold model on a real winter trip: does Maps add the stop the warm model missed at ~26 °F? (Outside temperature itself is confirmed: raw × 0.5 − 40 °C matches the dash.)
