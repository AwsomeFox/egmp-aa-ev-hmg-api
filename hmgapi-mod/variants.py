#!/usr/bin/env python3
"""The mod's two builds, from hmgapi-mod/mod.json and settings.json:

  egmp-aa-ev             as written: requires the Wireless CarPlay + Android Auto mod, whose
                         Android Auto library patch hands over Maps' route data, which
                         battery preconditioning needs.
  egmp-aa-ev-standalone  no Wireless mod: everything but preconditioning (energy model,
                         battery % curve, EV plugs, live battery and range, the EV log), for
                         wired Android Auto or the car's own wireless Android Auto. No
                         "requires", no conditioning command, no relay service, no
                         preconditioning settings (the code then keeps preconditioning off:
                         HmgApiEntry.precondOn). Each conflicts with the other.

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
    mod["description"] = ("Without the Wireless mod: wired or the car's own wireless Android Auto. "
                          "Google Maps EV routing: battery and range, charge curves, EV plugs. "
                          "No battery preconditioning.")
    mod.pop("requires", None)
    mod["conflicts"] = ["egmp-aa-ev"]
    mod["nemesis_conflicts"] = ["hmgapi.egmp-aa-ev"]
    mod.pop("vehicle", None)                       # only preconditioning sends commands
    mod["services"] = [s for s in mod.get("services", []) if s.get("name") != "relay"]
    # HMG-API prefixes the mod's own log lines with its id
    mod["logs"] = dict(mod["logs"], tags=[t for t in mod["logs"]["tags"] if t != "EvPrecond"],
                       match=["[%s]" % STANDALONE])
    keep = [s for s in settings["settings"] if s["key"] != "preconditioning" and not s["key"].startswith("precond_")]
    return mod, dict(settings, settings=keep)


def main():
    kind, out = sys.argv[1], sys.argv[2]
    mod = json.load(open(os.path.join(HERE, "mod.json")))
    settings = json.load(open(os.path.join(HERE, "settings.json")))
    if kind == "standalone":
        mod, settings = standalone(mod, settings)
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
