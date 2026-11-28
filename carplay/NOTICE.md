# Attribution and external dependencies

- [augard/evmaps](https://github.com/augard/evmaps): charging/consumption parameter dictionaries and reference material. The model pairing and Gen5W integration were validated separately; see docs/CONTRACTS.md.
- [HMG-API](https://github.com/VysseCodes/HMG-API): required mod/hook runtime, native recipe engine and signing tools, AGPL-3.0. HMG-API implementation classes and executables are not bundled. Compile-only API stubs adapted from its references retain their upstream licensing; they are excluded from the shipped JAR.
- [NEMESIS](https://github.com/xwtk/hmg_nemesis), by xwtk: required package manager, AGPL-3.0; not bundled or modified. USB diagnostic conventions were informed by HMG Leafia Plus.
- [egmp-aa-ev-feat](https://github.com/willemcvu/egmp-aa-ev-feat): vehicle-data research reference.
- OpenJDK, Clang/LLD, R8/D8, Android API classes, JSON-java and pyelftools: external build/test tools, not bundled in release packages.

Original project code is GPL-3.0-or-later. Dependency copyrights/licenses remain applicable; see LICENSE. Manufacturer applications, libraries and firmware are private runtime/test inputs, never distributed here. No signing private keys or raw HU captures are included.

Official NEMESIS wrappers include unmodified HMG-API lifecycle scripts under AGPL-3.0, with the upstream license and a source link. The embedded EV-routing mod retains its own license and signing identity.
