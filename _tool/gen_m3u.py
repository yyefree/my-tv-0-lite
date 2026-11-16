import json
ok = json.load(open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sd_ok.json", encoding="utf-8"))
okurls = {c["url"]: c["name"] for c in ok}
out = ["#EXTM3U"]
cur = "其他"
n = 0
for line in open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sdtvlist.txt", encoding="utf-8", errors="ignore"):
    line = line.strip()
    if not line:
        continue
    if line.endswith("#genre#"):
        cur = line[:-7].strip().rstrip(",") or "其他"
        continue
    if "," not in line:
        continue
    name, url = line.split(",", 1)
    url = url.strip()
    if url not in okurls:
        continue
    n += 1
    title = okurls[url]
    out.append('#EXTINF:-1 tvg-chno="%d" tvg-name="%s" group-title="%s",%s' % (n, title, cur, title))
    out.append(url)
txt = "\r\n".join(out) + "\r\n"
p = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\channels_sd.m3u"
open(p, "w", encoding="utf-8", newline="").write(txt)
print("channels =", n, "bytes =", len(txt.encode("utf-8")))
src = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\my-tv-0\app\src\main\res\raw\channels.txt"
open(src, "w", encoding="utf-8", newline="").write(txt)
print("raw channels.txt updated")
