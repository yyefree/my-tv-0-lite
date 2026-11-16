import urllib.request
u = "http://ucdn.sd.chinamobile.com:8089/00/SNM/CHANNEL00000301/index.m3u8"
for ua in [None, "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36"]:
    try:
        h = {} if ua is None else {"User-Agent": ua}
        req = urllib.request.Request(u, headers=h)
        r = urllib.request.urlopen(req, timeout=8)
        body = r.read(300)
        print("UA=%r status=%s ctype=%s" % (ua, r.status, r.headers.get("Content-Type")))
        print(" body:", body[:200])
    except Exception as e:
        print("UA=%r ERR %s" % (ua, e))
