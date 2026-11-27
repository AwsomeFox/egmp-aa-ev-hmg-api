# 0.5.3 — experimental

- Accept Wireless CarPlay + Android Auto 3.x (`>=2.2.0-rc.4 <4.0.0-0`). Wireless 3.0 moves turn-by-turn to its own mod (Phone Turn-by-Turn) and keeps the shared CarPlay identification API this mod uses. 4.0.0 pre-releases are not accepted.
- Document that the Korean EV6 (`CVEV.KOR.`, 260902) stays inactive: its HMG-API map lacks four of the iAP2 service points the mod requires.
- No code changes.

# 0.5.2 — experimental

- Add Hyundai Ioniq 6 (`CEEV`) profile selection with prepared 53 and 77.4 kWh variants.
- Use an Ioniq 6-specific estimated charging curve and consumption model derived from the earlier local 74 kWh usable-energy draft, normalized to the current nominal-capacity convention.
- Keep the existing HMG-API map, required-hook and exact native-library checks fail-closed. The new risk is limited to unvalidated Apple Maps charging, arrival-energy and charging-stop estimates after compatibility checks pass.
- Add the 53 kWh manual capacity override and package-engine coverage for Ioniq 6 profile installation and removal.

# 0.5.1 — experimental

- Accept the existing Ioniq 5 (`NEEV23`, `NEEV`) and EV6 (`CVEV`) profiles without restricting the firmware region or version. A resolving HMG-API map and the exact `libiap2session.so` hash remain mandatory.
- Make native-hash refusal logs identify the mismatched file and show its expected and actual hashes explicitly.
- Remove the stale `libiap2client.so` hash requirement. The mod does not load, patch or call that library.
- Add pre-generated 58, 72.6, 77.4 and 84 kWh Ioniq 5 profiles and 58, 77.4 and 84 kWh EV6 profiles. Runtime reads `BMS_EVENERGYCAPA` to select a bundled profile; it performs no on-car scaling and retries without sending EV status while the signal is unavailable, invalid or unsupported.
- Select the nearest prepared capacity profile within ±1,000 Wh to tolerate small BMS reporting differences; reject ties and values outside the limit.
- Add an optional battery-capacity override setting while keeping automatic BMS selection as the default.
- Log battery-profile selection once, show unexpected capacities as red HMG-API errors, suppress identical retry noise and simplify recurring status lines.

# 0.5.0 — experimental

- Move the CarPlay vehicle identity contribution to Wireless CarPlay + Android Auto's shared identification API. This release no longer patches `libiap2session.so` itself, avoiding competing native identity patches.
- Require HMG-API 1.1.0-rc.3 or newer and Wireless CarPlay + Android Auto 2.2.0-rc.4 or newer. Wireless rc.4 is the first release containing the shared identity API; HMG-API rc.3 is its required core.
- Retain 0.4.2's Hyundai-style whole-percent SOC flooring and 0.4.3's ten-second status cadence with one-minute, 25 W-rounded auxiliary-power averages.
- Validate the signed package off-car against the exact HMG-API rc.3 and Wireless rc.4 tags for all three supported product profiles.

# 0.4.3 — experimental

- Send vehicle status every ten seconds instead of every five seconds.
- Average valid auxiliary-power samples over one-minute windows, round the result to the nearest 25 W and publish it only at minute boundaries. Hold the profile default until the first complete window and retain the last published average through missing samples.
- Add deterministic regression coverage for window timing, rounding and missing samples.

# 0.4.2 — experimental

- Floor live battery state of charge to a whole percent before sending it to Apple Maps, matching Hyundai's EV app treatment of the half-percent `DISPLAY_SOC` signal.
- Add regression coverage for half-percent SOC values and invalid inputs.
- Native patch, energy models, auxiliary-power behavior and five-second update policy are unchanged.

# 0.4.1 — experimental

- Accept the 2022 US Ioniq 5 (`NEEV.USA`, firmware `260507`) with the existing 77.4 kWh profile; on-car validation pending.
- Include both detected native-library MD5 hashes in rejection logs; exact-hash guards remain unchanged.
- Native patch, energy models and five-second update policy are unchanged.

# 0.4.0 — experimental first release

- One signed native HMG-API mod download with automatic profile selection at Apply, requiring official beta6 or newer.
- Live SOC/range/auxiliary power and bundled vehicle contracts.
- Exact native-hash gates, HMG-API map checks, signed updates, and rotating logs. Optional NEMESIS wrappers remain available for testing.
- Ioniq 5 integration tested; EV6 profile copied and unvalidated.
- Known issue: route recalculation at the five-second status cadence remains under investigation.
