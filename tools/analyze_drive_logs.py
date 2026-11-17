#!/usr/bin/env python3
"""Compare egmp EV logs (docs/LOGGING.md) with the shipped energy model.

    python3 tools/analyze_drive_logs.py summary  FILE...   one line per file
    python3 tools/analyze_drive_logs.py charges  FILE...   every charging session vs the model
    python3 tools/analyze_drive_logs.py drive    FILE...   driving: real vs model per stretch,
                                                           and the road load that fits
    python3 tools/analyze_drive_logs.py capacity FILE...   BMS energy vs display SOC (capacity
                                                           and the 0-energy point)

drive takes --km N (stretch length, default 10), --vmin KPH (stretches slower
than this are left out of the fit, default 40) and --a/--c to try another road
load. The model side uses tools/build_ioniq5_model.py's constants (road load,
drivetrain and battery loss, aux table, charge curve), so a change there shows
up here. Climate power is the logged hvac_raw (~20 W/unit) for the fit, the
aux table for the model.

Standard library only. Logs are data: nothing in them is executed.
"""
import csv
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_ioniq5_model as M  # noqa: E402

HVAC_W_PER_UNIT = 20.0


# ---- loading ------------------------------------------------------------------

def load(path):
    """(header dict, rows): numeric columns as floats, empty as None."""
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    hdr = {}
    for line in lines:
        if line.startswith("# ") and "=" in line:
            k, v = line[2:].split("=", 1)
            hdr[k.strip()] = v.strip()
    body = [ln for ln in lines if ln and not ln.startswith("#")]
    rows = []
    for r in csv.DictReader(body):
        out = {}
        for k, v in r.items():
            if k is None:
                continue
            try:
                out[k] = float(v) if v not in ("", None) and k != "model_rev" else None
            except ValueError:
                out[k] = None
        if out.get("t_s") is not None:
            rows.append(out)
    return hdr, rows


def g(r, k):
    return r.get(k)


def temp_c(r):
    t = g(r, "out_temp_c")
    return None if t is None or t <= -40 else t


def lin(table, x):
    """Linear lookup in [(x, y)...] (any order), clamped."""
    t = sorted(table)
    if x <= t[0][0]:
        return t[0][1]
    if x >= t[-1][0]:
        return t[-1][1]
    for (x0, y0), (x1, y1) in zip(t, t[1:]):
        if x0 <= x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return t[-1][1]


def road_load_for(product):
    for prefix, a, c in M.CAR_ROAD_LOAD:
        if product and product.startswith(prefix):
            return a, c
    return M.ROAD_LOAD[0], M.ROAD_LOAD[2]


# ---- the model, battery side -----------------------------------------------------

def model_power(v, t, a, c, aux=True):
    """Battery power (W) at speed v (m/s), outside temp t (C, or None), steady."""
    a = a + (lin(M.COLD_DELTA_A, t) if t is not None else 0.0)
    wheel = a * v + c * v ** 3
    p = wheel + M.DL_SPIN(v) + M.DL_FRACTION * abs(wheel)
    p += M.BL_OHM * (p / M.BL_VOLT) ** 2
    if aux:
        p += lin(M.AUX_TABLE, t if t is not None else 15.0)
    return p


def model_charge_min(charger_w, soc1, soc2, cap_wh):
    """Minutes from soc1 to soc2 (0-1) on this charger, the 8.1 tables' physics."""
    k = cap_wh / M.CAPACITY_WH
    e1, e2 = soc1 * M.CAPACITY_WH, soc2 * M.CAPACITY_WH
    n = 400
    de = (e2 - e1) / n
    sec = sum(3600 * de / (M.charger_power(charger_w, e1 + (i + 0.5) * de)
                           * M.charge_eta(charger_w)) for i in range(n))
    return k * sec / 60


# ---- sessions and stretches ---------------------------------------------------------

def charging(r):
    return (g(r, "chg_sts_raw") or 0) >= 1 or (g(r, "fast_charging_raw") or 0) >= 1


def charge_sessions(rows):
    """[(first, last)] row indexes of parked charging runs over 2 min."""
    out, cur = [], None
    for i, r in enumerate(rows):
        if charging(r) and (g(r, "speed_kph") or 0) < 1:
            cur = [i, i] if cur is None else [cur[0], i]
        elif cur is not None:
            out.append(cur)
            cur = None
    if cur:
        out.append(cur)
    return [c for c in out if rows[c[1]]["t_s"] - rows[c[0]]["t_s"] > 120]


def stretches(rows, km):
    """Runs of rows with energy and odometer, no charging, cut every km."""
    out, cur = [], []
    for r in rows:
        if charging(r) or g(r, "energy_wh") is None or g(r, "odo_delta_km") is None:
            if len(cur) > 2:
                out.append(cur)
            cur = []
            continue
        cur.append(r)
        if g(cur[-1], "odo_delta_km") - g(cur[0], "odo_delta_km") >= km:
            out.append(cur)
            cur = [r]
    if len(cur) > 2:
        out.append(cur)
    return out


def stretch_stats(w, a, c):
    """Distance (m), time (s), energy used (Wh), sum v^3 dt, climate (Wh),
    model (Wh), altitude change (m), mean outside temp."""
    d = (g(w[-1], "odo_delta_km") - g(w[0], "odo_delta_km")) * 1000
    t = w[-1]["t_s"] - w[0]["t_s"]
    used = g(w[0], "energy_wh") - g(w[-1], "energy_wh")
    v3 = vs = hv = model = 0.0
    temps = []
    for p, r in zip(w, w[1:]):
        dt = r["t_s"] - p["t_s"]
        v = (g(r, "speed_avg_kph") or g(r, "speed_kph") or 0) / 3.6
        tc = temp_c(r)
        if tc is not None:
            temps.append(tc)
        v3 += v ** 3 * dt
        vs += v * dt
        hv += (g(r, "hvac_raw") or 0) * HVAC_W_PER_UNIT * dt / 3600
        model += model_power(v, tc, a, c) * dt / 3600
    if vs > 0:  # speeds are the display speed: scale to the odometer
        v3 *= (d / vs) ** 3
    alt = (g(w[-1], "alt_delta_m") or 0) - (g(w[0], "alt_delta_m") or 0)
    return dict(d=d, t=t, used=used, v3=v3, hvac=hv, model=model, alt=alt,
                temp=st.mean(temps) if temps else None)


def solve(x, y):
    """Least squares by the normal equations (small, well-conditioned problems)."""
    n = len(x[0])
    a = [[sum(r[i] * r[j] for r in x) for j in range(n)] for i in range(n)]
    b = [sum(r[i] * yy for r, yy in zip(x, y)) for i in range(n)]
    for i in range(n):
        p = max(range(i, n), key=lambda k: abs(a[k][i]))
        a[i], a[p], b[i], b[p] = a[p], a[i], b[p], b[i]
        for k in range(i + 1, n):
            m = a[k][i] / a[i][i]
            a[k] = [u - m * w for u, w in zip(a[k], a[i])]
            b[k] -= m * b[i]
    out = [0.0] * n
    for i in reversed(range(n)):
        out[i] = (b[i] - sum(a[i][j] * out[j] for j in range(i + 1, n))) / a[i][i]
    return out


# ---- commands --------------------------------------------------------------------

def cmd_summary(files):
    for p in sorted(files):
        h, rows = load(p)
        e = [g(r, "energy_wh") for r in rows if g(r, "energy_wh")]
        soc = [g(r, "soc_half_pct") / 2 for r in rows if g(r, "soc_half_pct")]
        km = max([g(r, "odo_delta_km") or 0 for r in rows] or [0])
        mins = rows[-1]["t_s"] / 60 if rows else 0
        temps = [temp_c(r) for r in rows if temp_c(r) is not None]
        line = "%s  %4.0f min %6.1f km" % (os.path.basename(p), mins, km)
        if e:
            line += "  energy %+6.2f kWh" % ((e[-1] - e[0]) / 1000)
        if soc:
            line += "  SOC %.1f -> %.1f%%" % (soc[0], soc[-1])
        if temps:
            line += "  %.0f..%.0f C" % (min(temps), max(temps))
        if charge_sessions(rows):
            line += "  %d charging session(s)" % len(charge_sessions(rows))
        print(line)


def cmd_charges(files):
    for p in sorted(files):
        h, rows = load(p)
        for a, b in charge_sessions(rows):
            run = rows[a:b + 1]
            first = run[0]
            e0, e1 = g(first, "energy_wh"), g(run[-1], "energy_wh")
            cap = g(first, "capacity_wh") or M.CAPACITY_WH
            if e0 is None or e1 is None:
                continue
            mins = (run[-1]["t_s"] - first["t_s"]) / 60
            dc = any((g(r, "fast_charging_raw") or 0) >= 1 for r in run)
            pack = [g(r, "pack_min_c") for r in run if g(r, "pack_min_c") is not None]
            print("%s %5.0f min: %s %.1f min, energy %.0f -> %.0f Wh (%+.1f kWh, %.1f kW "
                  "battery side), pack %s -> %s C" % (
                      os.path.basename(p), first["t_s"] / 60, "DC" if dc else "AC", mins,
                      e0, e1, (e1 - e0) / 1000, (e1 - e0) / mins * 60 / 1000,
                      pack[0] if pack else "-", pack[-1] if pack else "-"))
            # Maps' plan for this stop: the tracked charging stop in the last
            # 10 minutes before arriving
            plan = [(g(r, "nav_charger_chg_s"), g(r, "nav_charger_dep_wh"), g(r, "nav_charger_kw"))
                    for r in rows[:a] if g(r, "nav_charger_chg_s")
                    and first["t_s"] - r["t_s"] <= 600]
            if dc:
                for i in range(0, len(run) - 1, 12):
                    j = min(i + 12, len(run) - 1)
                    dt = run[j]["t_s"] - run[i]["t_s"]
                    de = (g(run[j], "energy_wh") or 0) - (g(run[i], "energy_wh") or 0)
                    print("    %4.1f min  SOC %5.1f%%  %6.1f kW  pack %s C" % (
                        (run[i]["t_s"] - first["t_s"]) / 60, (g(run[i], "soc_half_pct") or 0) / 2,
                        de / dt * 3.6 if dt else 0, g(run[i], "pack_min_c")))
            if plan and plan[-1][1]:
                chg_s, dep, kw = plan[-1]
                hit = next(((r["t_s"] - first["t_s"]) / 60 for r in run
                            if (g(r, "energy_wh") or 0) >= dep), None)
                charger = int((kw or 150) * 1000)
                print("    Maps planned %.1f min on %s kW to %.0f Wh; reached after %s; model %.1f min" % (
                    chg_s / 60, kw, dep, "%.1f min" % hit if hit is not None else "never",
                    model_charge_min(charger, e0 / cap, dep / cap, cap)))
            for charger in ((350000, 150000) if dc else (10900,)):
                print("    model %5.1f kW: %.1f min for the same energy" % (
                    charger / 1000, model_charge_min(charger, e0 / cap, e1 / cap, cap)))


def cmd_drive(files, km, vmin, a_try, c_try):
    data = []
    for p in sorted(files):
        h, rows = load(p)
        a0, c0 = road_load_for(h.get("product", ""))
        a, c = (a_try if a_try is not None else a0), (c_try if c_try is not None else c0)
        for w in stretches(rows, km):
            s = stretch_stats(w, a, c)
            if s["d"] < km * 600 or s["t"] <= 0 or s["used"] <= 0:
                continue
            kph = s["d"] / s["t"] * 3.6
            print("%s t%5.0f %5.1f km %5.1f km/h alt %+5.0f m %s real %4.0f Wh/km model %4.0f (%+4.0f%%)" % (
                os.path.basename(p), w[0]["t_s"] / 60, s["d"] / 1000, kph, s["alt"],
                "%5.1f C" % s["temp"] if s["temp"] is not None else "   - C",
                s["used"] / s["d"] * 1000, s["model"] / s["d"] * 1000,
                (s["model"] / s["used"] - 1) * 100))
            if kph >= vmin:
                data.append(s)
    if not data:
        print("no stretches")
        return
    used = sum(s["used"] for s in data)
    model = sum(s["model"] for s in data)
    print("\n%d stretches at >= %.0f km/h: model / real energy = %.3f" % (len(data), vmin, model / used))
    # Road load fit: A with C fixed (highway data can't separate them), the
    # model's drivetrain loss and spin, measured climate, plus a climb term.
    print("road load fit (climate measured, model losses):")
    for c in (0.493, 0.45, 0.40, 0.35):
        x = [[s["d"] * (1 + M.DL_FRACTION), s["alt"]] for s in data]
        y = [s["used"] * 3600 - c * s["v3"] * (1 + M.DL_FRACTION)
             - M.DL_SPIN(s["d"] / s["t"]) * s["t"] - s["hvac"] * 3600 for s in data]
        a, k = solve(x, y)
        res = []
        for s, xx, yy in zip(data, x, y):
            res.append((yy - a * xx[0] - k * xx[1]) / s["d"] * 1000 / 3600)
        print("  C %.3f: A %4.0f N, climb %.2f Wh/m, residual sd %.1f Wh/km" % (
            c, a, k / 3600, st.pstdev(res)))


def cmd_capacity(files):
    by = {}
    for p in files:
        h, rows = load(p)
        name = h.get("product", "?").split(".")[0]
        pts = by.setdefault(name, {})
        for r in rows:
            s, e = g(r, "soc_half_pct"), g(r, "avlbl_enrg_raw")
            if s and e and 0 < e < 45000 and (g(r, "speed_kph") or 0) == 0 and not charging(r):
                pts.setdefault(s / 2, []).append(e * 2)
    for name, pts in sorted(by.items()):
        if len(pts) < 3:
            print("%s: too few parked SOC levels" % name)
            continue
        socs = sorted(pts)
        med = [sorted(pts[s])[len(pts[s]) // 2] for s in socs]
        slope, icpt = solve([[s, 1.0] for s in socs], med)
        print("%s: %d SOC levels %.1f..%.1f%%: energy = %.0f Wh/%% x (SOC - %.2f%%); "
              "%.0f Wh at 100%% (the model assumes 0 at %.1f%%)" % (
                  name, len(socs), socs[0], socs[-1], slope, -icpt / slope,
                  slope * 100 + icpt, M.ENERGY_ZERO_PCT))


def main(argv):
    if len(argv) < 3 or argv[1] not in ("summary", "charges", "drive", "capacity"):
        print(__doc__)
        return 2
    cmd, rest = argv[1], argv[2:]
    opts = {"--km": 10.0, "--vmin": 40.0, "--a": None, "--c": None}
    files = []
    i = 0
    while i < len(rest):
        if rest[i] in opts:
            opts[rest[i]] = float(rest[i + 1])
            i += 2
        else:
            files.append(rest[i])
            i += 1
    if cmd == "summary":
        cmd_summary(files)
    elif cmd == "charges":
        cmd_charges(files)
    elif cmd == "drive":
        cmd_drive(files, opts["--km"], opts["--vmin"], opts["--a"], opts["--c"])
    else:
        cmd_capacity(files)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
