"""Build a tiny local-only smoke-test pack; it is not emergency mapping data."""
import hashlib
import json
import math
import sqlite3
import struct
import subprocess
import sys
import zlib
import zipfile
from pathlib import Path

root = Path(__file__).parent / "mumbai-pack"
version = "2026.10-test"
device_pack = f"/data/user/0/com.resqnet.app/files/offline-packs/mmr-{version}"

def png_tile():
    width = height = 256
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            line = (x % 32 == 0) or (y % 32 == 0)
            raw.extend((35, 67, 90, 255) if line else (222, 239, 245, 255))
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")

def tile_xy(lon, lat, z):
    scale = 2 ** z
    x = int((lon + 180.0) / 360.0 * scale)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * scale)
    return x, y

mbtiles = root / "basemap.mbtiles"
if mbtiles.exists(): mbtiles.unlink()
conn = sqlite3.connect(mbtiles)
conn.executescript("create table metadata (name text, value text); create table tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);")
metadata = {"name": "ResQNet Mumbai smoke map", "format": "png", "type": "baselayer", "version": "1", "minzoom": "0", "maxzoom": "10", "bounds": "72.70,18.85,73.10,19.60", "center": "72.9,19.2,10"}
conn.executemany("insert into metadata values (?, ?)", metadata.items())
tile = png_tile()
for z in range(11):
    x, y = tile_xy(72.9, 19.2, z)
    conn.execute("insert into tiles values (?, ?, ?, ?)", (z, x, (2 ** z - 1) - y, tile))
conn.commit(); conn.close()

style = {
    "version": 8,
    "name": "ResQNet Mumbai smoke style",
    "sources": {"basemap": {"type": "raster", "tiles": [f"pmtiles://file://{device_pack}/basemap.pmtiles"], "tileSize": 256}},
    "layers": [{"id": "basemap", "type": "raster", "source": "basemap"}],
}
(root / "style.json").write_text(json.dumps(style))
shelters = {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"id": "test-mumbai", "name": "Mumbai test destination", "address": "Test fixture only", "notes": "Not an emergency shelter", "source": "ResQNet test fixture", "verificationStatus": "unverified", "lastVerified": None}, "geometry": {"type": "Point", "coordinates": [72.8777, 19.0760]}}]}
(root / "shelters.geojson").write_text(json.dumps(shelters))

converter = Path.home() / "AppData/Roaming/Python/Python314/Scripts/pmtiles-convert"
subprocess.run([sys.executable, str(converter), str(mbtiles), str(root / "basemap.pmtiles"), "--overwrite"], check=True)

files = ["valhalla_tiles.tar", "valhalla.json", "basemap.mbtiles", "basemap.pmtiles", "style.json", "shelters.geojson"]
manifest = {"regionId": "mmr", "version": version, "minimumAppVersionCode": 1, "files": []}
for name in files:
    content = (root / name).read_bytes()
    manifest["files"].append({"path": name, "sha256": hashlib.sha256(content).hexdigest(), "sizeBytes": len(content)})
(root / "region-pack.json").write_text(json.dumps(manifest, separators=(",", ":")))
archive = root.parent / "resqnet-mumbai-smoke-pack.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zip_file:
    for name in files + ["region-pack.json"]:
        zip_file.write(root / name, name)
print(archive)
