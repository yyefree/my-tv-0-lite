# -*- coding: utf-8 -*-
"""解析 channels.txt 为块结构，统计各 ISP 源；对照山东电信新源频道名匹配情况。"""
import os
import re
from collections import defaultdict, Counter

BASE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(BASE, "..", "app", "src", "main", "res", "raw", "channels.txt")

# 已测不可用的外省电信主机（上一轮探测）
DEAD_HOSTS = {"124.228.160.40", "119.129.172.3"}


def parse(path):
    blocks = []  # {title, entries:[{isp,region,url}]}
    title = ""
    entries = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line == "#EXTM3U":
                continue
            if line.startswith("#EXTINF"):
                if title or entries:
                    blocks.append({"title": title, "entries": entries})
                title = line.split(",", 1)[-1].strip()
                entries = []
            elif line.startswith("#ISP:"):
                entries.append({"isp": line.split(":", 1)[1].strip(), "region": "", "url": ""})
            elif line.startswith("#REGION:"):
                if entries:
                    entries[-1]["region"] = line.split(":", 1)[1].strip()
            elif line.startswith("http"):
                if entries:
                    entries[-1]["url"] = line
    if title or entries:
        blocks.append({"title": title, "entries": entries})
    return blocks


def main():
    blocks = parse(RAW)
    print(f"频道块数: {len(blocks)}")

    isp_cnt = Counter()
    tel_entries = []
    for b in blocks:
        for e in b["entries"]:
            isp_cnt[e["isp"]] += 1
            if e["isp"] == "TELECOM":
                tel_entries.append((b["title"], e))
    print("=== 各 ISP 条目数 ===")
    for k, v in isp_cnt.most_common():
        print(f"  {k:10s} {v}")

    print(f"\n现有 TELECOM 条目: {len(tel_entries)}")
    dead = [t for t in tel_entries if t[1]["url"].split("/")[2] in DEAD_HOSTS]
    alive = [t for t in tel_entries if t[1]["url"].split("/")[2] not in DEAD_HOSTS]
    print(f"  命中已宕主机: {len(dead)}")
    print(f"  其余(待复验): {len(alive)}")

    # 现有电信频道集合
    cur_tel_chan = {t[0] for t in tel_entries}

    # 读新山东电信可用源
    new_rows = list(__import__("csv").DictReader(
        open(os.path.join(BASE, "sd_telecom_report.csv"), encoding="utf-8-sig")))
    tel_new = [r for r in new_rows if r["status"] == "OK" and r["host"] in {"150.138.8.143", "150.138.8.132"}]
    new_chan = {r["channel"] for r in tel_new}
    print(f"\n新山东电信可用条目: {len(tel_new)}  频道: {len(new_chan)}")

    # 频道名匹配
    match = cur_tel_chan & new_chan
    only_new = new_chan - cur_tel_chan
    print(f"  与现有频道名完全匹配: {len(match)}")
    print(f"  新源里有、现有文件无同名频道: {len(only_new)}")
    print("  新增频道样例:", "、".join(sorted(only_new)[:25]))

    # 现有电信源所在频道里，新源缺的
    only_old = cur_tel_chan - new_chan
    print(f"\n  现有有电信源但新源缺的频道: {len(only_old)}")
    print("  ", "、".join(sorted(only_old)[:25]))


if __name__ == "__main__":
    main()
