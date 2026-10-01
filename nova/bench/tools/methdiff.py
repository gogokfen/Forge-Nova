"""methdiff.py A.txt B.txt: per-method comparison of two normalized javap -c -p listings (local slot numbers ignored)."""
import sys, re
def split(path):
    meths, cur, name = {}, [], None
    for ln in open(path, encoding="utf-8", errors="replace"):
        ln = ln.rstrip("\n")
        if re.match(r"^  \S.*\(.*\).*;$", ln) or re.match(r"^  (static )?\{\};$", ln):
            if name: meths[name] = cur
            name, cur = ln.strip(), []
        elif name:
            ln = re.sub(r"(aload|astore|iload|istore|lload|lstore|dload|dstore|fload|fstore)(_| +)\d+", r"\1 L", ln)
            ln = re.sub(r"iinc +\d+", "iinc L", ln)
            cur.append(ln.strip())
    if name: meths[name] = cur
    return meths
a, b = split(sys.argv[1]), split(sys.argv[2])
for m in sorted(set(a) | set(b)):
    if m not in a: print("ONLY IN B:", m); continue
    if m not in b: print("ONLY IN A:", m); continue
    if a[m] != b[m]:
        print("DIFFERS:", m, len(a[m]), len(b[m]))

if len(sys.argv) > 3:
    import difflib
    for m in sorted(set(a) & set(b)):
        if a[m] != b[m] and (sys.argv[3] == "all" or sys.argv[3] in m):
            print("=" * 20, m)
            for d in difflib.unified_diff(a[m], b[m], "orig", "new", n=2, lineterm=""):
                print(d)
