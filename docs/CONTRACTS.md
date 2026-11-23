# Charging and consumption contracts

Special credit to [augard/evmaps](https://github.com/augard/evmaps): its Porsche implementation supplied the parameter dictionaries and reference material behind these contracts. Our iAP2 model pairing was verified separately; the Porsche app's model IDs are different.

Each vehicle profile contains two objects with `model_id` and `vehicle_parameters`. They are sent as compact UTF-8 JSON strings with a trailing NUL in every VehicleStatus update. The whole profile and its metadata are not sent as contract JSON.

| Profile key | Decimal model_id | Hex reference | A101 parameter |
|---|---:|---|---:|
| charging_contract | 12582922 | 0x00C0000A | 26 |
| consumption_contract | 12582921 | 0x00C00009 | 27 |

IDs are JSON integers shared across profiles, not vehicle identifiers. Preserve their roles, field spelling and types. This reference describes our implementation rather than an official Apple specification.

## Charging

| vehicle_parameters field | Shape / units | Meaning |
|---|---|---|
| vehicle_energy_axis_wh | 11 numbers, Wh | Increasing battery-energy breakpoints. |
| vehicle_charge_axis_w | 11 numbers, W | Charging power at the corresponding energy point. |
| energy_w_per_h | 21 numbers | Physical meaning and interpolation are unresolved. |
| efficiency_factor | Number, dimensionless | 0.95 in the profiles; precise application is unresolved. |

Energy and charging-power arrays pair by index. The reference energy axis spans 0–77400 Wh. The checked-in capacity variants pre-scale `vehicle_energy_axis_wh` and the separate 21-entry `energy_w_per_h` sequence; charging power is unchanged. Runtime selects one prepared variant by the BMS nominal capacity and performs no scaling. The precise meaning of `energy_w_per_h` remains unresolved, so this capacity-relative treatment is experimental.

The bundled Ioniq 5 charging object:

```json
{
  "model_id": 12582922,
  "vehicle_parameters": {
    "vehicle_energy_axis_wh": [0, 20000, 38700, 41022, 61920, 62694, 65790, 69660, 73530, 75852, 77400],
    "vehicle_charge_axis_w": [230000, 235000, 228000, 160000, 160000, 70000, 48500, 33500, 25500, 17000, 12000],
    "energy_w_per_h": [0, 3650, 7350, 11300, 15100, 18700, 22600, 26350, 30150, 34050, 37850, 41600, 45400, 49400, 53050, 57150, 60950, 64950, 68750, 72750, 77050],
    "efficiency_factor": 0.95
  }
}
```

## Consumption

| vehicle_parameters field | Shape / units | Meaning |
|---|---|---|
| vehicle_auxiliary_power_w | Number, W | Time-based auxiliary load; 400 W startup default, replaced by live or retained power. See [Live data](LIVE-DATA.md). |
| vehicle_consumption_values_wh_per_m | 10 numbers, Wh/m | Distance-based coefficients; index-selection rules are unresolved. |
| vehicle_altitude_gain_consumption_wh_per_m | Number, Wh/vertical m | Ascent coefficient, 6.42. |
| vehicle_altitude_loss_consumption_wh_per_m | Number, Wh/vertical m | Descent coefficient, 4.33; retain the positive value. Apple's precise application is unresolved. |

For scale, 0.2254 Wh/m equals 225.4 Wh/km; a 400 W load over one hour uses 400 Wh. These conversions do not specify Apple's complete routing formula. The ten consumption positions have no verified speed-bin, temperature or city/highway mapping.

The bundled Ioniq 5 consumption object, before runtime auxiliary replacement:

```json
{
  "model_id": 12582921,
  "vehicle_parameters": {
    "vehicle_auxiliary_power_w": 400.0,
    "vehicle_consumption_values_wh_per_m": [0.2254000186920166, 0.18230000734329224, 0.19320000410079957, 0.21390001773834227, 0.1721000075340271, 0.1721000075340271, 0.18310000896453857, 0.2052000045776367, 0.2266000032424927, 0.25840001106262206],
    "vehicle_altitude_gain_consumption_wh_per_m": 6.42,
    "vehicle_altitude_loss_consumption_wh_per_m": 4.33
  }
}
```

## Profiles and editing

- [Ioniq 5 profiles](../vehicles/hyundai/ioniq-5/profiles.json).
- [EV6 profiles](../vehicles/kia/ev6/profiles.json): copied numerical values are **unvalidated and likely inaccurate**.

The runtime requires the array lengths above, two connectors, a 77,400 Wh reference capacity and a unique prepared variant within 1,000 Wh of the BMS value. These are implementation constraints, not universal Apple-model rules. The builder signs one mod containing the profiles listed in [release.json](../release.json). Vehicle-specific parts select one model-family profile at Apply; runtime selects its capacity variant. Profile edits require rebuilding and signing the package.

Keep units explicit, preserve array alignment and validate changes against measured charging/trip data. Installation success establishes neither calibration accuracy nor the unresolved interpolation/efficiency/index-selection semantics. See [Vehicle profiles](PROFILE.md) and [Validation](VALIDATION.md).
