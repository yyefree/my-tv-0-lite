import urllib.request, re
def get(u, timeout=8, headers=None):
    req = urllib.request.Request(u, headers=headers or {"User-Agent": "Mozilla/5.0"})
    r = urllib.request.urlopen(req, timeout=timeout)
    return r.status, r.read()
base = "http://ucdn.sd.chinamobile.com:8089/00/SNM/CHANNEL00000301/index.m3u8"
s, b = get(base)
txt = b.decode("utf-8", "ignore")
sub = [l.strip() for l in txt.splitlines() if l.strip().startswith("http")]
print("master ok, sub count =", len(sub))
if sub:
    s2, b2 = get(sub[0], 10)
    t2 = b2.decode("utf-8", "ignore")
    print("sub status", s2, "head:", t2.splitlines()[0][:40])
    segs = [l.strip() for l in t2.splitlines() if l.strip().startswith("http")]
    print("segments:", len(segs))
    if segs:
        s3, b3 = get(segs[0], 10)
        print("first .ts =>", s3, "bytes =", len(b3))
