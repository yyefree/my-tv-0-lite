import urllib.request, json, concurrent.futures, re
p = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sdtvlist.txt"
items = []
for line in open(p, encoding="utf-8", errors="ignore"):
    line = line.strip()
    if not line or line.startswith("#genre#") or line.startswith("#EXT"):
        continue
    if "," in line:
        name, url = line.split(",", 1)
        if url.startswith("http"):
            items.append((name.strip(), url.strip()))
print("total", len(items))

def check(it):
    name, url = it
    h = {"User-Agent": "Mozilla/5.0"}
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=5)
        b = r.read(4096)
        if b"#EXTM3U" not in b:
            return None
        txt = b.decode("utf-8", "ignore")
        subs = [l.strip() for l in txt.splitlines() if l.strip().startswith("http")]
        if not subs:
            return None
        r2 = urllib.request.urlopen(urllib.request.Request(subs[0], headers=h), timeout=5)
        b2 = r2.read(2048)
        if b"#EXTINF" in b2:
            return (name, url)
    except Exception:
        return None
    return None

with concurrent.futures.ThreadPoolExecutor(max_workers=24) as ex:
    ok = [x for x in ex.map(check, items) if x]
print("OK", len(ok))
json.dump([{"name": n, "url": u} for n, u in ok], open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sd_ok.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for n, u in ok[:40]:
    print(n)
