import urllib.request, time
tests = [
 "https://epg.112114.xyz/?ch=CCTV1",
 "https://epg.112114.xyz/",
 "https://raw.githubusercontent.com/iptv-org/epg/master/README.md",
]
for u in tests:
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0"}), timeout=10)
        b = r.read(300)
        print(u, r.status, "%.1fs" % (time.time()-t0), b[:80].decode("utf-8","ignore").replace("\n"," "))
    except Exception as e:
        print(u, "ERR", "%.1fs" % (time.time()-t0), type(e).__name__, str(e)[:70])
