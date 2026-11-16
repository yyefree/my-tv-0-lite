import urllib.request, time
for cid in ["CHANNEL00000311", "CHANNEL00000317", "CHANNEL00000359", "CHANNEL00000301"]:
    u = "http://ucdn.sd.chinamobile.com:8089/00/SNM/%s/index.m3u8" % cid
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0"}), timeout=10)
        b = r.read(200)
        print(cid, r.status, "%.1fs" % (time.time()-t0), b[:30])
    except Exception as e:
        print(cid, "ERR", "%.1fs" % (time.time()-t0), type(e).__name__, e)
