# NEMESIS packaging

`nemesis-release.json` configures the official HMG-API v1 wrapper preparation: the pinned published native ZIP, dependencies and legacy-wrapper conflict. Run `tools/prepare-nemesis.py` against the reviewed HMG-API v1.0.0 source. The generated store and USB packages are under `dist/nemesis/`.

All lifecycle scripts come from upstream unchanged and use `--from-nemesis` to avoid nested package-list synchronization. Only dependency metadata is extended to require wireless projection and refuse the old wrapper. No extra install/rollback service is added.

`nemesis/` contains the earlier beta6 experimental wrapper, retained for its existing tests/recovery reference. It is obsolete for the v1 rollout and must not be uploaded. Use the [rollout plan](../docs/NEMESIS-RELEASE.md).
