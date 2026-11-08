#!/usr/bin/env python3
"""
Model rain over the Pacific for the weather app's radar map.

Radar can't see far past the coast, so offshore the app shows NOAA's GFS model instead:
composite reflectivity (REFC, dBZ) from the newest 0.25-degree run, every hour from the
run's start to +18 h. Each hour becomes a small set of Web Mercator tiles (z2..z5) over the
eastern Pacific, coloured with the exact NEXRAD N0Q palette the app already decodes, so the
phone smooths and colours them exactly like real radar.

Within ~200 km of the lower-48 coast (where real radar covers it) the model is left blank.
"""
import io, json, math, os, re, sys, time, datetime as dt
from concurrent.futures import ThreadPoolExecutor
import numpy as np, requests, pygrib
from scipy.ndimage import distance_transform_edt
from PIL import Image

BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/gfs/prod/"
OUT = os.path.join(sys.argv[1] if len(sys.argv) > 1 else "dist", "gfs")
PREV_MANIFEST = os.environ.get("PREV_MANIFEST_URL", "")
FORCE = os.environ.get("FORCE") == "1"
HOURS = list(range(0, 19))
W, E, S_, N = -180.0, -100.0, 8.0, 62.0
ZOOMS = range(2, 6)
COAST_KM = 200
MIN_DBZ = 10
S = requests.Session()
S.headers["User-Agent"] = "WeatherApp-GFS/1.0 (personal weather app)"

HERE = os.path.dirname(os.path.abspath(__file__))
PAL = re.search(r'N0Q_PAL="([0-9a-f]+)"', open(os.path.join(HERE, "..", "web", "index.html")).read()).group(1)
PALRGB = np.array([[int(PAL[i * 6:i * 6 + 2], 16), int(PAL[i * 6 + 2:i * 6 + 4], 16), int(PAL[i * 6 + 4:i * 6 + 6], 16)]
                   for i in range(len(PAL) // 6)], np.uint8)


def get(url, **kw):
    for i in range(6):
        try:
            r = S.get(url, timeout=60, **kw)
            if r.status_code in (200, 206):
                return r
            print("HTTP", r.status_code, url.rsplit("/", 1)[-1], r.text[:120].replace("\n", " ") if r.status_code != 206 else "")
            if r.status_code == 404:
                return None
        except requests.RequestException as e:
            print("retry", url, e)
        time.sleep(5 * (i + 1))
    return None


def fname(day, hh, f):
    return f"{BASE}gfs.{day}/{hh}/atmos/gfs.t{hh}z.pgrb2.0p25.f{f:03d}"


def latest_cycle():
    now = dt.datetime.now(dt.timezone.utc)
    for back in range(0, 30, 6):
        t = now - dt.timedelta(hours=back)
        c = t.replace(hour=t.hour // 6 * 6, minute=0, second=0, microsecond=0)
        day, hh = c.strftime("%Y%m%d"), c.strftime("%H")
        r = S.head(fname(day, hh, HOURS[-1]) + ".idx", timeout=30)
        print("cycle", day, hh, r.status_code)
        if r.status_code == 200:
            return c, day, hh
    raise SystemExit("no complete GFS cycle found")


def grab(url, var, lev):
    r = get(url + ".idx")
    if r is None:
        raise RuntimeError("no index for " + url)
    lines = r.text.strip().split("\n")
    for k, ln in enumerate(lines):
        p = ln.split(":")
        if p[3] == var and p[4] == lev:
            start = int(p[1]); end = int(lines[k + 1].split(":")[1]) - 1
            g = get(url, headers={"Range": f"bytes={start}-{end}"})
            if g is None:
                raise RuntimeError("download failed " + url)
            path = f"/tmp/gfs_{var}_{os.getpid()}_{abs(hash(url)) % 10**8}.grb"
            open(path, "wb").write(g.content)
            gr = pygrib.open(path); m = gr.message(1); v = m.values; gr.close(); os.remove(path)
            return np.ma.filled(v, 0).astype(np.float32)   # 721 x 1440, lat 90..-90, lon 0..359.75
    raise RuntimeError(f"{var} {lev} not in {url}")


def subset(a):
    # rows: lat 90 - 0.25*i ; cols: lon 0.25*j (0..360). Our box: lon 180..260, lat 62..8 (+1 cell margin)
    i0, i1 = int((90 - N) / .25) - 1, int((90 - S_) / .25) + 2
    j0, j1 = int((W + 360) / .25) - 1, int((E + 360) / .25) + 2
    return a[i0:i1, j0:j1], 90 - i0 * .25, j0 * .25 - 360


def coast_mask(land):
    """True where we hide the model: within COAST_KM of lower-48 land (radar covers it)."""
    sub, lat0, lon0 = subset(land)
    h, w = sub.shape
    lats = lat0 - np.arange(h) * .25
    lons = lon0 + np.arange(w) * .25
    LA, LO = np.meshgrid(lats, lons, indexing="ij")
    us = (sub > .5) & (LA <= 49.5) & (LO >= -125.5) & (((LO < -106) & (LA >= 31.3)) | ((LO >= -106) & (LA >= 25.5)))
    # distance on the 0.25-degree grid: rows ~27.8 km apart, columns ~27.8 km * cos(lat) (40N used)
    dist = distance_transform_edt(~us, sampling=(27.8, 27.8 * math.cos(math.radians(40))))
    hide = dist < COAST_KM
    return hide


def tile_coords(z):
    n = 2 ** z
    def tx(lon): return int((lon + 180) / 360 * n)
    def ty(lat): return int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return range(tx(W), min(n - 1, tx(E - 1e-6)) + 1), range(ty(N), ty(S_) + 1)


def render(dbz, lat0, lon0, z, x, y):
    n = 2 ** z
    px = (np.arange(256) + .5) / 256
    lon = (x + px) / n * 360 - 180
    my = math.pi * (1 - 2 * (y + px) / n)
    lat = np.degrees(np.arctan(np.sinh(my)))
    fi = (lat0 - lat) / .25            # fractional row
    fj = (lon - lon0) / .25            # fractional col
    h, w = dbz.shape
    FI, FJ = np.meshgrid(fi, fj, indexing="ij")
    ok = (FI >= 0) & (FI <= h - 1.001) & (FJ >= 0) & (FJ <= w - 1.001)
    FI = np.clip(FI, 0, h - 1.001); FJ = np.clip(FJ, 0, w - 1.001)
    i0 = FI.astype(int); j0 = FJ.astype(int); ti = FI - i0; tj = FJ - j0
    v = (dbz[i0, j0] * (1 - ti) * (1 - tj) + dbz[i0 + 1, j0] * ti * (1 - tj) +
         dbz[i0, j0 + 1] * (1 - ti) * tj + dbz[i0 + 1, j0 + 1] * ti * tj)
    idx = np.clip(np.round((v + 32.5) * 2), 1, 199).astype(int)
    on = ok & (v >= MIN_DBZ)
    img = np.zeros((256, 256, 4), np.uint8)
    img[..., :3] = PALRGB[idx]
    img[..., 3] = np.where(on, 255, 0)
    img[~on, :3] = 0
    return img, bool(on.any())


def main():
    cyc, day, hh = latest_cycle()
    cid = cyc.strftime("%Y%m%d%H")
    if PREV_MANIFEST and not FORCE:
        try:
            p = S.get(PREV_MANIFEST, timeout=20).json()
            if p.get("cycle") == cid:
                print("cycle", cid, "already published")
                if os.environ.get("GITHUB_OUTPUT"):
                    open(os.environ["GITHUB_OUTPUT"], "a").write("skip=1\n")
                return
        except Exception as e:
            print("no previous manifest", e)

    land = grab(fname(day, hh, 0), "LAND", "surface")
    hide = coast_mask(land)
    print("coast mask cells:", int(hide.sum()))
    empty = io.BytesIO(); Image.new("RGBA", (256, 256), (0, 0, 0, 0)).save(empty, "PNG", optimize=True); empty = empty.getvalue()

    def one(f):
        try:
            refc = grab(fname(day, hh, f), "REFC", "entire atmosphere")
        except Exception as e:
            print(f"f{f:03d}: skipped ({e})")
            return None
        sub, lat0, lon0 = subset(refc)
        sub = np.where(hide, -30, sub)
        nonempty = 0
        for z in ZOOMS:
            xs, ys = tile_coords(z)
            for x in xs:
                for y in ys:
                    d = os.path.join(OUT, f"f{f:03d}", str(z), str(x)); os.makedirs(d, exist_ok=True)
                    img, any_ = render(sub, lat0, lon0, z, x, y)
                    p = os.path.join(d, f"{y}.png")
                    if any_:
                        Image.fromarray(img, "RGBA").save(p, "PNG", optimize=True); nonempty += 1
                    else:
                        open(p, "wb").write(empty)
        print(f"f{f:03d}: max {float(sub.max()):.0f} dBZ, {nonempty} tiles with rain")
        return f

    with ThreadPoolExecutor(2) as ex:
        done = sorted(h for h in ex.map(one, HOURS) if h is not None)
    if len(done) < 6:
        raise SystemExit(f"only {len(done)} hours built")
    man = {"cycle": cid, "run": cyc.strftime("%Y-%m-%dT%H:%MZ"), "hours": done,
           "bounds": [W, S_, E, N], "maxzoom": max(ZOOMS),
           "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    os.makedirs(OUT, exist_ok=True)
    json.dump(man, open(os.path.join(OUT, "manifest.json"), "w"))
    print(json.dumps(man))


if __name__ == "__main__":
    main()
