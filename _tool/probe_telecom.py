# -*- coding: utf-8 -*-
"""
电信线路源可用性探测
- 解析 app/src/main/res/raw/channels.txt，抽取 #ISP:TELECOM 标注的全部地址
- 并发做 DNS 解析 + HTTP 拉流（m3u8 读首行 / ts 读首包），记录状态码、耗时、解析 IP
- 输出：telecom_report.csv（明细）+ 控制台汇总
本机出口：山东济南 Chinanet（电信）
"""
import concurrent.futures as cf
import csv
import re
import socket
import sys
import time
from urllib.parse import urlparse

import urllib.request
import urllib.error

RAW = r"..\app\src\main\res\raw\channels.txt"
OUT_CSV = r"telecom_report.csv"

UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36"
TIMEOUT = 8
WORKERS = 12
MAX_BYTES = 64 * 1024  # 首包拉到 64KB 即可判定可播


def parse(path):
    """返回 [(channel, region, url, isp)]，按 EXTINF 标题归属频道。"""
    rows = []
    channel = ""
    isp, region = "", ""
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#EXTINF"):
                channel = line.split(",", 1)[-1].strip()
                isp, region = "", ""
            elif line.startswith("#ISP:"):
                isp = line.split(":", 1)[1].strip()
            elif line.startswith("#REGION:"):
                region = line.split(":", 1)[1].strip()
            elif line.startswith("http"):
                rows.append((channel, region, line, isp))
    return rows


def probe(row):
    channel, region, url, isp = row
    rec = {
        "channel": channel, "region": region, "isp": isp, "url": url,
        "status": "", "code": "", "ip": "", "ms": "", "note": "",
    }
    host = urlparse(url).hostname
    t0 = time.perf_counter()
    try:
        # 1) DNS
        try:
            ip = socket.gethostbyname(host)
            rec["ip"] = ip
        except socket.gaierror as e:
            rec["status"] = "DNS_FAIL"
            rec["note"] = f"DNS: {e}"
            return rec

        # 2) HTTP 拉流
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            rec["code"] = resp.status
            body = resp.read(MAX_BYTES)
            rec["ms"] = int((time.perf_counter() - t0) * 1000)
            text = body.decode("utf-8", "ignore")
            if url.lower().endswith(".m3u8") or "#EXTM3U" in text[:64]:
                # m3u8：是否含媒体片段或子 playlist
                seg = [l for l in text.splitlines()
                       if l.strip().startswith(("http", "#EXTINF"))]
                if "#EXTM3U" in text[:64] or seg:
                    rec["status"] = "OK"
                else:
                    rec["status"] = "EMPTY"
                    rec["note"] = "m3u8 无片段"
            else:
                if len(body) > 0:
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
    rows = parse(RAW)
    tel = [r for r in rows if r[3] == "TELECOM"]
    print(f"解析地址总数: {len(rows)}  电信(TELECOM)地址: {len(tel)}")

    results = []
    with cf.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for r in ex.map(probe, tel):
            results.append(r)

    # 汇总
    ok = [r for r in results if r["status"] == "OK"]
    fail = [r for r in results if r["status"] != "OK"]
    by_status = {}
    for r in results:
        by_status[r["status"]] = by_status.get(r["status"], 0) + 1

    print("\n=== 状态统计（电信线路）===")
    for k, v in sorted(by_status.items(), key=lambda x: -x[1]):
        print(f"  {k:14s} {v}")
    print(f"\n  可用 OK: {len(ok)} / {len(results)}  不可用: {len(fail)}")

    # 频道维度：每个频道只要有一条可用即记为可用
    chan_ok, chan_total = set(), set()
    for r in results:
        chan_total.add(r["channel"])
        if r["status"] == "OK":
            chan_ok.add(r["channel"])
    print(f"  覆盖频道: {len(chan_total)}  有可用源的频道: {len(chan_ok)}")

    # 输出 CSV
    fields = ["channel", "region", "status", "code", "ms", "ip", "url", "note"]
    with open(OUT_CSV, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(sorted(results, key=lambda r: (r["status"] != "OK", r["channel"])))
    print(f"\n明细已写入: {OUT_CSV}")

    # 打印失败清单
    if fail:
        print("\n=== 不可用明细（前 40）===")
        for r in fail[:40]:
            print(f"  [{r['status']:12s}] {r['channel']:12s} {r['region']:4s} {r['note'][:40]}  {r['url'][:70]}")


if __name__ == "__main__":
    sys.exit(main())
