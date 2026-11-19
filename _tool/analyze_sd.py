# -*- coding: utf-8 -*-
import csv
from collections import defaultdict

rows = list(csv.DictReader(open("sd_telecom_report.csv", encoding="utf-8-sig")))
ok = [r for r in rows if r["status"] == "OK"]
bad = [r for r in rows if r["status"] not in ("OK", "MULTICAST")]

# 延迟分布
ms = sorted(int(r["ms"]) for r in ok if r["ms"])
def pct(p): return ms[min(len(ms) - 1, int(len(ms) * p))]
print(f"可用 {len(ok)} 条 | 延迟 p50={pct(0.5)}ms p90={pct(0.9)}ms max={ms[-1]}ms")

# 不可用按频道
chan_bad = defaultdict(int)
for r in bad:
    chan_bad[r["channel"]] += 1
print(f"\n不可用 {len(bad)} 条，涉及 {len(chan_bad)} 个频道：")
for c, n in sorted(chan_bad.items(), key=lambda x: -x[1]):
    print(f"  {c}  x{n}")

# 各来源贡献
src = defaultdict(lambda: [0, 0])
for r in rows:
    for s in r["source"].split("+"):
        src[s][0] += 1
        if r["status"] == "OK":
            src[s][1] += 1
print("\n=== 各来源（条目数/可用）===")
for s, (t, o) in sorted(src.items(), key=lambda x: -x[1][0]):
    print(f"  {s:34s} {t:4d} 可用{o:4d}")

# 每频道是否有多条可用
chan_ok = defaultdict(list)
for r in ok:
    chan_ok[r["channel"]].append(int(r["ms"] or 99999))
multi = {c: v for c, v in chan_ok.items() if len(v) > 1}
print(f"\n有多个可用源备份的频道: {len(multi)}")
print(f"仅单条可用源的频道: {len(chan_ok) - len(multi)}")
