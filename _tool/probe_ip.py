import urllib.request, time
ips = ["39.135.159.114", "39.134.144.6"]
for ip in ips:
    u = "http://%s:8089/00/SNM/CHANNEL00000311/index.m3u8" % ip
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0"}), timeout=8)
        b = r.read(120)
        print(ip, r.status, "%.2fs" % (time.time()-t0), b[:40].decode("utf-8","ignore").replace("\r\n"," "))
    except Exception as e:
        print(ip, "ERR", "%.2fs" % (time.time()-t0), type(e).__name__, str(e)[:80])
