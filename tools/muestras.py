"""Baja unas pocas teselas de prueba (Caballito) de varios estilos de CARTO, para comparar."""
import os, urllib.request, math
KEY = os.environ["CARTO_KEY"].strip()
STYLES = ["dark_all", "dark_nolabels", "dark_only_labels",
          "rastertiles/voyager", "rastertiles/voyager_nolabels", "rastertiles/voyager_only_labels"]
lat, lon = -34.618, -58.437
os.makedirs("muestras", exist_ok=True)
for z in (15, 16, 17):
    n = 1 << z
    x = int((lon + 180) / 360 * n)
    r = math.radians(lat)
    y = int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)
    for s in STYLES:
        for r2 in ("", "@2x"):
            for dx in (0, 1):
                url = f"https://basemaps.cartocdn.com/{s}/{z}/{x+dx}/{y}{r2}.png?key={KEY}"
                req = urllib.request.Request(url, headers={"User-Agent": "com.jsolaresc.launcher (muestras)"})
                try:
                    data = urllib.request.urlopen(req, timeout=20).read()
                except Exception as e:
                    print("ERROR", s, z, r2, e)
                    continue
                name = f"{s.replace('rastertiles/','').replace('/','_')}_{z}_{dx}{r2.replace('@','_')}.png"
                open(os.path.join("muestras", name), "wb").write(data)
print(sorted(os.listdir("muestras")))
