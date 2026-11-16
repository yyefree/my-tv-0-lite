import urllib.request, json
for path in ["/api/sources"]:
    try:
        r = urllib.request.urlopen("http://192.168.9.153:34567" + path, timeout=15)
        print(path, r.status)
        print(r.read().decode("utf-8","ignore")[:1200])
    except Exception as e:
        print(path, "ERR", e)
