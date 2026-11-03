"""
Arma calles-amba.sqlite: calles (nombre, velocidad máxima, forma) y radares del AMBA,
desde OpenStreetMap (extracto de Geofabrik). El launcher lo usa sin conexión para:
el nombre de la calle bajo la flecha, la velocidad máxima, los radares y los nombres
de las calles dibujados derechos sobre el mapa.

Uso: python3 tools/calles.py amba.osm.pbf calles-amba.sqlite
"""
import math
import re
import sqlite3
import struct
import sys

import osmium

# Mismo recorte que el mapa del AMBA (sur, oeste, norte, este) con un poco de margen.
S, W, N, E = -35.05, -59.0, -34.30, -57.80
CELL = 0.005  # ~500 m

ROADS = {
    "motorway": 1, "trunk": 1, "motorway_link": 2, "trunk_link": 2,
    "primary": 3, "primary_link": 3, "secondary": 4, "secondary_link": 4,
    "tertiary": 5, "tertiary_link": 5, "unclassified": 6, "residential": 6,
    "living_street": 7, "service": 8,
}


def parse_speed(v):
    if not v:
        return None
    m = re.match(r"^\s*(\d+)", v)
    if not m:
        return None
    n = int(m.group(1))
    return round(n * 1.609) if "mph" in v else n


def cell_of(lat, lon):
    return int(math.floor((lat + 90) / CELL)) * 100000 + int(math.floor((lon + 180) / CELL))


class Handler(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.ways = 0
        self.cams = 0
        self.enforce = []  # relaciones: (kind, limit, [(role, ref)])
        self.need = {}

    def node(self, n):
        t = n.tags
        if t.get("highway") == "speed_camera" and n.location.valid():
            kind = 1 if t.get("enforcement") == "traffic_signals" else 0
            self.db.execute("INSERT INTO cameras VALUES (?,?,?,?)",
                            (n.location.lat, n.location.lon, kind, parse_speed(t.get("maxspeed"))))
            self.cams += 1

    def way(self, w):
        t = w.tags
        kind = ROADS.get(t.get("highway"))
        if kind is None:
            return
        name = t.get("name") or t.get("ref")
        if kind == 8 and not name:
            return
        pts = []
        for nd in w.nodes:
            if nd.location.valid():
                pts.append((nd.location.lat, nd.location.lon))
        if len(pts) < 2:
            return
        blob = b"".join(struct.pack("<ii", round(a * 1e6), round(b * 1e6)) for a, b in pts)
        oneway = 1 if t.get("oneway") in ("yes", "1", "true") or t.get("junction") == "roundabout" else 0
        self.db.execute("INSERT INTO ways VALUES (?,?,?,?,?,?)",
                        (w.id, name, parse_speed(t.get("maxspeed")), kind, oneway, blob))
        cells = set()
        for (a, b), (c, d) in zip(pts, pts[1:]):
            # Celdas que toca cada tramo (muestreo cada ~100 m alcanza con celdas de 500 m).
            steps = max(1, int(max(abs(c - a), abs(d - b)) / 0.001))
            for i in range(steps + 1):
                cells.add(cell_of(a + (c - a) * i / steps, b + (d - b) * i / steps))
        self.db.executemany("INSERT INTO cells VALUES (?,?)", [(x, w.id) for x in cells])
        self.ways += 1

    def relation(self, r):
        t = r.tags
        if t.get("type") != "enforcement":
            return
        e = t.get("enforcement")
        if e in ("maxspeed", "average_speed", "mindistance"):
            kind = 0
        elif e == "traffic_signals":
            kind = 1
        else:
            return
        members = [(m.role, m.ref) for m in r.members if m.type == "n" and m.role in ("device", "from")]
        if members:
            self.enforce.append((kind, parse_speed(t.get("maxspeed")), members))


class NodeLoc(osmium.SimpleHandler):
    def __init__(self, wanted):
        super().__init__()
        self.wanted = wanted
        self.found = {}

    def node(self, n):
        if n.id in self.wanted and n.location.valid():
            self.found[n.id] = (n.location.lat, n.location.lon)


def main(pbf, out):
    db = sqlite3.connect(out)
    db.executescript("""
        DROP TABLE IF EXISTS ways; DROP TABLE IF EXISTS cells; DROP TABLE IF EXISTS cameras;
        CREATE TABLE ways (id INTEGER PRIMARY KEY, name TEXT, maxspeed INTEGER, kind INTEGER, oneway INTEGER, pts BLOB);
        CREATE TABLE cells (cell INTEGER, way INTEGER);
        CREATE TABLE cameras (lat REAL, lon REAL, kind INTEGER, maxspeed INTEGER);
    """)
    h = Handler(db)
    h.apply_file(pbf, locations=True)
    # Radares cargados como relación: la posición es la del nodo "device" (o "from").
    wanted = {ref for _, _, ms in h.enforce for _, ref in ms}
    if wanted:
        nl = NodeLoc(wanted)
        nl.apply_file(pbf)
        for kind, limit, ms in h.enforce:
            for role, ref in ms:
                if ref in nl.found:
                    lat, lon = nl.found[ref]
                    db.execute("INSERT INTO cameras VALUES (?,?,?,?)", (lat, lon, kind, limit))
                    h.cams += 1
                    break
    db.executescript("""
        CREATE INDEX cells_cell ON cells(cell);
        CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT);
    """)
    db.execute("INSERT INTO meta VALUES ('cell', ?)", (str(CELL),))
    db.commit()
    db.execute("VACUUM")
    named = db.execute("SELECT count(*) FROM ways WHERE name IS NOT NULL").fetchone()[0]
    speeds = db.execute("SELECT count(*) FROM ways WHERE maxspeed IS NOT NULL").fetchone()[0]
    print(f"calles: {h.ways} (con nombre {named}, con máxima {speeds}) · radares: {h.cams}")
    db.close()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
