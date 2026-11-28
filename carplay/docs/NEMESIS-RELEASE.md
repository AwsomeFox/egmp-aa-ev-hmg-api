# NEMESIS store rollout — on hold

**Rollout is on hold.** Upstream HMG-API v1.0.0 operational issues need investigation and HU verification before proceeding. Its appearance in the store alone does not release this hold. Our off-car checks establish package compatibility, not reliability of the core update.

The existing signed **0.4.0** native mod stays the payload. The store package uses HMG-API v1’s official lifecycle scripts; this is packaging for the same mod, not a second EV implementation.

## Prepared downloads

| File in `dist/nemesis/` | Purpose |
|---|---|
| `egmp-carplay-ev-routing-0.4.0-nemesis-package.zip` | Upload this file to the NEMESIS store, or use it with `nemesis install`. |
| `egmp-carplay-ev-routing-0.4.0-nemesis.zip` | USB layout: extract onto the stick’s root and let NEMESIS install it. |
| `SHA256SUMS` | Checksums for these two files. |
| `store-readiness.json` | Preparation evidence and upload filename; not an installer or store-submission API request. |

Requires **NEMESIS 1.16.3 or newer** for dependency resolution and `!id` conflicts; preparation was checked against 1.16.7.

The package ID is **`hmgapi.egmp-carplay-ev-routing`**, matching HMG-API’s own NEMESIS sync. Dependencies are HMG-API **1.0+** and its Wireless CarPlay + Android Auto package **2.2.0+**. The old experimental `carplay.ev-routing` wrapper conflicts and must be uninstalled first. The native mod ID and signing key are unchanged.

HMG-API’s generator supplies all install/update/remove scripts unchanged. Our only descriptor adjustment declares the wireless dependency and old-wrapper conflict. Both profiles remain inside one signed payload; HMG-API selects one active profile at Apply.

## Build the wrappers without rebuilding the mod

```sh
python3 tools/prepare-nemesis.py \
  --mod-zip dist/egmp-carplay-ev-routing-v0.4.0.zip \
  --hmgapi-source /path/to/HMG-API-1.0.0
```

This verifies the published ZIP’s SHA256, signature, author UUID, ID and version, runs upstream `tools/mknemesismod.sh`, and generates the wrapper/download/checksums. It never needs the author’s private key. Published native ZIPs must not be replaced. Preparation is pinned in `packaging/nemesis-release.json` to the reviewed upstream version and existing tested payload.

## After the hold is lifted and dependencies are in the store

1. Confirm the store offers `hmgapi` **1.0+** and `hmgapi.wireless-carplay-aa` **2.2.0+** under those exact IDs. Verify compatibility for our listed cars. Store packaging support by itself does not establish that the listings are live.
2. Test the prepared wrapper through NEMESIS on the HU. Native v0.4.0 is already HU-tested; the official wrapper has only been tested off the car so far.
3. Publish the two prepared NEMESIS downloads alongside the existing native mod release, with their separate checksums. Keep the existing native mod/source assets intact.
4. Upload **the `-nemesis-package.zip`** at [the NEMESIS submission portal](https://portal.xwtk.cloud/nemesis/), give the repository URL and choose **Submit for approval**. Review ID, version, dependencies, compatibility and SHA256. Do not upload the USB layout or source ZIP as the store package.
5. After the listing is live, make NEMESIS Store the first install path in the README and installation guide. Retain the native HMG-API import option.

Owners then install from NEMESIS Store, remove USB and reboot, delete/recreate the iPhone–HU pairing for first-time setup, and add the vehicle in Apple Maps. The existing [illustrated directions](INSTALLATION.md#re-pair-your-iphone-required) apply. HMG-API’s sync lets NEMESIS and HMG-API manage the same installed mod; one mod is installed, regardless of entry point.

## Catalogue and remaining checks

HMG-API’s community catalogue is separate from the NEMESIS store. Its `tools/catalog.py add` can prepare our catalogue and author-key entries from the already published native ZIP. That PR is not a NEMESIS store submission.

Off-car checks cover the exact embedded ZIP, upstream script identity, real v1 engine install/apply, per-car profile choice, updates retaining disabled state/settings, removal restoring the original system, missing-wireless refusal, and NEMESIS descriptor dependencies. The known Maps recalculation issue and EV6 calibration caveats still apply.
