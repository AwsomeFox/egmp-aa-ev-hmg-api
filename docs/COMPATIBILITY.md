# Compatibility

One mod ZIP supports the configurations in `release.json`: NEEV23 USA 260507 (tested Ioniq 5 integration), NEEV USA 260507 (2022 Ioniq 5, newly accepted; on-car validation pending), or CVEV USA 260507 (untested EV6). EV6 numerical values are copied from Ioniq 5 and likely inaccurate. All listed configurations currently use a 77.4 kWh battery model.

Required on-disk MD5s:

| Library | MD5 |
|---|---|
| libiap2session.so | fc7cb675b46a44bd893a1edc054c5e71 |

HMG-API must resolve its map on the actual car. Our startup repeats native-hash and required-point checks; unknown native layouts remain inactive. HMG-API supporting a vehicle/firmware does not establish this mod's native ABI or contract accuracy on it. No 260903 support is claimed.

On a native-library mismatch, the mod remains inactive and logs the detected `libiap2session.so` MD5 (`unavailable` if the library cannot be read). The hash guard runs at startup; accepting a model identifier does not bypass it. `libiap2client.so` is not loaded, patched or used by this mod and is not a compatibility requirement.
