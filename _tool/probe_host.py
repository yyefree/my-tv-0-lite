import socket, time
for ip in ["39.135.159.114", "39.134.144.6"]:
    t0 = time.time()
    try:
        s = socket.create_connection((ip, 8089), timeout=8)
        req = ("GET /00/SNM/CHANNEL00000311/index.m3u8 HTTP/1.1\r\n"
               "Host: ucdn.sd.chinamobile.com\r\n"
               "User-Agent: Mozilla/5.0\r\n"
               "Connection: close\r\n\r\n")
        s.sendall(req.encode())
        data = s.recv(300)
        s.close()
        print(ip, "%.2fs" % (time.time()-t0), data.split(b"\r\n")[0], "| body:", data[-60:].decode("utf-8","ignore").strip()[:40])
    except Exception as e:
        print(ip, "ERR", "%.2fs" % (time.time()-t0), type(e).__name__, str(e)[:70])
