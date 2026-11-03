#!/usr/bin/env python3
"""Write the HMG-API memory recipe for egmp's three Android Auto library patches.

usage: mkrecipe.py <libautoreceiver_infobank.so> <out.recipe>
           [--id ID --sites ISVALID,DEFAULT,REPORT --queue QUEUEOUTGOING] [--avoid RECIPE...]

Without options: the US 260507 build. The input is any build of the library
that still has the stock bytes at the sites below: the stock file (md5
48ee793af8b86052503b8c952b034731) or a Leafia / NEMESIS projection.tbt build of
it (c08410a8...), which never touch these sites.

Another library build (another firmware or region): find the same three
instructions and queueOutgoing in it (docs/RESEARCH.md 5d says how they were
found; a disassembler such as capstone), then
  mkrecipe.py <that build> recipes/ev-sensor-<its md5, 8 chars>.recipe \
      --id ev-sensor-<8 chars> --sites 0x...,0x...,0x... --queue 0x... \
      --avoid <the Wireless mod's recipe for that build>
and add the file to mod.json hooks.native. The stock bytes at each site must be
the same instructions (this refuses otherwise); verify windows are the 16 bytes
around each site, and must not overlap the --avoid recipes' regions (HMG-API
refuses overlapping recipes on one library). NativeCheck reads the new recipe
itself: nothing else changes. The recipe holds only hashes of the verify regions and the
bytes we write (no manufacturer code), in hmgapi.engine.MemRecipe's format:

  verify <off> <len> <md5 before> <md5 after>
  patch <off> <hex>

The patches are tools/patch_library.py's (docs/RESEARCH.md section 5d):
  1. SensorType_IsValid (0x144ec2): cmp r0,#20 -> cmp r0,#22 (type 23 parses)
  2. handleSensorRequest default (0xbb29e): mvn.w r7,#8 -> mov.w r7,#0
  3. reportDiagnosticsData (0xbb638): push.w -> b.w queueOutgoing (0xb1972)

Verify windows are kept clear of the Wireless CarPlay/AA mod's turn-by-turn
recipe (aa-tbt-48ee793a: 0xb1a1e, 0xb6808, 0x144eea), because HMG-API refuses
a recipe whose regions overlap another mod's on the same library.
"""
import argparse
import hashlib
import re
import struct
import sys

QUEUE_OUTGOING = 0xB1972


def encode_b_w(from_addr, to_addr):
    """Thumb-2 B.W (T4) at from_addr to to_addr (same as patch_library.py)."""
    offset = to_addr - (from_addr + 4)
    assert offset % 2 == 0 and -(1 << 24) <= offset < (1 << 24), hex(offset)
    x = (offset >> 1) & 0xFFFFFF
    s = (x >> 23) & 1
    i1, i2 = (x >> 22) & 1, (x >> 21) & 1
    j1, j2 = (i1 ^ s) ^ 1, (i2 ^ s) ^ 1
    hw1 = 0xF000 | (s << 10) | ((x >> 11) & 0x3FF)
    hw2 = 0x9000 | (j1 << 13) | (j2 << 11) | (x & 0x7FF)
    return struct.pack("<HH", hw1, hw2)


# (patch offset, stock bytes, new bytes, verify window start, window length, what)
SITES = [
    (0x144EC2, bytes.fromhex("1428"), bytes.fromhex("1628"), 0x144EB8, 16,
     "SensorType_IsValid: accept sensor types 1..23"),
    (0xBB29E, bytes.fromhex("6ff00807"), bytes.fromhex("4ff00007"), 0xBB296, 16,
     "handleSensorRequest default: reply SUCCESS, not -9"),
    (0xBB638, bytes.fromhex("2de9f041"), encode_b_w(0xBB638, QUEUE_OUTGOING), 0xBB630, 16,
     "reportDiagnosticsData: tail-branch into queueOutgoing"),
]
# the branch target must be queueOutgoing: checked, never written
TARGET = (QUEUE_OUTGOING, 24, "ProtocolEndpointBase::queueOutgoing (branch target, unchanged)")
# the Wireless mod's verify regions on the same library build (must not overlap)
OTHERS = [(0xB1A1E, 33), (0xB6808, 36), (0x144EEA, 33)]


def md5(b):
    return hashlib.md5(b).hexdigest()


def sites_for(isvalid, default, report, queue):
    """The three patches at another build's offsets (16-byte verify windows around each)."""
    return [
        (isvalid, SITES[0][1], SITES[0][2], isvalid - 8, 16, SITES[0][5]),
        (default, SITES[1][1], SITES[1][2], default - 8, 16, SITES[1][5]),
        (report, SITES[2][1], encode_b_w(report, queue), report - 8, 16,
         "reportDiagnosticsData: tail-branch into queueOutgoing"),
    ]


def regions_of(path):
    """A recipe's verify regions (offset, length)."""
    out = []
    for line in open(path):
        m = re.match(r"\s*verify\s+(\S+)\s+(\S+)", line)
        if m:
            out.append((int(m.group(1), 0), int(m.group(2), 0)))
    return out


def main():
    ap = argparse.ArgumentParser(description="egmp-aa-ev's library recipe")
    ap.add_argument("lib")
    ap.add_argument("out")
    ap.add_argument("--id", default="ev-sensor-48ee793a")
    ap.add_argument("--sites", help="IsValid cmp, handleSensorRequest default, reportDiagnosticsData push (file offsets)")
    ap.add_argument("--queue", help="queueOutgoing (file offset)")
    ap.add_argument("--avoid", nargs="*", default=None, help="other recipes on this build whose regions must stay clear")
    a = ap.parse_args()
    if bool(a.sites) != bool(a.queue):
        sys.exit("--sites and --queue go together")
    sites, target, others = SITES, TARGET, OTHERS
    if a.sites:
        offs = [int(x, 0) for x in a.sites.split(",")]
        if len(offs) != 3:
            sys.exit("--sites: three offsets")
        q = int(a.queue, 0)
        sites = sites_for(offs[0], offs[1], offs[2], q)
        target = (q, 24, TARGET[2])
        others = []
        if a.id == "ev-sensor-48ee793a":
            sys.exit("--id: name the recipe after this build (ev-sensor-<md5, 8 chars>)")
    if a.avoid is not None:
        others = [r for f in a.avoid for r in regions_of(f)]
    src = open(a.lib, "rb").read()
    after = bytearray(src)
    for off, old, new, _w, _n, what in sites:
        cur = src[off:off + len(old)]
        if cur != old:
            sys.exit("%s: bytes at %#x are %s, expected %s (not a build these patches are for)"
                     % (what, off, cur.hex(), old.hex()))
        after[off:off + len(new)] = new
    windows = [(w, n) for _o, _a, _b, w, n, _d in sites] + [target[:2]]
    for w, n in windows:
        for o, m in others:
            if not (w + n <= o or o + m <= w):
                sys.exit("verify window %#x overlaps another recipe's region %#x" % (w, o))
    lines = [
        "# Android Auto library: egmp-aa-ev's EV energy model path (sensor type 23 accepted,",
        "# answered SUCCESS, raw messages through reportDiagnosticsData). Made from a build with",
        "# md5 %s; verify regions mismatch on any other build." % md5(src),
        "# generated by hmgapi-mod/tools/mkrecipe.py (memory only: hmgapi.engine.MemRecipe)",
        "id %s" % a.id,
        "file /system/lib/libautoreceiver_infobank.so",
    ]
    for off, old, new, w, n, what in sites:
        lines.append("# %s" % what)
        lines.append("verify %#x %d %s %s" % (w, n, md5(src[w:w + n]), md5(bytes(after[w:w + n]))))
    t, n, what = target
    lines.append("# %s" % what)
    lines.append("verify %#x %d %s %s" % (t, n, md5(src[t:t + n]), md5(src[t:t + n])))
    for off, old, new, _w, _n, _d in sites:
        lines.append("patch %#x %s" % (off, new.hex()))
    open(a.out, "w").write("\n".join(lines) + "\n")
    print("wrote %s from %s (md5 %s)" % (a.out, a.lib, md5(src)))


if __name__ == "__main__":
    main()
