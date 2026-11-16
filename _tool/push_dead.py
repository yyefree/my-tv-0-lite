import urllib.request
body = open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\dead_test.m3u", encoding="utf-8").read().encode("utf-8")
req = urllib.request.Request("http://192.168.9.153:34567/api/import-text", data=body, method="POST", headers={"Content-Type": "text/plain"})
print("import =>", urllib.request.urlopen(req, timeout=20).status)
