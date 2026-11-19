# -*- coding: utf-8 -*-
"""把探测为 OK 的源导出为可直接导入的 m3u，并按专网/公网分文件。"""
import csv
import os

BASE = os.path.dirname(os.path.abspath(__file__))
rows = list(csv.DictReader(open(os.path.join(BASE, "sd_telecom_report.csv"), encoding="utf-8-sig")))
TELECOM = {"150.138.8.143", "150.138.8.132"}

def dump(rs, path):
    with open(path, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for r in rs:
            name = r["channel"] or r["host"]
            group = "山东电信专网" if r["host"] in TELECOM else "公网兜底"
            f.write(f'#EXTINF:-1 group-title="{group}",{name}\n{r["url"]}\n')

ok = [r for r in rows if r["status"] == "OK"]
tel_ok = [r for r in ok if r["host"] in TELECOM]
pub_ok = [r for r in ok if r["host"] not in TELECOM]

dump(ok, os.path.join(BASE, "sd_telecom_ok_all.m3u"))
dump(tel_ok, os.path.join(BASE, "sd_telecom_ok_unicast.m3u"))
dump(pub_ok, os.path.join(BASE, "sd_telecom_ok_public.m3u"))

print(f"全部可用: {len(ok)} -> sd_telecom_ok_all.m3u")
print(f"专网可用: {len(tel_ok)} -> sd_telecom_ok_unicast.m3u")
print(f"公网可用: {len(pub_ok)} -> sd_telecom_ok_public.m3u")
