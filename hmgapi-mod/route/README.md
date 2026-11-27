# Route data recipes (standalone build)

`aa-tbt-<md5>.recipe` are the turn-by-turn library recipes of the **Wireless CarPlay +
Android Auto mod** (2.x) and of **Phone Turn-by-Turn** (`projection-tbt`), where they
moved in Wireless 3.0. Copied unchanged from
[AwsomeFox/hmg-api-turn-by-turn](https://github.com/AwsomeFox/hmg-api-turn-by-turn)
`projection-tbt/recipes/` (1.0.0-beta.1, commit bcb7e81; the same files as
[Open-HMG/hmgapi-wireless-cp-aa](https://github.com/Open-HMG/hmgapi-wireless-cp-aa)
main at 9ee603d, "Android Auto road names keep their last character": the parser's
string cut fixed), AGPL-3.0 like this project:

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
energy model recipe. To update: copy the newer files here from a projection-tbt release.

The standalone build stays off beside a Wireless mod before 3.0 (which carries these
recipes itself; use the full build there). Beside projection-tbt it runs, and asks for
its library patch after projection-tbt's: HMG-API refuses the second recipe on the same
bytes and takes the build's next one that fits, the energy model's alone, so the route
data comes from projection-tbt (`HmgApiEntry.yieldRoute`). On a library build with no
energy model recipe (the Canadian Ioniq 5's 260206) the log then says the patch wasn't
applied; projection-tbt's is in place.
