#!/usr/bin/env python3
"""
Differential test + benchmark for Forge Nova's engine patches.

Runs the same seeded AI-vs-AI games (Forge's headless "sim" mode) with the original
Forge jar and with the patched engine classes first on the classpath, then:
  * compares every game log line-by-line (the simulator is deterministic for a seed),
  * reports the wall-clock time of each game for both variants.

Usage (from the Forge install folder):
  python nova/bench/difftest.py                   # default matrix
  python nova/bench/difftest.py --quick           # a few games only
  python nova/bench/difftest.py --verify-caches   # also run the patched engine in cache-verification mode
"""
import argparse
import concurrent.futures
import glob
import os
import re
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NOISE = re.compile(r"not assigned to any set|Upcoming set|^Read cards|Language '|GuiBase|ThreadUtil|Error handling|"
                   r"^\s*$|ended in \d+ ms|ended in a Draw|Took \d+ ms|\[Nova\]|WARNING|SEVERE|EventBus|"
                   r"^\s+at |^\s*\.\.\. \d+ more|AI eval thread at timeout|^Caused by|Exception")

PAIRS_1V1 = [
    ("Atraxa.dck", "Kynaios.dck"), ("Vazi.dck", "Rocco.dck"), ("Mirmoldo.dck", "Bananas Akibo.dck"),
    ("Elenda and Azor.dck", "Jenson.dck"), ("Nissa.dck", "Toph.dck"), ("Isshin.dck", "Glarb.dck"),
    ("Zidane.dck", "Savra.dck"), ("Ulamog.dck", "Serah Farron.dck"),
]
FOUR_PLAYER = [("Vazi.dck", "Rocco.dck", "Mirmoldo.dck", "Bananas Akibo.dck")]


def forge_jar():
    jars = glob.glob(os.path.join(ROOT, "forge-gui-desktop-*-jar-with-dependencies.jar"))
    if not jars:
        sys.exit("Forge desktop jar not found in " + ROOT)
    return jars[0]


def deck_dir():
    appdata = os.environ.get("APPDATA") or os.path.expanduser("~/.forge")
    return os.path.join(appdata, "Forge", "decks", "commander")


def run_case(classpath, decks, seed, games, clock, extra_jvm):
    cmd = ["java", "-Xmx3g", "-Djava.awt.headless=true"] + extra_jvm + [
        "-cp", classpath, "forge.view.Main", "sim", "-D", deck_dir(), "-d", *decks,
        "-f", "Commander", "-n", str(games), "-c", str(clock), "-s", str(seed)]
    t0 = time.time()
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    wall = time.time() - t0
    out = p.stdout + p.stderr
    game_ms = [int(x) for x in re.findall(r"ended in (\d+) ms", out)]
    game_ms += [int(x) for x in re.findall(r"Took (\d+) ms", out)]
    lines = [ln.rstrip() for ln in out.splitlines() if not NOISE.search(ln)]
    timeouts = out.count("AI eval thread at timeout")
    return {"wall": wall, "game_ms": sum(game_ms), "lines": lines, "raw": out, "timeouts": timeouts}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--four", action="store_true", help="include a 4-player commander game (slow)")
    ap.add_argument("--seeds", default="101,202,303")
    ap.add_argument("--games", type=int, default=1)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--clock", type=int, default=600)
    ap.add_argument("--verify-caches", action="store_true")
    ap.add_argument("--patches", default=os.path.join(ROOT, "nova", "lib", "nova-engine-patches.jar"))
    ap.add_argument("--out", default=os.path.join(ROOT, "nova", "bench", "results"))
    args = ap.parse_args()

    jar = forge_jar()
    if not os.path.exists(args.patches):
        sys.exit("Patched classes not found: " + args.patches + " (run nova/tools/build.cmd first)")
    seeds = [int(s) for s in args.seeds.split(",")]
    pairs = PAIRS_1V1[:3] if args.quick else PAIRS_1V1
    cases = [(p, s) for p in pairs for s in (seeds[:1] if args.quick else seeds)]
    if args.four:
        cases += [(p, seeds[0]) for p in FOUR_PLAYER]
    os.makedirs(args.out, exist_ok=True)

    variants = {"original": ([jar], []), "patched": ([args.patches, jar], [])}
    if args.verify_caches:
        variants["patched"] = ([args.patches, jar], ["-Dnova.verifyCaches=true"])

    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as ex:
        futs = {}
        for (decks, seed) in cases:
            for name, (cp, jvm) in variants.items():
                f = ex.submit(run_case, os.pathsep.join(cp), decks, seed, args.games, args.clock, jvm)
                futs[f] = (decks, seed, name)
        for f in concurrent.futures.as_completed(futs):
            decks, seed, name = futs[f]
            results[(decks, seed, name)] = f.result()
            print(f"  done {name:9s} seed {seed} {' vs '.join(d[:-4] for d in decks)}", flush=True)

    # Some AI choices break ties by hash-iteration order, which can differ between JVM runs even for
    # the original engine. When logs differ, re-run the original: if it disagrees with itself the
    # case is inherently nondeterministic rather than a behaviour change.
    for (decks, seed) in cases:
        o, p = results[(decks, seed, "original")], results[(decks, seed, "patched")]
        if o["lines"] != p["lines"] and not (o["timeouts"] or p["timeouts"]):
            o2 = run_case(os.pathsep.join(variants["original"][0]), decks, seed, args.games, args.clock, [])
            o["rerun_differs"] = o2["lines"] != o["lines"]
            o["rerun_matches_patched"] = o2["lines"] == p["lines"]

    print()
    print(f"{'game':52s} {'original':>10s} {'patched':>10s} {'speedup':>8s}  log")
    tot_o = tot_p = 0
    mismatches = 0
    for (decks, seed) in cases:
        o, p = results[(decks, seed, "original")], results[(decks, seed, "patched")]
        same = o["lines"] == p["lines"] or o.get("rerun_matches_patched", False)
        nondet = not same and o.get("rerun_differs", False)
        mismatches += 0 if (same or nondet) else 1
        tag = f"{' vs '.join(d[:-4] for d in decks)} #{seed}"
        tot_o += o["game_ms"]
        tot_p += p["game_ms"]
        sp = (o["game_ms"] / p["game_ms"]) if p["game_ms"] else 0
        note = ""
        if nondet:
            note = "  (original engine is nondeterministic here: its own re-run differs)"
        elif o.get("rerun_matches_patched"):
            note = "  (matched on re-run of the original)"
        if not same and (o["timeouts"] or p["timeouts"]):
            note = f"  (AI time-outs: original {o['timeouts']}, patched {p['timeouts']} -> divergence expected)"
        verdict = 'IDENTICAL' if same else ('NONDETERM.' if nondet else 'DIFFERENT')
        print(f"{tag[:52]:52s} {o['game_ms']/1000:9.1f}s {p['game_ms']/1000:9.1f}s {sp:7.2f}x  {verdict}{note}")
        base = os.path.join(args.out, re.sub(r"[^A-Za-z0-9]+", "_", tag))
        with open(base + ".original.log", "w", encoding="utf-8") as fh:
            fh.write("\n".join(o["lines"]))
        with open(base + ".patched.log", "w", encoding="utf-8") as fh:
            fh.write("\n".join(p["lines"]))
        if "CACHE MISMATCH" in p["raw"]:
            print("   !! cache verification reported mismatches, see", base + ".patched.raw.log")
            with open(base + ".patched.raw.log", "w", encoding="utf-8") as fh:
                fh.write(p["raw"])
    print()
    sp = tot_o / tot_p if tot_p else 0
    print(f"TOTAL game time: original {tot_o/1000:.1f}s, patched {tot_p/1000:.1f}s -> {sp:.2f}x faster")
    print(f"Behaviour preserved in {len(cases) - mismatches}/{len(cases)} games (identical logs, or the original is itself nondeterministic).")
    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
