#!/usr/bin/env python3
"""
Forge Nova engine patches: behaviour-equivalence check + speed comparison on Commander games.

Plays the same seeded AI-only games with the original Forge engine and with Nova's patched engine
(nova/bench/src/NovaSim.java: like Forge's "sim" mode, but the AI never runs into its decision time
limit, so both engines must play move-for-move identical games). Then
  * compares the full game logs line by line,
  * compares the wall time (total and per phase type: attacks, blocks, main phases...).

Usage (from the Forge folder, after nova\\tools\\build.cmd):
  python nova/bench/novadiff.py                     # 4-player games with your decks + 1v1 games
  python nova/bench/novadiff.py --set four --seeds 11,22
  python nova/bench/novadiff.py --inject 4:12       # big boards: 12 permanents per player put into play on turn 4
  python nova/bench/novadiff.py --verify-caches     # patched engine re-checks every cached answer
  python nova/bench/novadiff.py --baseline nova/bench/results   # reuse the original engine's saved games
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BENCH = os.path.join(ROOT, "nova", "bench")

FOUR = [
    ("Atraxa", "Kynaios", "Elenda and Azor", "Jenson"),
    ("Nissa", "Toph", "Isshin", "Glarb"),
    ("Zidane", "Savra", "Ulamog", "Serah Farron"),
    ("Ekthi", "Bruna", "Elda", "Grizzlegom"),
    ("Kwia", "Mishra", "Nethroi", "Phelddagrif"),
    ("Szeriasis", "Zagorka", "Tana & Ravos", "Odriculous"),
    ("Baron Helmut Zemo", "Charixifiction", "Ludejestics", "Magarmation of Magic"),
    ("Vazi", "Rocco", "Mirmoldo", "Bananas Akibo"),
]
TWO = [
    ("Atraxa", "Kynaios"), ("Vazi", "Rocco"), ("Mirmoldo", "Bananas Akibo"), ("Elenda and Azor", "Jenson"),
    ("Nissa", "Toph"), ("Isshin", "Glarb"), ("Zidane", "Savra"), ("Ulamog", "Serah Farron"),
]
NOISE = re.compile(r"^\[NovaSim\]|not assigned to any set|Upcoming set|^Read cards|Language '|GuiBase|ThreadUtil|"
                   r"^\s*$|\[Nova\]|WARNING|SEVERE|EventBus|^\s+at |^\s*\.\.\. \d+ more|^Caused by|Exception|"
                   r"^Warning: default|^Correcting zone|^\(ThreadUtil|^Match Result")


def forge_jar():
    jars = glob.glob(os.path.join(ROOT, "forge-gui-desktop-*-jar-with-dependencies.jar"))
    if not jars:
        sys.exit("Forge desktop jar not found in " + ROOT)
    return jars[0]


def run_case(cp, decks, seed, args, jvm):
    cmd = ["java", "-Xmx3g", "-XX:+UseParallelGC", "-Djava.awt.headless=true"] + jvm + ["-cp", cp, "NovaSim",
           "-d", *[d + ".dck" for d in decks], "-s", str(seed), "-timeout", str(args.timeout), "-clock", str(args.clock)]
    if args.inject:
        cmd += ["-inject", args.inject]
    if args.maxturns:
        cmd += ["-maxturns", str(args.maxturns)]
    t0 = time.time()
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    wall = time.time() - t0
    out = p.stdout + p.stderr
    total = re.findall(r"\[NovaSim\] total ([\d.]+) s", out)
    phases = {m[0]: float(m[1]) for m in re.findall(r"\[NovaSim\] phase (\S+)\s+([\d.]+) s", out)}
    lines = [ln.rstrip() for ln in out.splitlines() if not NOISE.search(ln)]
    return {"wall": wall, "total": float(total[0]) if total else -1.0, "phases": phases, "lines": lines, "raw": out,
            "clock": "(CLOCK LIMIT)" in out, "mismatch": "CACHE MISMATCH" in out}


def case_base(folder, decks, seed):
    return os.path.join(folder, re.sub(r"[^A-Za-z0-9]+", "_", f"{' vs '.join(decks)} #{seed}"))


def game_options(args):
    """The options that change which game is played; a saved original game is reused only when they match."""
    return {"timeout": args.timeout, "clock": args.clock, "inject": args.inject, "maxturns": args.maxturns,
            "deterministic": args.deterministic}


# Forge declares forced attackers from a thread pool with one thread per CPU, so their order in the log can
# differ between runs of the same engine. With one reported CPU that pool runs its tasks in order.
DETERMINISTIC_JVM = ["-XX:ActiveProcessorCount=1", "-XX:ParallelGCThreads=4"]


def load_baseline(folder, decks, seed, args):
    """The original engine's result saved by an earlier run (log + .json sidecar), or None."""
    base = case_base(folder, decks, seed)
    try:
        with open(base + ".original.json", encoding="utf-8") as fh:
            meta = json.load(fh)
        with open(base + ".original.log", encoding="utf-8") as fh:
            lines = fh.read().split("\n")
    except OSError:
        return None
    if meta.get("options") != game_options(args):
        return None
    return {"wall": meta["wall"], "total": meta["total"], "phases": meta["phases"], "lines": lines, "raw": "",
            "clock": meta["clock"], "mismatch": False, "baseline": True}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="four,two", help="four, two or both (comma separated)")
    ap.add_argument("--games", default="", help="indexes into the chosen sets, e.g. 0,3")
    ap.add_argument("--seeds", default="101")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--timeout", type=int, default=1000, help="AI decision time limit in seconds")
    ap.add_argument("--clock", type=int, default=3600, help="give up on a game after this many seconds")
    ap.add_argument("--inject", default="", help="turn:count - put count permanents per player into play on that turn")
    ap.add_argument("--maxturns", type=int, default=0)
    ap.add_argument("--verify-caches", action="store_true")
    ap.add_argument("--only", default="", help="run only 'original' or 'patched'")
    ap.add_argument("--patches", default=os.path.join(ROOT, "nova", "lib", "nova-engine-patches.jar"))
    ap.add_argument("--out", default=os.path.join(ROOT, "nova", "bench", "results"))
    ap.add_argument("--baseline", default="", help="folder of an earlier run: reuse its original-engine games "
                    "(same decks, seeds and options) instead of playing them again")
    ap.add_argument("--deterministic", action="store_true", help="make the JVMs report one CPU so Forge's "
                    "thread-pool decisions (forced attackers) happen in a fixed order; both engines get it")
    ap.add_argument("--reruns", type=int, default=2, help="when logs differ, play the original this many more times")
    args = ap.parse_args()

    jar = forge_jar()
    classes = os.path.join(BENCH, "classes")
    if not os.path.exists(os.path.join(classes, "NovaSim.class")):
        sys.exit("Compile nova/bench/src/NovaSim.java into nova/bench/classes first (see nova/README.md).")
    games = []
    for s in args.set.split(","):
        games += {"four": FOUR, "two": TWO}[s]
    if args.games:
        games = [games[int(i)] for i in args.games.split(",")]
    seeds = [int(s) for s in args.seeds.split(",")]
    cases = [(g, s) for g in games for s in seeds]
    os.makedirs(args.out, exist_ok=True)

    common = DETERMINISTIC_JVM if args.deterministic else []
    variants = {"original": ([jar, classes], common), "patched": ([args.patches, jar, classes], common)}
    if args.verify_caches:
        variants["patched"] = (variants["patched"][0], common + ["-Dnova.verifyCaches=true"])
    if args.only:
        variants = {args.only: variants[args.only]}

    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as ex:
        futs = {}
        for (decks, seed) in cases:
            for name, (cp, jvm) in variants.items():
                if name == "original" and args.baseline:
                    r = load_baseline(args.baseline, decks, seed, args)
                    if r:
                        results[(decks, seed, name)] = r
                        continue
                futs[ex.submit(run_case, os.pathsep.join(cp), decks, seed, args, jvm)] = (decks, seed, name)
        for f in concurrent.futures.as_completed(futs):
            decks, seed, name = futs[f]
            r = results[(decks, seed, name)] = f.result()
            print(f"  done {name:9s} seed {seed} {' vs '.join(decks)}: {r['total']:.1f}s", flush=True)

    # Forge's AI decides some things on a thread pool (e.g. forced attackers are added to combat from parallel
    # tasks), so even the original engine does not always produce the same log (see --deterministic). When logs
    # differ, re-run the original: if a re-run matches the patched game, the patched engine played a game the
    # original also plays; if the re-runs only disagree with each other, the case is nondeterministic.
    if "original" in variants and "patched" in variants:
        for (decks, seed) in cases:
            o, p = results[(decks, seed, "original")], results[(decks, seed, "patched")]
            for _ in range(args.reruns if o["lines"] != p["lines"] else 0):
                o2 = run_case(os.pathsep.join(variants["original"][0]), decks, seed, args, variants["original"][1])
                o["rerun_differs"] = o.get("rerun_differs", False) or o2["lines"] != o["lines"]
                if o2["lines"] == p["lines"]:
                    o["rerun_matches_patched"] = True
                    break

    print()
    print(f"{'game':60s} {'original':>9s} {'patched':>9s} {'speedup':>8s}  log")
    tot = {"original": 0.0, "patched": 0.0}
    phase_tot = {"original": {}, "patched": {}}
    bad = 0
    for (decks, seed) in cases:
        tag = f"{' vs '.join(decks)} #{seed}"
        base = case_base(args.out, decks, seed)
        o, p = results.get((decks, seed, "original")), results.get((decks, seed, "patched"))
        for name, r in (("original", o), ("patched", p)):
            if r:
                tot[name] += r["total"]
                for k, v in r["phases"].items():
                    phase_tot[name][k] = phase_tot[name].get(k, 0.0) + v
                with open(base + "." + name + ".log", "w", encoding="utf-8") as fh:
                    fh.write("\n".join(r["lines"]))
                if name == "original":
                    with open(base + ".original.json", "w", encoding="utf-8") as fh:
                        json.dump({"wall": r["wall"], "total": r["total"], "phases": r["phases"], "clock": r["clock"],
                                   "options": game_options(args)}, fh)
        if o and p:
            same = o["lines"] == p["lines"]
            verdict = "IDENTICAL" if same else "DIFFERENT"
            if not same and o.get("rerun_matches_patched"):
                verdict, same = "IDENTICAL (matched on a re-run of the original)", True
            elif not same and o.get("rerun_differs"):
                verdict = "UNVERIFIED (the original differs from its own re-runs, none matched; try --deterministic)"
            if o["clock"] or p["clock"]:
                verdict += " (clock limit hit)"
            if not same:
                bad += 1
            sp = o["total"] / p["total"] if p["total"] > 0 else 0
            print(f"{tag[:60]:60s} {o['total']:8.1f}s {p['total']:8.1f}s {sp:7.2f}x  {verdict}")
        else:
            r = o or p
            print(f"{tag[:60]:60s} {r['total']:8.1f}s")
        if p and p["mismatch"]:
            bad += 1
            print("   !! cache verification reported mismatches, see", base + ".patched.raw.log")
            with open(base + ".patched.raw.log", "w", encoding="utf-8") as fh:
                fh.write(p["raw"])
    print()
    if tot["original"] and tot["patched"]:
        print(f"TOTAL: original {tot['original']:.1f}s, patched {tot['patched']:.1f}s -> {tot['original'] / tot['patched']:.2f}x faster")
        print("Time per phase type (original -> patched):")
        for k in sorted(phase_tot["original"], key=lambda k: -phase_tot["original"][k]):
            a, b = phase_tot["original"][k], phase_tot["patched"].get(k, 0.0)
            if a >= 0.5:
                print(f"   {k:22s} {a:8.1f}s -> {b:8.1f}s  ({a / b if b else 0:.2f}x)")
        print(f"Identical game logs: {len(cases) - bad}/{len(cases)}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
