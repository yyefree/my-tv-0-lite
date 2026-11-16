import urllib.request, time
def get(u, timeout=12):
    t0 = time.time()
    r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0"}), timeout=timeout)
    return r.status, r.read(), time.time()-t0
for cid in ["CHANNEL00000311", "CHANNEL00000317"]:
    u = "http://ucdn.sd.chinamobile.com:8089/00/SNM/%s/index.m3u8" % cid
    s, b, dt = get(u)
    sub = [l.strip() for l in b.decode("utf-8","ignore").splitlines() if l.strip().startswith("http")]
    print(cid, "master", s, "%.2fs" % dt, "subs", len(sub))
    if sub:
        for i in range(2):
            s2, b2, dt2 = get(sub[0])
            print("   sub try%d" % (i+1), s2, "%.2fs" % dt2, b2[:20])
