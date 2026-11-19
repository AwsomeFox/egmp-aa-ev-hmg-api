# v0.5.0 — Experimental

This release moves E-GMP vehicle information into Wireless CarPlay + Android Auto's shared CarPlay identification API. It no longer carries its own `libiap2session.so` patch, so the wireless mod owns the native identification change and this mod contributes the vehicle data through the published API.

- Requires **HMG-API 1.1.0-rc.3 or newer**.
- Requires **Wireless CarPlay + Android Auto 2.2.0-rc.4 or newer**. Wireless rc.4—not rc.3—is the first release with the shared CarPlay identification API.
- Keeps live SOC and range updates at ten-second intervals.
- Floors half-percent SOC readings to the whole percentage shown by Hyundai's EV display.
- Averages auxiliary power over one-minute windows, rounds it to 25 W, and retains the last published value when samples are missing.
- Uses the same signing identity and mod ID, so HMG-API can update an existing installation in place.

The signed ZIP was built and checked off-car against the exact HMG-API rc.3 and Wireless rc.4 tags. The shared-identity release still needs an on-car installation and drive test; EV6 energy values remain copied from the Ioniq 5.

# v0.4.3 — Experimental

- Send vehicle status to Apple Maps every ten seconds instead of every five seconds.
- Sample auxiliary power with each status, average valid readings over one-minute windows, round each published average to the nearest 25 W and keep that value unchanged until the next minute boundary. The profile default remains in use until the first window completes; missing readings do not become zero.
- Add deterministic host tests for the first-minute hold, minute-boundary publication, 25 W rounding and missing-sample retention.

This is an experimental mitigation for repeated route recalculation. Apple's required status cadence and contract-update semantics remain undocumented, so on-car validation is still required.

# v0.4.2 — Experimental

- Floor live battery state of charge to a whole percent before sending it to Apple Maps. Hyundai's EV app converts its half-percent `DISPLAY_SOC` signal to an integer by truncation; this keeps Maps from showing one percentage point more than the car when the underlying value ends in `.5`.
- Add host regression tests for zero, middle and upper half-percent values and invalid SOC inputs.
- Keep the existing native patch, vehicle profiles, energy models, auxiliary-power behavior and five-second update policy unchanged.

Install the single signed mod ZIP through HMG-API, apply changes, fully reboot and re-pair the iPhone if this is the first installation. The five-second route-recalculation issue, capacity semantics and EV6 calibration remain under investigation.

# v0.4.1 — Experimental

- Add the 2022 US Ioniq 5 with a 77.4 kWh battery and firmware `NEEV.USA.S5W_M.V015.012.260507` to the accepted vehicle list. It uses the existing Ioniq 5 profile; on-car validation is pending.
- Log both detected native-library MD5 hashes when an unsupported hash keeps EV routing inactive. Unreadable hashes are reported as `unavailable`.
- Keep the existing exact-hash checks and required HMG-API hook checks. No native patch, energy model or update-frequency changes.

Install the single signed mod ZIP through HMG-API, apply changes and fully reboot. For a first installation, manually delete the iPhone–HU pairing on both devices and pair again. See [installation instructions](https://github.com/isaacwisdom/egmp-carplay-ev-routing/blob/main/docs/INSTALLATION.md).

The five-second route recalculation issue remains under investigation. EV6 values remain copied and unvalidated. NEMESIS store rollout remains on hold.

# v0.4.0 — Experimental

First public release of E-GMP CarPlay EV Routing for Gen5W, using official HMG-API **1.0.0-beta.6 or newer** and its Wireless CarPlay + Android Auto mod.

One signed ZIP automatically selects the active vehicle profile at Apply. Live SOC, range and auxiliary power feed Apple Maps EV routing, with bundled charging/consumption contracts and rotating runtime logs.

- **Ioniq 5:** NEEV23 USA 260507, 77.4 kWh; final package installed and confirmed working by its owner.
- **EV6:** CVEV USA 260507; untested, with copied Ioniq 5 numerical values that are unvalidated and likely inaccurate.
- Other firmware/configurations, including 260903, are unsupported by this release.

## Install

Install HMG-API beta6+ and its Wireless CarPlay + Android Auto mod first; confirm CarPlay works. Put `egmp-carplay-ev-routing-v0.4.0.zip` **unchanged** in `HMGAPI/updates/` on USB. In HMG-API, use **Check USB updates**, install/enable, review and **Apply changes**. Remove USB and fully reboot. **Manually delete the existing iPhone–HU pairing on both devices, then pair again and allow CarPlay.** Reconnecting the existing pairing is not enough.

See [detailed installation, update and removal instructions](https://github.com/isaacwisdom/egmp-carplay-ev-routing/blob/main/docs/INSTALLATION.md). The mod is signed with author UUID `b45fb2d9-e6e8-83a2-bce9-cd5c40d2232d`; beta6 may show an unfamiliar-author warning. No OEM binaries are distributed.

## Known limits

Maps may recalculate routes at the five-second status cadence; this remains under investigation. Energy models are uncalibrated, and long-drive reliability needs more testing. Both source profiles are bundled, but only one active profile is applied automatically. The source ZIP is for developers; install only the mod ZIP.

Special credit to [augard/evmaps](https://github.com/augard/evmaps) for charging/consumption reference material, and to HMG-API, NEMESIS and egmp-aa-ev-feat.
