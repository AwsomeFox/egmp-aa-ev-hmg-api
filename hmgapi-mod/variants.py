#!/usr/bin/env python3
"""The mod's two builds, from hmgapi-mod/mod.json and settings.json:

  egmp-aa-ev             as written: requires the Wireless CarPlay + Android Auto mod, whose
                         Android Auto library patch hands over Maps' route data, which
                         battery preconditioning needs.
  egmp-aa-ev-standalone  no Wireless mod: wired Android Auto or the car's own wireless
                         Android Auto. Everything, preconditioning included where it has the
                         route data: it carries the Wireless mod's turn-by-turn library
                         recipes itself (hmgapi-mod/route/, credited there), each merged with
                         the energy model's recipe for the same library build, since HMG-API
                         applies one recipe per library for a mod. No "requires". Each
                         conflicts with the other.

  variants.py <full|standalone> <out dir>    writes mod.json and settings.json there
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
STANDALONE = "egmp-aa-ev-standalone"


def standalone(mod, settings):
    mod = dict(mod)
    mod["id"] = STANDALONE
    mod["name"] = "EV routing for Android Auto (E-GMP), standalone"
    # (NEMESIS shows at most 256 characters, with "<name> <version>, a HMG-API mod (needs HMG-API). " first)
    mod["description"] = ("Wired or the car's own wireless Android Auto. Google Maps EV routing: battery, "
                          "charge curves, EV plugs, DC preconditioning. Built for E-GMP; other EVs learn as they go.")
    mod.pop("requires", None)
    mod["conflicts"] = ["egmp-aa-ev"]
    mod["nemesis_conflicts"] = ["hmgapi.egmp-aa-ev", "aa.ev.model"]
    # HMG-API prefixes the mod's own log lines with its id
    mod["logs"] = dict(mod["logs"], match=["[%s]" % STANDALONE])
    keep = []
    for s in settings["settings"]:
        if s["key"] == "preconditioning":
            old = "Needs the route data the Wireless CarPlay + Android Auto mod's turn-by-turn adds."
            assert old in s["description"], "settings.json preconditioning text changed: update variants.py"
            s = dict(s, description=s["description"].replace(old,
                "Needs Maps' route data, which this build's Android Auto library patch adds on the "
                "library builds it has one for (US 260507, the Canadian Ioniq 5's 260206, the Ioniq 6's)."))
        keep.append(s)
    return mod, dict(settings, settings=keep)


ROUTE = os.path.join(HERE, "route")
RECIPES = os.path.join(HERE, "recipes")
LIB = "libautoreceiver_infobank.so"


def body(text):
    """a recipe's lines but its id and file lines"""
    return [l for l in text.splitlines() if l.split()[:1] not in (["id"], ["file"])]


def route_recipes(mod, out):
    """
    The standalone build's library recipes: for each Wireless turn-by-turn recipe
    (route/aa-tbt-<md5>), one recipe with the energy model's patches for that build too
    (ev-sensor-route-<md5>), or the route data alone (route-<md5>). They go first in
    hooks.native, before the energy model's own recipes (which still apply where the
    route data's don't fit); HMG-API applies the first that fits.
    """
    os.makedirs(os.path.join(out, "recipes"), exist_ok=True)
    names = []
    for f in sorted(os.listdir(ROUTE)):
        if not (f.startswith("aa-tbt-") and f.endswith(".recipe")):
            continue
        md5 = f[len("aa-tbt-"):-len(".recipe")]
        tbt = open(os.path.join(ROUTE, f)).read()
        ev = os.path.join(RECIPES, "ev-sensor-%s.recipe" % md5)
        if os.path.exists(ev):
            rid = "ev-sensor-route-" + md5
            parts = ["# egmp-aa-ev-standalone: ev-sensor-%s (the energy model) and the Wireless mod's" % md5,
                     "# aa-tbt-%s (Maps' route data, copied unchanged: hmgapi-mod/route/) in one recipe," % md5,
                     "# since HMG-API applies one recipe per library for a mod.",
                     "id " + rid, "file /system/lib/" + LIB] + body(open(ev).read()) + body(tbt)
        else:
            rid = "route-" + md5
            parts = ["# egmp-aa-ev-standalone: the Wireless mod's aa-tbt-%s (Maps' route data, copied" % md5,
                     "# unchanged: hmgapi-mod/route/); no energy model recipe for this build yet.",
                     "id " + rid, "file /system/lib/" + LIB] + body(tbt)
        with open(os.path.join(out, "recipes", rid + ".recipe"), "w") as w:
            w.write("\n".join(parts) + "\n")
        names.append("recipes/%s.recipe" % rid)
    nat = mod["hooks"]["native"]
    nat[LIB] = names + nat[LIB]
    return mod


def main():
    kind, out = sys.argv[1], sys.argv[2]
    mod = json.load(open(os.path.join(HERE, "mod.json")))
    settings = json.load(open(os.path.join(HERE, "settings.json")))
    if kind == "standalone":
        mod, settings = standalone(mod, settings)
        mod = route_recipes(mod, out)
    elif kind != "full":
        sys.exit("variants.py: full or standalone, not %r" % kind)
    os.makedirs(out, exist_ok=True)
    for name, d in (("mod.json", mod), ("settings.json", settings)):
        with open(os.path.join(out, name), "w") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print(mod["id"])


if __name__ == "__main__":
    main()
