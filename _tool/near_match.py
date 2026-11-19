# -*- coding: utf-8 -*-
import re
import rewrite_telecom as w

head, blocks = w.parse_blocks(w.RAW)
by = w.load_new_telecom()
cur = {}
for b in blocks:
    cur.setdefault(w.norm(b["title"]), b["title"])
new = [c for c in by if w.norm(c) not in cur]


def core(s):
    m = re.search(r"cctv\s*0*(\d+)", s, re.I)
    if m:
        return "cctv" + m.group(1)
    for kw in ["CGTN", "CETV", "QTV", "卫视"]:
        if kw in s:
            return re.split(kw, s)[0] + kw
    return None


cur_core = {}
for t in cur.values():
    k = core(t)
    if k:
        cur_core.setdefault(k, t)

print("=== 二次近名匹配 ===")
for c in sorted(new):
    k = core(c)
    hit = cur_core.get(k) if k else None
    if hit:
        print(f"  {c}  ~=  {hit}")
