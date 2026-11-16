import urllib.request, time, json
def get(u, timeout=10):
    t0 = time.time()
    r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0"}), timeout=timeout)
    return r.status, r.read(), time.time()-t0
for cid, name in [("CHANNEL00000311","CCTV-1"),("CHANNEL00000360","CCTV-5"),("CHANNEL00000317","CCTV-2"),("CHANNEL00000301","山东卫视")]:
    u = "http://ucdn.sd.chinamobile.com:8089/00/SNM/%s/index.m3u8" % cid
    try:
        s, b, dt = get(u)
        txt = b.decode("utf-8","ignore")
        sub = [l.strip() for l in txt.splitlines() if l.strip().startswith("http")]
        info = [l for l in txt.splitlines() if "RESOLUTION" in l]
        print(name, "master", s, "%.2fs" % dt, (info[0][:80] if info else ""))
        if sub:
            s2, b2, dt2 = get(sub[0], 12)
            print("   sub", s2, "%.2fs" % dt2, "segs", b2.count(b"#EXTINF"))
    except Exception as e:
        print(name, "ERR", type(e).__name__, e)
