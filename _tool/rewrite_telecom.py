# -*- coding: utf-8 -*-
"""
将山东电信专网可用源回写 channels.txt：
1) 删除两台已宕外省电信主机的失效源（124.228.160.40 / 119.129.172.3）
2) 匹配到现有频道：追加 #ISP:TELECOM #REGION:山东 + URL
3) 未匹配频道：以新块追加到文件末尾，按名称归入 group-title
备份原文件为 channels.txt.bak
"""
import csv
import os
import re
import shutil
from collections import defaultdict
from urllib.parse import urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(BASE, "..", "app", "src", "main", "res", "raw", "channels.txt")
REPORT = os.path.join(BASE, "sd_telecom_report.csv")
BAK = RAW + ".bak"

DEAD_HOSTS = {"124.228.160.40", "119.129.172.3"}
TEL_HOSTS = {"150.138.8.143", "150.138.8.132"}


def norm(name: str) -> str:
    """频道名归一化：去空格、横线，统一 CCTV 写法。"""
    s = name.strip().lower()
    s = re.sub(r"[\s\-_·\[\]（）()]", "", s)
    s = s.replace("cctv", "cctv")
    return s


def group_of(name: str) -> str:
    n = name
    if re.search(r"cctv|cgti|cetv|中国教育|中央新影|中学生", n, re.I):
        return "央视频道"
    if "卫视" in n or re.search(r"^(东方|东南|三沙|北京|天津|河北|山西|内蒙古|辽宁|吉林|黑龙江|上海|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|广西|海南|重庆|四川|贵州|云南|西藏|陕西|甘肃|青海|宁夏|新疆|深圳)", n):
        return "卫视频道"
    if re.search(r"少儿|卡通|动画|卡酷", n, re.I):
        return "少儿频道"
    if re.search(r"电影|影院|大片|剧场|影视|动作|喜剧|经典电影", n):
        return "影视频道"
    if re.search(r"纪录片|纪录|文物|书画|梨园|垂钓|体育|足球|足协", n, re.I):
        return "数字频道"
    if re.search(r"山东|济南|青岛|烟台|威海|淄博|潍坊|临沂|济宁|东营|泰安|威海|日照|滨州|德州|聊城|菏泽|枣庄|QTV|东平|东明|东昌|东阿|东营", n):
        return "山东地方"
    return "地方频道"


def parse_blocks(path):
    """返回 (head_lines, blocks)；head_lines 含 #EXTM3U 等非块行。"""
    blocks = []
    head = []
    cur = None
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            s = line.strip()
            if s == "#EXTM3U":
                head.append(line)
                continue
            if not s:
                continue
            if s.startswith("#EXTINF"):
                cur = {"extinf": line, "title": s.split(",", 1)[-1].strip(), "entries": []}
                blocks.append(cur)
            elif s.startswith("#ISP:"):
                cur["entries"].append({"isp": s.split(":", 1)[1].strip(), "region": "", "url": ""})
            elif s.startswith("#REGION:"):
                if cur and cur["entries"]:
                    cur["entries"][-1]["region"] = s.split(":", 1)[1].strip()
            elif s.startswith("http"):
                if cur and cur["entries"]:
                    cur["entries"][-1]["url"] = s
    return head, blocks


def load_new_telecom():
    rows = list(csv.DictReader(open(REPORT, encoding="utf-8-sig")))
    tel = [r for r in rows if r["status"] == "OK" and r["host"] in TEL_HOSTS]
    by_chan = defaultdict(list)
    for r in tel:
        by_chan[r["channel"]].append(r["url"])
    return by_chan


def main():
    head, blocks = parse_blocks(RAW)
    by_chan_new = load_new_telecom()

    # 现有频道归一化 -> block
    cur_map = {}
    for b in blocks:
        cur_map.setdefault(norm(b["title"]), b)

    removed = 0
    appended_to_existing = 0
    new_blocks = []
    matched_norm = set()

    # 1) 删除宕机主机失效条目；统计
    for b in blocks:
        kept = []
        for e in b["entries"]:
            if e["isp"] == "TELECOM" and urlparse(e["url"]).hostname in DEAD_HOSTS:
                removed += 1
                continue
            kept.append(e)
        b["entries"] = kept

    # 2) 匹配现有频道追加
    for chan, urls in by_chan_new.items():
        key = norm(chan)
        if key in cur_map:
            b = cur_map[key]
            for u in urls:
                b["entries"].append({"isp": "TELECOM", "region": "山东", "url": u})
                appended_to_existing += 1
            matched_norm.add(key)
        else:
            new_blocks.append((chan, urls))

    # 3) 未匹配频道 -> 新块
    for chan, urls in new_blocks:
        g = group_of(chan)
        extinf = f'#EXTINF:-1 tvg-name="{chan}" group-title="{g}",{chan}'
        entries = [{"isp": "TELECOM", "region": "山东", "url": u} for u in urls]
        blocks.append({"extinf": extinf, "title": chan, "entries": entries})

    # 写回
    shutil.copy2(RAW, BAK)
    with open(RAW, "w", encoding="utf-8", newline="\n") as f:
        for h in head:
            f.write(h + "\n")
        for b in blocks:
            f.write(b["extinf"] + "\n")
            for e in b["entries"]:
                f.write(f"#ISP:{e['isp']}\n")
                if e["region"]:
                    f.write(f"#REGION:{e['region']}\n")
                f.write(e["url"] + "\n")

    print(f"删除宕机失效电信源: {removed}")
    print(f"匹配现有频道追加: {appended_to_existing} 条，覆盖 {len(matched_norm)} 个频道")
    print(f"新增频道块: {len(new_blocks)} 个，电信源 {sum(len(u) for _, u in new_blocks)} 条")
    print(f"回写后频道块总数: {len(blocks)}")
    print(f"备份: {BAK}")


if __name__ == "__main__":
    main()
