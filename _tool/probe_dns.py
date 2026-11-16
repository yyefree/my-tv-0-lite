import socket, urllib.request, time
def all_ips(name):
    ips = []
    try:
        for r in socket.getaddrinfo(name, 8089, proto=socket.IPPROTO_TCP):
            ip = r[4][0]
            if ip not in ips: ips.append(ip)
    except Exception as e:
        print(name, "dns ERR", e)
    return ips
hosts = ["ucdn.sd.chinamobile.com"]
for h in hosts:
    print(h, "->", all_ips(h))
