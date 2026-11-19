# -*- coding: utf-8 -*-
"""复跑 channels.txt 中所有山东电信源（150.138.8.x），确认回写后仍可用。"""
import os
import socket
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(BASE, "..", "app", "src", "main", "res", "raw", "channels.txt")
TEL_HOSTS = {"150.138.8.143", "150.138.8.132"}
UA = "Mozilla/5.0 (Linux; Android 12; TV)"
TIMEOUT = 8

# 收集文件内山东电信条目
entries = []
cur_title = ""
with open(RAW, encoding="utf-8") as f:
    for line in f:
        s = line.strip()
        if s.startswith("#EXTINF"):
            cur_title = s.split(",", 1)[-1].strip()
        elif s.startswith("http"):
            host = urlparse(s).hostname
            if host in TEL_HOSTS:
                entries.append((cur_title, s))


def probe(item):
    title, url = item
    t0 = time.perf_counter()
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read(32 * 1024)
            return title, url, "OK", resp.status, int((time.perf_counter() - t0) * 1000)
    except Exception as e:
        return title, url, "FAIL", type(e).__name__, int((time.perf_counter() - t0) * 1000)


with ThreadPoolExecutor(max_workers=16) as ex:
    results = list(ex.map(probe, entries))

ok = [r for r in results if r[2] == "OK"]
fail = [r for r in results if r[2] != "OK"]
print(f"文件内山东电信源: {len(results)}  可用: {len(ok)}  失败: {len(fail)}")
for r in fail[:20]:
    print("  FAIL", r[0], r[3], r[4], "ms", r[1][:60])
