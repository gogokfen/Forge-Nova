"""rtnorm.py file.java: normalize a decompiled source for round-trip comparison (whitespace, labels, imports)."""
import sys, re
out = []
for ln in open(sys.argv[1], encoding="utf-8", errors="replace"):
    s = ln.strip()
    if not s or s.startswith("import "):
        continue
    s = re.sub(r"\blabel\d+\b", "labelN", s)
    s = re.sub(r"\bvar\d+\b", "varN", s)
    out.append(s)
print("\n".join(out))
