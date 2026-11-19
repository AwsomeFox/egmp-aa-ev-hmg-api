# Build

Use a JDK, Clang + LLD, Python with pyelftools, HMG-API 1.1.0-rc.3 source, Wireless CarPlay + Android Auto 2.2.0-rc.4 source, an Android compile API JAR, R8/D8 and JSON-java (host tests). No OEM binaries are needed to compile/package the mod. Private stock-library inputs are optional for offline ARM tests and must never be distributed.

```sh
python3 tools/build-release.py --hmgapi-source /path/to/HMG-API --wireless-source /path/to/hmgapi-wireless-carplay-aa --android-jar /path/to/android.jar --d8-jar /path/to/r8.jar --json-jar /path/to/json.jar --key /private/path/author.key --java-home /path/to/jdk --lld /path/to/ld.lld
```

Use the established author key: the builder verifies its expected UUID and refuses another key. Keep it outside this repository. The Wireless source supplies the exact exported API used at runtime; EV Routing does not carry a duplicate API stub. Compile-only HMG/Wireless/OEM classes are excluded from the mod JAR. One signed native mod contains all supported profiles. HMG-API’s vehicle-specific parts select and install one active profile at Apply; runtime reads that fixed file.

Outputs in gitignored `dist/`: one signed native mod ZIP, source ZIP and SHA256SUMS. Optional NEMESIS test wrappers are in `dist/testing/`. `out/build-report.json` records tools, profiles and artifact hashes. The native ZIPs are the primary installation route. They contain `mod.json` at the ZIP root, the unique code JAR, helper library, recipe, bundled profiles, collector, notices and `META-HMGAPI` signature files. This matches the format accepted by upstream `mkmodpkg.sh` and the package engine.

`python3 tests/test-release.py` runs package/lifecycle checks after a build. The build also checks official HMG-API hook plans, payload signatures and HU shell-command availability. `tests/NemesisMetadataHost.c` can be linked with NEMESIS `ini.c` and `meta.c` to check the descriptor with its actual parser. Public source roots are src/, stubs/, recipes/, svc/, vehicles/, packaging/, tools/, tests/, docs/ and root metadata/notices. Generated dependencies, private keys, captures and old release artifacts are excluded.

To rerun the guide’s shell check with upstream tools, run `tools/check-scripts.sh` from the HMG-API checkout, passing absolute paths to our built `out/mod` and `packaging/nemesis`. The builder runs the same ScriptCheck implementation automatically. `python3 tests/test-hmgapi-package.py --java /path/to/java --wireless-source /path/to/hmgapi-wireless-carplay-aa` exercises the finished payloads with the real HMG-API host package engine in temporary directories.

For a future NEMESIS store rollout, use `tools/prepare-nemesis.py` to wrap a published mod without rebuilding or signing it again. See [NEMESIS release preparation](NEMESIS-RELEASE.md). The legacy wrapper outputs from `build-release.py` are test artifacts and must not be published.

Validate the prepared package using `python3 tests/test-nemesis-package.py --engine-classes /path/to/v1/engine-classes --java /path/to/java`. `tests/NemesisStoreMetadataHost.c`, linked with current NEMESIS `ini.c`/`meta.c`, checks the actual descriptor parser and dependencies.
