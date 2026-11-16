import urllib.request, socket
u = "http://ucdn.sd.chinamobile.com:8089/00/SNM/CHANNEL00000311/index.m3u8"
b = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0"}), timeout=10).read().decode("utf-8","ignore")
sub = [l.strip() for l in b.splitlines() if l.strip().startswith("http")][0]
print("SUB:", sub[:140])
from urllib.parse import urlparse
h = urlparse(sub).hostname
print("HOST:", h, "resolve:", socket.gethostbyname(h) if h else "-")
