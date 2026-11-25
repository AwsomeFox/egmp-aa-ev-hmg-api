# Compatibility

One mod ZIP accepts the Ioniq 5 model families NEEV23 and NEEV, the Ioniq 6 family CEEV and the EV6 family CVEV without restricting region or firmware. The tested baseline remains USA 260507. Runtime selects a prepared profile by exact BMS nominal capacity: Ioniq 5 supports 58, 72.6, 77.4 and 84 kWh; Ioniq 6 supports 53 and 77.4 kWh; EV6 supports 58, 77.4 and 84 kWh. EV6 and Ioniq 6 energy-model values are unvalidated and may produce inaccurate Apple Maps estimates.

Required on-disk MD5s:

| Library | MD5 |
|---|---|
| libiap2session.so | fc7cb675b46a44bd893a1edc054c5e71 |

HMG-API must resolve its map on the actual car. Startup repeats both native-hash and required-point checks; unknown native layouts remain inactive. Accepting a model family does not establish the mod's native ABI, battery-profile fit or contract accuracy on every vehicle in that family.

On a native-library mismatch, the mod remains inactive and logs `libiap2session.so`'s expected hash, actual hash and explicit `match` or `MISMATCH` status (`unavailable` if it cannot be read). The hash guard runs at startup; accepting a model identifier does not bypass it. `libiap2client.so` is not loaded, patched or used by this mod and is not a compatibility requirement.
