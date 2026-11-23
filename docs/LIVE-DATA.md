# Live vehicle data

While subscribed, read SOC/range from HSCAN and auxiliary power from the HEV service every ten seconds. Auxiliary watts = 20 × climate + 10 × electronics + 20 × battery compressor + 100 × battery heater. Reject invalid/sentinel readings instead of treating them as zero. Source CAN-cache freshness is not independently established.

Nominal capacity comes from the HEV service's `BMS_EVENERGYCAPA` value (transaction 3, flag `0x35`, raw value × 2 Wh). Runtime uses the exact result to select a pre-generated capacity profile: Ioniq 5 accepts 58,000, 72,600, 77,400 or 84,000 Wh; EV6 accepts 58,000, 77,400 or 84,000 Wh. Until a listed capacity is available, no EV status is sent and the worker retries every ten seconds. Once resolved, the selection is retained for the session and logged as `max_wh=... battery_source=bms_evenergycapa_profile_<Wh>`.

HSCAN failures omit live battery/range fields; auxiliary reads remain independent. A successful SOC/range sample expires after 15 seconds within a polling operation. Current Wh and full-charge range are estimates from SOC and the BMS-reported nominal capacity.

Every status includes both JSON contracts. Valid auxiliary samples are averaged in one-minute windows and the result is rounded to the nearest 25 W. The sent consumption contract holds the profile default through the first window, then changes auxiliary power only at minute boundaries and retains the previous average if a window has no valid samples. The installed profile is not rewritten. The ten-second status interval, one-minute auxiliary policy and repeated-contract policy are implementation choices, not established Apple requirements.
