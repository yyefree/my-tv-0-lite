import json
p = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sd_ok.json"
ok = json.load(open(p, encoding="utf-8"))
lines = ["#EXTM3U"]
for c in ok:
    lines.append('#EXTINF:-1 tvg-name="%s",%s' % (c["name"], c["name"]))
    lines.append(c["url"])
txt = "\r\n".join(lines) + "\r\n"
out = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sd_live.m3u"
open(out, "w", encoding="utf-8", newline="").write(txt)
print("channels =", len(ok), "bytes =", len(txt.encode("utf-8")))
body = txt.encode("utf-8")
import urllib.request
req = urllib.request.Request("http://192.168.9.153:34567/api/import-text", data=body, method="POST", headers={"Content-Type": "text/plain"})
r = urllib.request.urlopen(req, timeout=20)
print("import-text =>", r.status, r.read(200))
