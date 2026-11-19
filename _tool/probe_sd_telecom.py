# -*- coding: utf-8 -*-
"""
聚合全网搜集到的山东电信直播源，去重后在本机（济南电信 Chinanet）实测。
- 解析 sd_sources/*.m3u（#EXTINF 标题 + URL）
- 按 URL 去重，记录来源文件、频道名
- 并发 DNS + HTTP 拉流测试，区分 http 单播 / rtp-udp 组播（组播不可直连）
- 输出 sd_telecom_report.csv（全量明细）、sd_telecom_best.csv（每频道最优）
"""
import csv
import os
import socket
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(BASE, "sd_sources")
OUT_CSV = os.path.join(BASE, "sd_telecom_report.csv")
OUT_BEST = os.path.join(BASE, "sd_telecom_best.csv")

UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36"
TIMEOUT = 8
WORKERS = 16
MAX_BYTES = 64 * 1024


def parse_m3u(path):
    """返回 [(channel, url)]，跳过非 http(s) 行。"""
    rows = []
    channel = ""
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#EXTINF"):
                channel = line.split(",", 1)[-1].strip()
            elif line.startswith(("http://", "https://")):
                rows.append((channel, line))
            elif line.startswith(("rtp://", "udp://", "rtsp://")):
                rows.append(("", line))  # 组播/RTSP，单独标注
    return rows


def probe(channel, url, source):
    rec = {
        "channel": channel, "source": source, "url": url,
        "status": "", "code": "", "ip": "", "ms": "", "host": "", "note": "",
    }
    scheme = urlparse(url).scheme
    host = urlparse(url).hostname
    rec["host"] = host or ""

    if scheme in ("rtp", "udp", "rtsp"):
        rec["status"] = "MULTICAST"
        rec["note"] = "组播/RTSP，公网不可直连"
        return rec

    t0 = time.perf_counter()
    try:
        try:
            ip = socket.gethostbyname(host)
            rec["ip"] = ip
        except socket.gaierror as e:
            rec["status"] = "DNS_FAIL"
            rec["note"] = f"DNS: {e}"
            return rec

        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            rec["code"] = resp.status
            body = resp.read(MAX_BYTES)
            rec["ms"] = int((time.perf_counter() - t0) * 1000)
            text = body.decode("utf-8", "ignore")
            if url.lower().endswith(".m3u8") or "#EXTM3U" in text[:64]:
                seg = [l for l in text.splitlines()
                       if l.strip().startswith(("http", "#EXTINF"))]
                if "#EXTM3U" in text[:64] or seg:
                    rec["status"] = "OK"
                else:
                    rec["status"] = "EMPTY"
                    rec["note"] = "m3u8 无片段"
            elif body:
                rec["status"] = "OK"
            else:
                rec["status"] = "EMPTY"
    except urllib.error.HTTPError as e:
        rec["status"] = "HTTP_ERR"
        rec["code"] = e.code
        rec["ms"] = int((time.perf_counter() - t0) * 1000)
        rec["note"] = str(e.reason)
    except urllib.error.URLError as e:
        rec["status"] = "UNREACHABLE"
        rec["note"] = str(e.reason)[:120]
    except socket.timeout:
        rec["status"] = "TIMEOUT"
        rec["note"] = f">{TIMEOUT}s"
    except ConnectionResetError:
        rec["status"] = "RESET"
        rec["note"] = "连接被重置"
    except Exception as e:
        rec["status"] = "ERR"
        rec["note"] = f"{type(e).__name__}: {e}"[:120]
    return rec


def main():
    # 聚合去重：url -> {channel, sources:set}
    merged = {}
    order = []
    for fn in sorted(os.listdir(SRC_DIR)):
        if not fn.lower().endswith((".m3u", ".m3u8", ".list")):
            continue
        path = os.path.join(SRC_DIR, fn)
        for channel, url in parse_m3u(path):
            if url not in merged:
                merged[url] = {"channel": channel, "sources": {fn}}
                order.append(url)
            else:
                merged[url]["sources"].add(fn)
                if not merged[url]["channel"] and channel:
                    merged[url]["channel"] = channel

    print(f"待探测唯一地址: {len(merged)}")
    tasks = [(merged[u]["channel"], u, "+".join(sorted(merged[u]["sources"])))
             for u in order]

    results = []
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for r in ex.map(lambda a: probe(*a), tasks):
            results.append(r)

    ok = [r for r in results if r["status"] == "OK"]
    fail = [r for r in results if r["status"] not in ("OK", "MULTICAST")]
    multi = [r for r in results if r["status"] == "MULTICAST"]

    by_status = defaultdict(int)
    for r in results:
        by_status[r["status"]] += 1
    print("\n=== 状态统计 ===")
    for k, v in sorted(by_status.items(), key=lambda x: -x[1]):
        print(f"  {k:14s} {v}")
    print(f"\n  可用 OK: {len(ok)} / {len(results)}   不可用: {len(fail)}   组播: {len(multi)}")

    # 按主机聚合可用/不可用
    by_host = defaultdict(lambda: [0, 0])
    for r in results:
        if r["status"] == "MULTICAST":
            continue
        by_host[r["host"]][0] += 1
        if r["status"] != "OK":
            by_host[r["host"]][1] += 1
    print("\n=== 按主机：总数 / 失效 ===")
    for h, (t, f) in sorted(by_host.items(), key=lambda x: x[1][1], reverse=True):
        print(f"  {h:24s} 总{t:3d} 失效{f:3d} 可用{t-f:3d}")

    # 频道覆盖
    chan_total, chan_ok = set(), set()
    for r in results:
        if r["channel"]:
            chan_total.add(r["channel"])
            if r["status"] == "OK":
                chan_ok.add(r["channel"])
    print(f"\n  覆盖频道: {len(chan_total)}  有可用源: {len(chan_ok)}")

    fields = ["channel", "status", "code", "ms", "ip", "host", "source", "url", "note"]
    with open(OUT_CSV, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(sorted(results, key=lambda r: (r["status"] != "OK", r["channel"])))

    # 每频道最优
    best = {}
    for r in results:
        if r["status"] != "OK":
            continue
        m = int(r["ms"] or 99999)
        if r["channel"] not in best or m < best[r["channel"]][0]:
            best[r["channel"]] = (m, r)
    with open(OUT_BEST, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["频道", "延迟ms", "主机", "来源", "地址"])
        for c, (m, r) in sorted(best.items()):
            w.writerow([c, m, r["host"], r["source"], r["url"]])

    print(f"\n明细: {OUT_CSV}\n最优: {OUT_BEST}")

    if fail:
        print("\n=== 不可用 Top20 ===")
        for r in fail[:20]:
            print(f"  [{r['status']:12s}] {r['channel']:14s} {r['host']:22s} {r['note'][:30]}")


if __name__ == "__main__":
    sys.exit(main())
