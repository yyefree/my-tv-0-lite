# -*- coding: utf-8 -*-
import csv
from collections import defaultdict

rows = list(csv.DictReader(open("telecom_report.csv", encoding="utf-8-sig")))
best = {}
for r in rows:
    if r["status"] != "OK":
        continue
    m = int(r["ms"] or 99999)
    if r["channel"] not in best or m < best[r["channel"]][0]:
        best[r["channel"]] = (m, r)

with open("telecom_best.csv", "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["频道", "地区", "延迟ms", "地址"])
    for c, (m, r) in sorted(best.items()):
        w.writerow([c, r["region"], m, r["url"]])

print("每频道最优源已写 telecom_best.csv，共", len(best), "个频道")
print("=== 延迟最快 Top10 ===")
for c, (m, r) in sorted(best.items(), key=lambda x: x[1][0])[:10]:
    print(f"  {m:4d}ms {c} ({r['region']})")
