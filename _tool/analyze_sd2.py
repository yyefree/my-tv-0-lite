# -*- coding: utf-8 -*-
import csv
from collections import defaultdict

rows = list(csv.DictReader(open("sd_telecom_report.csv", encoding="utf-8-sig")))

TELECOM_HOSTS = {"150.138.8.143", "150.138.8.132"}

def subset(pred):
    rs = [r for r in rows if pred(r)]
    ok = [r for r in rs if r["status"] == "OK"]
    chan = {r["channel"] for r in rs if r["channel"]}
    chan_ok = {r["channel"] for r in ok if r["channel"]}
    return rs, ok, chan, chan_ok

for label, pred in [
    ("山东电信专网源(150.138.8.x)", lambda r: r["host"] in TELECOM_HOSTS),
    ("公网/第三方源", lambda r: r["host"] not in TELECOM_HOSTS),
]:
    rs, ok, chan, chan_ok = subset(pred)
    print(f"\n== {label} ==")
    print(f"  地址 {len(rs)}  可用 {len(ok)}  可用率 {len(ok)/max(1,len(rs))*100:.1f}%")
    print(f"  频道 {len(chan)}  有可用源 {len(chan_ok)}")

# 专网源里失败的频道
tel = [r for r in rows if r["host"] in TELECOM_HOSTS]
tel_bad = [r for r in tel if r["status"] != "OK"]
print(f"\n专网源失败 {len(tel_bad)} 条（均 404/Not Found）")

# 专网不可用频道，公网是否有兜底
tel_dead = {r["channel"] for r in tel if not any(x["status"] == "OK" for x in [y for y in tel if y["channel"] == r["channel"]])}
pub_ok = {r["channel"] for r in rows if r["host"] not in TELECOM_HOSTS and r["status"] == "OK"}
covered = tel_dead & pub_ok
print(f"专网无可用的频道 {len(tel_dead)} 个，其中公网有兜底: {len(covered)}")
print("公网可兜底:", "、".join(sorted(covered)) or "无")
still = tel_dead - pub_ok
print(f"\n专网+公网均不可用的频道: {len(still)}")
print("  " + "、".join(sorted(still)))

# 公网可用源里和山东/卫视相关的速览
print("\n=== 公网可用的山东本地/卫视源 ===")
for r in rows:
    if r["status"] == "OK" and r["host"] not in TELECOM_HOSTS and any(k in r["channel"] for k in ["山东","齐鲁","济南","青岛","烟台","威海","淄博","潍坊","临沂","济宁","卫视"]):
        print(f"  {r['channel']:20s} {r['host']}  {r['ms']}ms")
