#!/usr/bin/env python3
"""
Build GTG turbulence (EDR) overlays for the weather app.

Source: NOAA/NCEP DAFS Graphical Turbulence Guidance v4 (HRRR-based, 3 km CONUS),
        https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod/
Field:  EDPARM - combined eddy dissipation rate, m^(2/3) s^-1, 51 levels every 1,000 ft MSL.

For each flight level / forecast hour we fetch only that GRIB message (byte range from the
.idx file), reproject it to Web Mercator over CONUS and write a PNG holding EDR x 400 as a
16-bit number:  red = high byte, green = low byte, blue = 255 where the model has data.
The app colours it on the device (light / moderate / severe / extreme per aircraft class).
"""
import io, json, math, os, re, sys, time, datetime as dt
from concurrent.futures import ThreadPoolExecutor
import numpy as np, requests, pygrib
from PIL import Image
from pyproj import Proj

BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod/"
OUT = sys.argv[1] if len(sys.argv) > 1 else "dist"
PREV_MANIFEST = os.environ.get("PREV_MANIFEST_URL", "")
LEVELS_FT = list(range(3000, 45001, 3000))            # FL030 .. FL450
HOURS = list(range(0, 19, 3))                          # f000 .. f018 every 3 h
BOUNDS = (-126.0, 22.0, -66.0, 51.0)                   # W, S, E, N (Web Mercator image)
WIDTH = 1600
SCALE = 400                                            # EDR stored as EDR*400 in 16 bits
FLOOR = 0.08                                           # below any aircraft class's light threshold: stored as 0 (smaller files)
S = requests.Session()
S.headers["User-Agent"] = "WeatherApp-GTG/1.0 (personal weather app)"


def get(url, **kw):
    for i in range(4):
        try:
            r = S.get(url, timeout=60, **kw)
            if r.status_code == 200 or r.status_code == 206:
                return r
            if r.status_code == 404:
                return None
        except requests.RequestException as e:
            print("retry", url, e)
        time.sleep(3 * (i + 1))
    return None


def listing(url):
    r = get(url)
    return re.findall(r'href="([^"?/][^"]*)"', r.text) if r is not None else []


def find_latest_cycle():
    """Returns (date, hh, folder_url) of the newest cycle with f018 published."""
    days = sorted({m for h in listing(BASE) for m in re.findall(r"dafs\.(\d{8})/?$", h)}, reverse=True)
    print("days on server:", days[:4])
    for day in days[:3]:
        durl = f"{BASE}dafs.{day}/"
        entries = listing(durl)
        cands = []  # (hh, folder)
        # files directly in the day folder
        for e in entries:
            m = re.match(r"dafs\.t(\d\d)z\.gtg\.3km\.conus\.f018\.grib2$", e)
            if m: cands.append((m.group(1), durl))
        # or one folder per cycle (e.g. 00/ 06/ ...), possibly with a further subfolder
        for e in entries:
            if re.fullmatch(r"\d\d/?", e):
                sub = durl + e.rstrip("/") + "/"
                for f in listing(sub):
                    m = re.match(r"dafs\.t(\d\d)z\.gtg\.3km\.conus\.f018\.grib2$", f)
                    if m: cands.append((m.group(1), sub))
                    elif re.fullmatch(r"[a-z]+/?", f):
                        sub2 = sub + f.rstrip("/") + "/"
                        for f2 in listing(sub2):
                            m2 = re.match(r"dafs\.t(\d\d)z\.gtg\.3km\.conus\.f018\.grib2$", f2)
                            if m2: cands.append((m2.group(1), sub2))
        if cands:
            hh, folder = sorted(cands)[-1]
            return day, hh, folder
        print("no complete GTG cycle in", durl, "entries:", entries[:15])
    return None


def idx_ranges(url):
    r = get(url + ".idx")
    if r is None:
        return {}
    lines = [l.split(":") for l in r.text.strip().splitlines()]
    out = {}
    for i, p in enumerate(lines):
        if len(p) < 6 or p[3] != "EDPARM":
            continue
        m = re.match(r"(\d+) m above mean sea level", p[4])
        if not m:
            continue
        start = int(p[1])
        end = int(lines[i + 1][1]) - 1 if i + 1 < len(lines) else None
        out[int(m.group(1))] = (start, end)
    return out


def merc_y(lat):
    return math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def target_grid():
    w, s, e, n = BOUNDS
    h = int(round(WIDTH * (merc_y(n) - merc_y(s)) / math.radians(e - w)))
    lon = np.linspace(w, e, WIDTH, endpoint=False) + (e - w) / WIDTH / 2
    ys = np.linspace(merc_y(n), merc_y(s), h, endpoint=False) - (merc_y(n) - merc_y(s)) / h / 2
    lat = np.degrees(2 * np.arctan(np.exp(ys)) - math.pi / 2)
    return np.meshgrid(lon, lat), h


def main():
    cyc = find_latest_cycle()
    if not cyc:
        sys.exit("No GTG cycle found on NOMADS")
    day, hh, folder = cyc
    cycle_id = f"{day}{hh}"
    print("latest cycle:", cycle_id, folder)
    if PREV_MANIFEST:
        prev = get(PREV_MANIFEST)
        if prev is not None:
            try:
                if prev.json().get("cycle") == cycle_id and os.environ.get("FORCE") != "1":
                    print("cycle already published, nothing to do")
                    open(os.environ.get("GITHUB_OUTPUT", os.devnull), "a").write("skip=1\n")
                    return
            except ValueError:
                pass

    (LON, LAT), H = target_grid()
    grid = {}

    def reprojection(g):
        p = Proj(g.projparams)
        la1, lo1 = g["latitudeOfFirstGridPointInDegrees"], g["longitudeOfFirstGridPointInDegrees"]
        x0, y0 = p(lo1 if lo1 <= 180 else lo1 - 360, la1)
        dx, dy, nx, ny = g["DxInMetres"], g["DyInMetres"], g["Nx"], g["Ny"]
        jpos = g["jScansPositively"] == 1
        X, Y = p(LON, LAT)
        ii = np.rint((X - x0) / dx).astype(int)
        jj = np.rint(((Y - y0) / dy) if jpos else ((y0 - Y) / dy)).astype(int)
        ok = (ii >= 0) & (ii < nx) & (jj >= 0) & (jj < ny)
        print("grid", nx, ny, dx, "jpos", jpos)
        return np.clip(jj, 0, ny - 1), np.clip(ii, 0, nx - 1), ok

    def one(job):
        fh, ft, url, a, b = job
        r = get(url, headers={"Range": f"bytes={a}-{'' if b is None else b}"})
        if r is None:
            return 0
        g = pygrib.fromstring(r.content)
        key = (g["Nx"], g["Ny"], g["DxInMetres"], g["latitudeOfFirstGridPointInDegrees"])
        if key not in grid:
            grid[key] = reprojection(g)
        jj, ii, ok = grid[key]
        vals = g.values
        data = np.ma.filled(vals.astype("float32"), np.nan) if np.ma.isMaskedArray(vals) else vals.astype("float32")
        v = data[jj, ii]
        valid = ok & np.isfinite(v) & (v >= 0)
        q = np.clip(np.rint(np.where(valid & (v >= FLOOR), v, 0) * SCALE), 0, 65535).astype("uint16")
        rgb = np.dstack([(q >> 8).astype("uint8"), (q & 255).astype("uint8"), np.where(valid, 255, 0).astype("uint8")])
        d = os.path.join(OUT, "gtg", f"f{fh:03d}")
        os.makedirs(d, exist_ok=True)
        Image.fromarray(rgb, "RGB").save(os.path.join(d, f"FL{ft // 100:03d}.png"), optimize=True)
        return 1

    times = []
    t0 = time.time()
    for fh in HOURS:
        url = f"{folder}dafs.t{hh}z.gtg.3km.conus.f{fh:03d}.grib2"
        ranges = idx_ranges(url)
        if not ranges:
            print("missing", url)
            continue
        levels_m = sorted(ranges)
        jobs = []
        for ft in LEVELS_FT:
            lev = min(levels_m, key=lambda m: abs(m - ft * 0.3048))
            jobs.append((fh, ft, url) + ranges[lev])
        if not grid:  # first message sets up the reprojection before going parallel
            wrote = one(jobs[0]); jobs = jobs[1:]
        else:
            wrote = 0
        with ThreadPoolExecutor(4) as ex:
            wrote += sum(ex.map(one, jobs))
        if wrote:
            valid_t = dt.datetime.strptime(cycle_id, "%Y%m%d%H").replace(tzinfo=dt.timezone.utc) + dt.timedelta(hours=fh)
            times.append({"f": f"f{fh:03d}", "valid": valid_t.strftime("%Y-%m-%dT%H:%M:%SZ")})
            print(f"f{fh:03d}: {wrote} levels ({time.time() - t0:.0f}s)")
    if not times:
        sys.exit("No GTG fields written")
    manifest = {"cycle": cycle_id, "source": "NOAA/NCEP DAFS GTG v4 (EDPARM)", "bounds": list(BOUNDS),
                "levels": [ft // 100 for ft in LEVELS_FT], "times": times, "scale": SCALE, "encoding": "edr16-rg",
                "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    os.makedirs(os.path.join(OUT, "gtg"), exist_ok=True)
    json.dump(manifest, open(os.path.join(OUT, "gtg", "manifest.json"), "w"))
    print("done:", len(times), "times")


if __name__ == "__main__":
    main()
