import urllib.request
p = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\channels_sd.m3u"
body = open(p, encoding="utf-8").read().encode("utf-8")
req = urllib.request.Request("http://192.168.9.153:34567/api/import-text", data=body, method="POST", headers={"Content-Type": "text/plain"})
r = urllib.request.urlopen(req, timeout=25)
print("import-text =>", r.status)
