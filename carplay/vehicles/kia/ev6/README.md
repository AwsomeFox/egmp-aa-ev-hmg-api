# Kia EV6 capacity profiles

`profiles.json` contains prepared 58, 77.4 and 84 kWh variants. Their consumption and charging-power values are copied from the Ioniq 5 and remain unvalidated for EV6; only the Wh-domain charging sequences differ by capacity.

Runtime selects an exact variant from `BMS_EVENERGYCAPA`. Installation or profile selection does not establish calibration accuracy or native compatibility; the resolving HMG-API map and exact native-library hashes remain separate requirements.
