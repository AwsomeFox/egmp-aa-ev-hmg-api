# Route data recipes (standalone build)

`aa-tbt-<md5>.recipe` are the **Wireless CarPlay + Android Auto mod's** turn-by-turn
library recipes, copied unchanged from
[Open-HMG/hmgapi-wireless-cp-aa](https://github.com/Open-HMG/hmgapi-wireless-cp-aa)
v2.2.0-rc.5 (`wireless-carplay-aa/recipes/`, commit 333e7b5), AGPL-3.0 like this project:

| Recipe | Library build | By |
|---|---|---|
| `aa-tbt-48ee793a` | US 260507 (Ioniq 5, EV6, Ioniq 6) | VysseCodes, from NEMESIS's `projection.tbt` package |
| `aa-tbt-a0bea1c4` | Canada 260206 (Ioniq 5) | VysseCodes |
| `aa-tbt-dbfe7bd2` | Ioniq 6 US and Canada (CEEV) | Weab-chan |

They make the Android Auto library hand Maps' route messages (destination, trip
distance and time, turns ahead) to GPM's navigation callbacks; event 33 carries the
raw route, which `Precond` reads for the next charging stop. The full build gets this
from the Wireless mod itself. The standalone build has no Wireless mod, so
`hmgapi-mod/variants.py` puts each of these into the standalone build's library recipe
for that build: `ev-sensor-route-<md5>` (the energy model's patches and these, since
HMG-API applies one recipe per library for a mod) or `route-<md5>` where there's no
energy model recipe. To update: copy the newer files here from a Wireless release.
