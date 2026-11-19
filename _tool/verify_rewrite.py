# -*- coding: utf-8 -*-
import os
from collections import Counter
from urllib.parse import urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(BASE, "..", "app", "src", "main", "res", "raw", "channels.txt")

blocks = []
cur = None
errors = []
with open(RAW, encoding="utf-8") as f:
    for i, line in enumerate(f, 1):
        s = line.strip()
        if not s or s == "#EXTM3U":
            continue
        if s.startswith("#EXTINF"):
            cur = {"title": s.split(",", 1)[-1].strip(), "entries": []}
            blocks.append(cur)
        elif s.startswith("#ISP:"):
            if cur is None:
                errors.append(f"行{i}: ISP 无频道块")
            else:
                cur["entries"].append({"isp": s.split(":", 1)[1].strip(), "region": "", "url": ""})
        elif s.startswith("#REGION:"):
            if cur and cur["entries"]:
                cur["entries"][-1]["region"] = s.split(":", 1)[1].strip()
        elif s.startswith("http"):
            if cur and cur["entries"]:
                cur["entries"][-1]["url"] = s
            else:
                errors.append(f"行{i}: URL 无归属 {s[:50]}")
        else:
            errors.append(f"行{i}: 无法识别 {s[:50]}")

# 完整性检查：每条 entry 必须有 url
for b in blocks:
    for e in b["entries"]:
        if not e["url"]:
            errors.append(f"频道 {b['title']} 存在无 URL 的 {e['isp']} 条目")

isp = Counter(e["isp"] for b in blocks for e in b["entries"])
print(f"频道块: {len(blocks)}")
print("各 ISP 条目:", dict(isp))

tel = [(b["title"], e) for b in blocks for e in b["entries"] if e["isp"] == "TELECOM"]
hosts = Counter(urlparse(e["url"]).hostname for _, e in tel)
print(f"\n回写后 TELECOM 条目: {len(tel)}")
print("电信主机分布:")
for h, n in hosts.most_common():
    print(f"  {h:20s} {n}")

# 山东电信源 region 检查
sd = [e for _, e in tel if urlparse(e["url"]).hostname in {"150.138.8.143", "150.138.8.132"}]
bad_region = [e for e in sd if e["region"] != "山东"]
print(f"\n山东电信源: {len(sd)}  region 非山东: {len(bad_region)}")

# 失效主机是否残留
dead = [e for _, e in tel if urlparse(e["url"]).hostname in {"124.228.160.40", "119.129.172.3"}]
print(f"宕机主机残留: {len(dead)}")

print(f"\n格式错误: {len(errors)}")
for x in errors[:10]:
    print("  ", x)
