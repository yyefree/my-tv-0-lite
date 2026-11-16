import urllib.request, json
base = "http://192.168.9.153:34567"
for path in ["/api/default-channel", "/api/sources"]:
    try:
        r = urllib.request.urlopen(base + path, timeout=8)
        b = r.read(600)
        print(path, r.status, b[:400])
    except Exception as e:
        print(path, "ERR", e)
