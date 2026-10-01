#!/usr/bin/env python3
"""Run several seeded 4-player Commander AI games (Forge sim mode) with JFR and report game times."""
import concurrent.futures, os, re, subprocess, sys, time, argparse

ROOT = r"C:\Users\Oron\Downloads\Projects\MTG Forge"
JAR = os.path.join(ROOT, "forge-gui-desktop-2.0.15-SNAPSHOT-jar-with-dependencies.jar")
DECKS = os.path.join(os.environ["APPDATA"], "Forge", "decks", "commander")

GAMES = [
    ("Atraxa", "Kynaios", "Elenda and Azor", "Jenson"),
    ("Nissa", "Toph", "Isshin", "Glarb"),
    ("Zidane", "Savra", "Ulamog", "Serah Farron"),
    ("Ekthi", "Bruna", "Elda", "Grizzlegom"),
    ("Kwia", "Mishra", "Nethroi", "Phelddagrif"),
    ("Szeriasis", "Zagorka", "Tana & Ravos", "Odriculous"),
    ("Baron Helmut Zemo", "Charixifiction", "Ludejestics", "Magarmation of Magic"),
    ("Aureliable Source of Income", "Ayulnot Bear the Consequences", "Domain Expansion_ Ya Basic", "Milkynaios and Tironey"),
    ("Norin or go Home", "Oops! All Chandras", "Ragavan See, Rashmi Do", "Satoru of my Life"),
]


def run(i, decks, seed, cp, outdir, jfr, extra):
    tag = f"g{i}_{seed}"
    cmd = ["java", "-Xmx3g", "-Djava.awt.headless=true"] + extra
    if jfr:
        cmd += ["-XX:FlightRecorderOptions=stackdepth=1024",
                f"-XX:StartFlightRecording=filename={os.path.join(outdir, tag + '.jfr')},settings=profile"]
    cmd += ["-cp", cp, "forge.view.Main", "sim", "-D", DECKS, "-d", *[d + ".dck" for d in decks],
            "-f", "Commander", "-n", "1", "-c", "1800", "-s", str(seed)]
    t0 = time.time()
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = p.stdout + p.stderr
    with open(os.path.join(outdir, tag + ".out"), "w", encoding="utf-8") as fh:
        fh.write(out)
    ms = re.findall(r"ended in (\d+) ms", out)
    turn = re.findall(r"Game Outcome: Turn (\d+)", out)
    return tag, decks, time.time() - t0, (int(ms[0]) if ms else None), (turn[0] if turn else "?"), out.count("AI eval thread at timeout")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--patches", default="")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--games", default="")
    ap.add_argument("--nojfr", action="store_true")
    ap.add_argument("--jvm", default="")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    cp = (a.patches + os.pathsep if a.patches else "") + JAR
    idx = [int(x) for x in a.games.split(",")] if a.games else range(len(GAMES))
    extra = a.jvm.split() if a.jvm else []
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as ex:
        futs = [ex.submit(run, i, GAMES[i], a.seed, cp, a.out, not a.nojfr, extra) for i in idx]
        for f in concurrent.futures.as_completed(futs):
            tag, decks, wall, ms, turn, to = f.result()
            print(f"{tag:10s} wall {wall:6.1f}s game {ms/1000 if ms else -1:7.1f}s turn {turn:>3s} timeouts {to}  {' / '.join(decks)}", flush=True)


if __name__ == "__main__":
    main()
