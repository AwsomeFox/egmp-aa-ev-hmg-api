# Unreleased

- Remove the stale `libiap2client.so` hash requirement. The mod does not load, patch or call that library; only the firmware-sensitive `libiap2session.so` EV-status bridge remains gated.

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
