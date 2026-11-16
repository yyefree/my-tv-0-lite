import json, io
okurls = {c["url"]: c["name"] for c in json.load(open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sd_ok.json", encoding="utf-8"))}
out = []
cur = "其他"
for line in open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\sdtvlist.txt", encoding="utf-8", errors="ignore"):
    line = line.strip()
    if not line:
        continue
    if line.endswith("#genre#"):
        cur = line[:-7].strip().rstrip(",") or "其他"
        continue
    if "," not in line:
        continue
    name, url = line.split(",", 1)
    url = url.strip()
    if url in okurls:
        out.append({"group": cur, "logo": "", "title": okurls[url], "uris": [url]})
json.dump(out, open(r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\my-tv-0\_tool\channels.new.json", "w", encoding="utf-8"), ensure_ascii=False)
groups = {}
for c in out: groups[c["group"]] = groups.get(c["group"], 0) + 1
print("channels =", len(out))
for g, n in groups.items(): print("  %s: %d" % (g, n))

