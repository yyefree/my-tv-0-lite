import zipfile
p = r"C:\Users\efree\Doubao\chats\2026-11-16\new-chat\tv-apk\my-tv-0-2.0.0-lite.apk"
z = zipfile.ZipFile(p)
for n in z.namelist():
    if "channels" in n or n.endswith(".txt"):
        i = z.getinfo(n)
        print(n, "size=", i.file_size, "compressed=", i.compress_size)
