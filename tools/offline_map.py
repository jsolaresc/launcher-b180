"""
Arma el mapa del AMBA para cargar en el estéreo por USB (mapa-amba.sqlite).

Mismo formato que la base de osmdroid (tabla tiles: key, provider, tile, expires),
mismas zonas, zooms y estilos que "Descargar AMBA" del launcher.
Lo corre GitHub Actions (workflow "Mapa sin conexión").
"""
import os
import sqlite3
import sys
import time
import math
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ZOOM_MIN, ZOOM_MAX = 10, 16

# (norte, este, sur, oeste), igual que SettingsActivity.AMBA
AMBA = [
    (-34.52, -58.33, -34.71, -58.54),  # CABA
    (-34.36, -58.44, -34.54, -58.95),  # Zona Norte: Vicente López, San Isidro, Tigre, Escobar, Pilar
    (-34.54, -58.50, -34.80, -58.95),  # Zona Oeste: Morón, La Matanza, Moreno, Gral. Rodríguez
    (-34.64, -57.88, -35.00, -58.50),  # Zona Sur: Avellaneda, Quilmes, Ezeiza, La Plata
]

# Nombres iguales a NavMap.DARK_TILES / LIGHT_TILES.
SOURCES = {
    "CartoDark2": "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
    "CartoVoyager2": "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
}

# Clave gratuita de CARTO: sin ella las teselas traen la marca "API KEY REQUIRED".
KEY = (os.environ.get("CARTO_KEY") or (open("carto.key").read() if os.path.exists("carto.key") else "")).strip()

UA = "com.jsolaresc.launcher (mapa sin conexión)"


def tile_x(lon, z):
    return int((lon + 180) / 360 * (1 << z))


def tile_y(lat, z):
    r = math.radians(lat)
    return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * (1 << z))


def tiles():
    seen = set()
    for z in range(ZOOM_MIN, ZOOM_MAX + 1):
        for n, e, s, w in AMBA:
            for x in range(tile_x(w, z), tile_x(e, z) + 1):
                for y in range(tile_y(n, z), tile_y(s, z) + 1):
                    if (z, x, y) not in seen:
                        seen.add((z, x, y))
                        yield z, x, y


def key(z, x, y):
    # SqlTileWriter.getIndex: ((z << z) + x << z) + y
    return (((z << z) + x) << z) + y


def fetch(url):
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.read()
        except Exception:
            time.sleep(2 * (attempt + 1))
    return None


def main(out):
    if not KEY:
        sys.exit("Falta la clave de CARTO (carto.key)")
    db = sqlite3.connect(out)
    db.execute(
        "CREATE TABLE IF NOT EXISTS tiles (key INTEGER, provider TEXT, tile BLOB, expires INTEGER, "
        "PRIMARY KEY (key, provider))"
    )
    jobs = [(name, url, z, x, y) for name, url in SOURCES.items() for z, x, y in tiles()]
    print(f"{len(jobs)} teselas", flush=True)

    def work(job):
        name, url, z, x, y = job
        sub = "abcd"[(x + y) % 4]
        u = url.format(s=sub, z=z, x=x, y=y)
        return job, fetch(u + "?key=" + KEY if KEY else u)

    done = failed = 0
    with ThreadPoolExecutor(8) as pool:
        for (name, _, z, x, y), data in pool.map(work, jobs):
            if data is None:
                failed += 1
                continue
            db.execute(
                "INSERT OR REPLACE INTO tiles (key, provider, tile, expires) VALUES (?, ?, ?, NULL)",
                (key(z, x, y), name, data),
            )
            done += 1
            if done % 2000 == 0:
                db.commit()
                print(f"{done}/{len(jobs)}", flush=True)
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"Listo: {done} teselas, {failed} con error", flush=True)
    if failed > len(jobs) * 0.01:
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "mapa-amba.sqlite")
