# -*- coding: utf-8 -*-
import glob
import os
import re

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sd_sources")

LINK_RE = re.compile(r'https?://[^\s"<>()\\]+')
# 表格行：<td>序号</td><td>频道名</td><td>来源</td><td>链接</td>
ROW_RE = re.compile(r'<tr>(.*?)</tr>', re.S)
TD_RE = re.compile(r'<td[^>]*>(.*?)</td>', re.S)
TAGS = re.compile(r'<[^>]+>')

out = []
for f in glob.glob(os.path.join(DIR, "b2og_*.list")):
    text = open(f, encoding="utf-8", errors="ignore").read()
    for row in ROW_RE.findall(text):
        tds = [TAGS.sub("", t).strip() for t in TD_RE.findall(row)]
        url = next((x for x in LINK_RE.findall(row) if
                    any(k in x for k in ("m3u8", "/live/", "PLTV", "tsfile", "index.m3u8"))), None)
        if not url:
            continue
        name = tds[1] if len(tds) > 1 else (tds[0] if tds else "")
        name = name.replace("(1080p)", "").replace("(720p)", "").replace("(406p)", "").strip()
        out.append((name, url))

# 去重
seen = {}
for name, url in out:
    if url not in seen:
        seen[url] = name

with open(os.path.join(DIR, "b2og_extracted.m3u"), "w", encoding="utf-8") as f:
    f.write("#EXTM3U\n")
    for url, name in seen.items():
        f.write(f"#EXTINF:-1 ,{name}\n{url}\n")

print(f"抽取直播链接: {len(seen)} 条 -> b2og_extracted.m3u")
for url, name in list(seen.items())[:10]:
    print(f"  {name:16s} {url[:80]}")
