# Vehicle profiles

One signed mod ZIP contains the supported vehicle profiles as `profiles/neev23.json`, `profiles/neev.json`, `profiles/cvev.json` and `profiles/ceev.json`. Each UTF-8 JSON file contains vehicle metadata, battery capacity, connectors and both [contracts](CONTRACTS.md).

HMG-API’s vehicle-specific `parts` declarations select one profile when **Apply changes** runs, and add that file at `/system/etc/carplay.ev-routing/profile.json`. This is an HMG-API-managed file: removal restores its prior state. The routing code reads only this fixed path during startup; it does not select a model during status updates. The signed bundle retains both source profiles so the same ZIP works on both vehicles.

`vehicle.display_name` is used directly. `default_display_name` is reserved for a future reset feature; renaming is not implemented. Model IDs are decimal JSON integers identifying the charging and consumption models, not the car.

The checked-in `profiles.json` files contain pre-generated capacity variants. Ioniq 5 includes 58, 72.6, 77.4 and 84 kWh; Ioniq 6 includes 53 and 77.4 kWh; EV6 includes 58, 77.4 and 84 kWh. Each non-reference variant was generated once from its vehicle's 77.4 kWh reference by scaling both Wh-domain charging arrays by `capacity / 77,400`; charging-power and consumption values remain unchanged. Runtime reads `BMS_EVENERGYCAPA` and selects the nearest variant within ±1,000 Wh—it does not scale JSON on the car. EV6 and Ioniq 6 energy models are unvalidated and potentially inaccurate.

Runtime checks schema, name, model roles, connectors, array lengths and the selected capacity profile. A missing, sentinel, tied or more-than-1,000 Wh-distant BMS capacity is retryable and does not become zero; EV status remains unsent until a variant resolves. Auxiliary power starts from the profile default, then uses live or retained values in transmitted consumption JSON. The installed profile is never rewritten. Profile changes require rebuilding and signing the package; automatic downloads are not implemented.

The **Battery capacity** mod setting can override automatic BMS selection with an exact bundled capacity. Its default is **Automatic (BMS)**. The override never scales a profile at runtime and must exist in the installed vehicle profile; 53 kWh is available only for Ioniq 6 and 72.6 kWh only for Ioniq 5. An unsupported override prevents EV status from starting and is reported explicitly in the log. Apply changes and fully reboot after changing the setting.
