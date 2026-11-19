# E-GMP CarPlay EV Routing

Experimental Apple Maps EV routing for Gen5W, installed as a **native HMG-API mod**. Current version: **0.5.0** (experimental).

**v0.5.0 has now been installed and tested successfully on-car.** The shared CarPlay identification path, vehicle detection and EV routing are working with the required HMG-API and Wireless CarPlay releases below.

## Install

1. Install [NEMESIS](https://github.com/xwtk/hmg_nemesis) and [HMG-API 1.1.0-rc.3 or newer](https://github.com/Open-HMG/HMG-API/releases). NEMESIS can install HMG-API itself.
2. Download HMG-API’s **[Wireless CarPlay + Android Auto](https://github.com/Open-HMG/hmgapi-wireless-cp-aa)** mod **2.2.0-rc.4 or newer** from its **[releases page](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases)**, install/enable it, apply, verify the car map and reboot. It is a separate download; confirm CarPlay works.
3. Download **[egmp-carplay-ev-routing-v0.5.0.zip](https://github.com/isaacwisdom/egmp-carplay-ev-routing/releases/download/v0.5.0/egmp-carplay-ev-routing-v0.5.0.zip)** and copy it unchanged into `HMGAPI/updates/` on a USB stick. In HMG-API, use **Check USB updates**, install it, enable it and **Apply changes**. Remove the USB and fully reboot.
4. **Manually delete the existing iPhone–HU pairing and pair again.** Remove the iPhone from the HU’s paired-device list, forget the HU in the iPhone’s Bluetooth and CarPlay settings, then pair again and allow CarPlay. This step is required after installation; reconnecting the existing pairing is not enough.

5. On your iPhone, open Apple Maps, tap your profile picture, then **Vehicles**. Follow the prompts to add your detected vehicle, or select it if it is already listed. See the [illustrated setup steps](docs/INSTALLATION.md#add-your-vehicle-in-apple-maps).

There is **one mod download** for both supported vehicles. HMG-API selects the correct bundled profile when applying the mod and writes one active profile file; the routing code always reads that file. No manual profile choice is needed. [Detailed directions](docs/INSTALLATION.md). The [NEMESIS store rollout remains on hold](docs/NEMESIS-RELEASE.md); use the native HMG-API installation route.

## Apple Maps EV routing

After adding your vehicle, select it when planning driving directions. Maps can include charging stops and estimated battery levels along the route.

<img src="images/apple-maps-ev-route-preview.png" alt="Apple Maps EV route preview showing Hyundai Ioniq 5, charging stops and estimated battery levels" width="280">

## Supported configurations

| Vehicle | Firmware | Status |
|---|---|---|
| Ioniq 5 US, 77.4 kWh | NEEV23.USA / 260507 | Integration tested on one HU; energy model accurate within 1-2%. |
| 2022 Ioniq 5 US, 77.4 kWh | NEEV.USA / 260507 | Integration tested on one HU; energy model accuracy pending. |
| Kia EV6 US | CVEV.USA / 260507 | Integration tested on one HU. **Consumption and Charging contract values copied from Ioniq 5, but appear to be accurate within 1-2%** |

An exact `libiap2session.so` hash and a resolving HMG-API map are required. `libiap2client.so` is not used or checked. Other firmware/battery configurations are unsupported, including 260903 until our native integration is validated there.

Live SOC and range are sent every ten seconds while subscribed. Auxiliary power is sampled at the same cadence, averaged over one-minute windows, rounded to the nearest 25 W and changed in the sent consumption contract only once per minute. Energy and full-charge range are estimates. Charging state, active connector and temperature are omitted. Renaming and other contract updates are not implemented.

Logs rotate between two 512 KiB files and can be exported by NEMESIS. The optional NEMESIS wrapper also writes USB install diagnostics; native HMG-API imports do not run that hook.

[Compatibility](docs/COMPATIBILITY.md) · [Contracts](docs/CONTRACTS.md) · [Logs](docs/LOGGING.md) · [Build](docs/BUILDING.md) · [Validation](docs/VALIDATION.md)

## Credits

Special credit to [augard/evmaps](https://github.com/augard/evmaps) for the charging/consumption dictionaries and reference material. Built on [HMG-API](https://github.com/Open-HMG/HMG-API) and NEMESIS, with vehicle-data research from [egmp-aa-ev-feat](https://github.com/willemcvu/egmp-aa-ev-feat). Original code: GPL-3.0-or-later; see [NOTICE](NOTICE.md). No OEM binaries are distributed.
