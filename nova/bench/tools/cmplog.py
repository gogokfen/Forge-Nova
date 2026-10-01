"""cmplog.py A B: compare NovaSim runs; each argument is a raw NovaSim output (.out) or a saved novadiff log."""
import sys, re, importlib.util, difflib
spec = importlib.util.spec_from_file_location("nd", r"C:\Users\Oron\Downloads\Projects\MTG Forge\nova\bench\novadiff.py")
nd = importlib.util.module_from_spec(spec); spec.loader.exec_module(nd)
EXTRA = re.compile(r"^\[\d+\.\d+s\]\[info\]\[jfr|^SVar '")

def load(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    if path.endswith(".out"):
        return [ln.rstrip() for ln in text.splitlines() if not nd.NOISE.search(ln) and not EXTRA.search(ln)]
    return [ln for ln in text.split("\n") if not EXTRA.search(ln)]

a, b = load(sys.argv[1]), load(sys.argv[2])
if a == b:
    print("IDENTICAL", len(a), "lines")
else:
    print("DIFFERENT", len(a), len(b))
    for d in list(difflib.unified_diff(a, b, "A", "B", n=1, lineterm=""))[:40]:
        print(d.encode("ascii", "replace").decode())
