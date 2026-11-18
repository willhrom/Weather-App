#!/usr/bin/env python3
"""
Model rain and clouds for the whole globe, for the weather app's map.

Radar can't see far past the coast and satellite only shows the present, so offshore the app
draws NOAA's GFS model itself: composite reflectivity (REFC, dBZ) and total cloud cover (TCDC, %)
from the newest 0.25-degree run, every hour from the run's start to +18 h.

Each hour becomes Web Mercator *data* tiles for the whole world (z2..z3, 256 px plus a 1 px border so
neighbouring tiles join without a seam) over the whole globe.
They hold numbers, not colours:
  R = reflectivity: (dBZ + 20) * 3, clamped 0..255
  G = cloud cover:  % * 2.55, already faded out within ~150-250 km of the lower-48 coast, where
      satellite covers it
  B = how much of the model's rain to show, 0..255: 0 inside the US radar network's reach, fading
      up to 255 where the nearest radar is too far away to see rain (offshore, and the radar
      gaps over land), so radar and model rain join into one picture
The phone interpolates them smoothly and paints clouds and rain (in the radar colours) itself.
"""
import json, math, os, sys, time, datetime as dt
from concurrent.futures import ThreadPoolExecutor
import numpy as np, requests, pygrib
from scipy.ndimage import distance_transform_edt, gaussian_filter, map_coordinates
from PIL import Image

BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/gfs/prod/"
OUT = os.path.join(sys.argv[1] if len(sys.argv) > 1 else "dist", "gfs")
PREV_MANIFEST = os.environ.get("PREV_MANIFEST_URL", "")
FORCE = os.environ.get("FORCE") == "1"
HOURS = list(range(0, 19))
W, E, S_, N = -180.0, 180.0, -85.06, 85.06   # the whole Web Mercator world
ZOOMS = range(2, 4)   # z3 at 256 px is already finer than the 0.25-degree grid; the phone smooths the rest
FADE_KM = (150, 250)          # clouds: distance from the lower-48 coast
RADAR_KM = (160, 230)         # rain: distance from the nearest lower-48 NEXRAD site (a short hand-over; the app
                              # eases the model in by intensity, so it reads as the radar carrying on)
CAL_KM = (40, 160)            # where radar is trustworthy and the model can be checked against it
CAL_MAX = 10.0                # largest intensity correction, dBZ
MOSAIC = "https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0"
RADAR_BOX = (-126.0, 23.0, -66.0, 50.0)   # where the app's radar mosaic has data at all (W, S, E, N)
EDGE_DEG = 1.8                # the model fades in over this band inside the box edge (the app fades the radar out
                              # over the outer 0.8 degrees of it), so the join is a gradual blend, not a line
NEXRAD_URL = "https://mesonet.agron.iastate.edu/geojson/network/NEXRAD.geojson"
S = requests.Session()
S.headers["User-Agent"] = "WeatherApp-GFS/1.0 (personal weather app)"

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
    """Global grid re-centred on Greenwich (-180..180) with 4 columns wrapped onto each side, so
    interpolation runs straight across the date line. Rows: lat 90 - 0.25*i."""
    g = np.roll(a, 720, axis=1)
    return np.concatenate([g[:, -4:], g, g[:, :4]], axis=1), 90.0, -181.0


def smooth(t):
    """smoothstep: an eased 0..1 ramp, so hand-overs have no visible start or end line"""
    return t * t * (3 - 2 * t)


def coast_weight(land):
    """0 near lower-48 land (radar and satellite cover it), rising to 1 a few hundred km offshore."""
    sub, lat0, lon0 = subset(land)
    h, w = sub.shape
    lats = lat0 - np.arange(h) * .25
    lons = lon0 + np.arange(w) * .25
    LA, LO = np.meshgrid(lats, lons, indexing="ij")
    us = (sub > .5) & (LA <= 49.5) & (LO >= -125.5) & (((LO < -106) & (LA >= 31.3)) | ((LO >= -106) & (LA >= 25.5)))
    # distance on the 0.25-degree grid: rows ~27.8 km apart, columns ~27.8 km * cos(lat) (40N used)
    dist = distance_transform_edt(~us, sampling=(27.8, 27.8 * math.cos(math.radians(40))))
    return smooth(np.clip((dist - FADE_KM[0]) / (FADE_KM[1] - FADE_KM[0]), 0, 1)).astype(np.float32)


def radar_weight(lat0, lon0, shape):
    """0 within reach of a lower-48 NEXRAD radar (what the app's radar mosaic covers), 1 beyond it.
    Also returns the distance to the nearest radar (km) for the calibration."""
    h, w = shape
    lats = lat0 - np.arange(h) * .25
    lons = lon0 + np.arange(w) * .25
    try:
        js = S.get(NEXRAD_URL, timeout=60).json()
        sites = [f["geometry"]["coordinates"][:2] for f in js["features"]]
        sites = [(lo, la) for lo, la in sites if 24 <= la <= 50 and -126 <= lo <= -66]
    except Exception as e:
        print("NEXRAD site list unavailable:", e)
        return None, None
    print("NEXRAD sites:", len(sites))
    dist = np.full(shape, 1e9, np.float32)
    R = RADAR_KM[1] / 111 + .5
    for lo, la in sites:
        i0, i1 = max(0, int((lat0 - (la + R)) / .25)), min(h, int((lat0 - (la - R)) / .25) + 2)
        dl = R / max(.2, math.cos(math.radians(la)))
        j0, j1 = max(0, int((lo - dl - lon0) / .25)), min(w, int((lo + dl - lon0) / .25) + 2)
        LA, LO = np.meshgrid(lats[i0:i1], lons[j0:j1], indexing="ij")
        p1, p2 = np.radians(la), np.radians(LA)
        a = np.sin((p2 - p1) / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(np.radians(LO - lo) / 2) ** 2
        dist[i0:i1, j0:j1] = np.minimum(dist[i0:i1, j0:j1], 12742 * np.arcsin(np.sqrt(a)))
    w = smooth(np.clip((dist - RADAR_KM[0]) / (RADAR_KM[1] - RADAR_KM[0]), 0, 1))
    # the radar mosaic simply stops at the edge of its box, radar in range or not: the model takes over there,
    # rising across the same band the app fades the radar out over, so there is neither a gap nor a hard edge
    LA, LO = np.meshgrid(lats, lons, indexing="ij")
    bw, bs, be, bn = RADAR_BOX
    inside = np.minimum(np.minimum(LO - bw, be - LO), np.minimum(LA - bs, bn - LA)) / EDGE_DEG
    return np.maximum(w, 1 - smooth(np.clip(inside, 0, 1))).astype(np.float32), dist


def radar_now(lat0, lon0, shape):
    """The live US radar mosaic on the model's 0.25-degree grid (dBZ, NaN where it has no data), and its time.
    Each cell takes the 80th percentile of its ~50x50 radar pixels, which is close to how a 28 km model
    cell's reflectivity reads."""
    try:
        meta = S.get(MOSAIC + ".json", timeout=30).json()
        valid = dt.datetime.strptime(meta["meta"]["valid"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
        wld = [float(x) for x in S.get(MOSAIC + ".wld", timeout=30).text.split()]
        im = Image.open(__import__("io").BytesIO(S.get(MOSAIC + ".png", timeout=120).content))
        a = np.asarray(im.convert("P") if im.mode != "P" else im, np.uint8)
    except Exception as e:
        print("radar mosaic unavailable:", e)
        return None, None
    dx, dy, x0, y0 = wld[0], wld[3], wld[4], wld[5]
    # index 0 is "no echo" as well as "no radar": both read as dry here; radar_bias only looks where radar reaches
    dbz = np.where(a > 0, a.astype(np.float32) * .5 - 32.5, -10).astype(np.float32)
    h, w = shape
    out = np.full(shape, np.nan, np.float32)
    lats = lat0 - np.arange(h) * .25
    lons = lon0 + np.arange(w) * .25
    rows = [(i, la) for i, la in enumerate(lats) if 23 <= la <= 50.5]
    cols = [(j, lo) for j, lo in enumerate(lons) if -127 <= lo <= -65]
    for i, la in rows:
        r0, r1 = int((la + .125 - y0) / dy), int((la - .125 - y0) / dy)
        if r1 <= 0 or r0 >= a.shape[0]: continue
        band = dbz[max(0, r0):min(a.shape[0], r1)]
        for j, lo in cols:
            c0, c1 = int((lo - .125 - x0) / dx), int((lo + .125 - x0) / dx)
            if c1 <= 0 or c0 >= a.shape[1]: continue
            blk = band[:, max(0, c0):min(a.shape[1], c1)]
            if blk.size:
                out[i, j] = np.percentile(blk, 80)
    print("radar mosaic", valid.isoformat(), "cells:", int(np.isfinite(out).sum()))
    return out, valid


def radar_bias(model, radar, dist):
    """How much to raise or lower the model's rain just beyond radar so it matches what radar sees just inside.
    Compared where radar is trustworthy (CAL_KM from a site): the average strength of rain in each, separately,
    so a storm the model has a little out of place still counts. Spread smoothly out to sea; 0 where there is
    too little rain in either to judge."""
    ok = np.isfinite(radar) & (dist >= CAL_KM[0]) & (dist <= CAL_KM[1])
    rr = ok & (np.nan_to_num(radar, nan=-99) >= 15)
    mm = ok & (model >= 15)
    sg = 6   # ~1.5 degrees
    def mean_of(v, m):
        num = gaussian_filter(np.where(m, v, 0).astype(np.float32), sg, mode="constant")
        den = gaussian_filter(m.astype(np.float32), sg, mode="constant")
        return num / np.maximum(den, 1e-6), den
    R, dr = mean_of(np.nan_to_num(radar), rr)
    M, dm = mean_of(model, mm)
    conf = np.clip(np.minimum(dr, dm) / .04, 0, 1)        # enough rain in both, nearby
    b = np.clip(R - M, -CAL_MAX, CAL_MAX) * conf
    # carry it out past the radar's reach: a wider, confidence-weighted blur
    num = gaussian_filter(b, 10, mode="constant"); den = gaussian_filter(conf, 10, mode="constant")
    far = np.where(den > .02, num / np.maximum(den, 1e-6), 0) * np.clip(den / .1, 0, 1)
    out = (b + (1 - conf) * far).astype(np.float32)   # b already carries conf: a smooth blend, no contour line
    n = int((conf > .5).sum())
    print(f"calibration: {n} cells judged, bias range {float(out.min()):+.1f}..{float(out.max()):+.1f} dBZ")
    return out


def tile_coords(z):
    n = 2 ** z
    def tx(lon): return int((lon + 180) / 360 * n)
    def ty(lat): return int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return range(tx(W), min(n - 1, tx(E - 1e-6)) + 1), range(max(0, ty(N)), min(n - 1, ty(S_)) + 1)


def render(fields, lat0, lon0, z, x, y):
    """Sample each 0.25-degree field onto a 256 px Mercator tile with cubic interpolation (smooth, no
    straight-edged artefacts along grid lines or tile edges)."""
    n = 2 ** z
    px = (np.arange(-1, 257) + .5) / 256    # 256 px plus a 1 px border copied from the neighbours
    lon = (x + px) / n * 360 - 180
    lat = np.degrees(np.arctan(np.sinh(math.pi * (1 - 2 * (y + px) / n))))
    FI, FJ = np.meshgrid((lat0 - lat) / .25, (lon - lon0) / .25, indexing="ij")
    dbz, cloud, wt = (map_coordinates(a, [FI, FJ], order=3, mode="nearest") for a in fields)
    img = np.empty((258, 258, 4), np.uint8)
    img[..., 0] = np.clip(np.round((dbz + 20) * 3), 0, 255)
    img[..., 1] = np.clip(np.round(cloud * 2.55), 0, 255)
    img[..., 2] = np.clip(np.round(wt * 255), 0, 255)
    img[..., 3] = 255
    return img


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
    cw, lat0, lon0 = coast_weight(land), *subset(land)[1:]
    rw, rdist = radar_weight(lat0, lon0, cw.shape)
    if rw is None:   # no site list: fall back to the coast for rain too
        rw = cw
    # calibrate the model's rain against live radar where both see it, at the forecast hour nearest the radar's time
    bias, f_now = None, None
    if rdist is not None:
        radar, valid = radar_now(lat0, lon0, cw.shape)
        if radar is not None:
            f_now = int(round((valid - cyc).total_seconds() / 3600))
            if HOURS[0] <= f_now <= HOURS[-1]:
                try:
                    m_now = subset(grab(fname(day, hh, f_now), "REFC", "entire atmosphere"))[0]
                    bias = radar_bias(gaussian_filter(np.maximum(m_now, -20), .6, mode="nearest"), radar, rdist)
                except Exception as e:
                    print("calibration skipped:", e)
    print("clouds shown cells:", int((cw > 0).sum()), "rain shown cells:", int((rw > 0).sum()), "of", cw.size)

    def one(f):
        try:
            refc = subset(grab(fname(day, hh, f), "REFC", "entire atmosphere"))[0]
            try:
                tcdc = subset(grab(fname(day, hh, f), "TCDC", "entire atmosphere"))[0]
            except RuntimeError:   # the run's first hour has no cloud average yet: use hour 1
                tcdc = subset(grab(fname(day, hh, f + 1), "TCDC", "entire atmosphere"))[0]
        except Exception as e:
            print(f"f{f:03d}: skipped ({e})")
            return None
        # a light blur takes the blockiness out of the 28 km grid before it's scaled up
        refc = gaussian_filter(np.maximum(refc, -20), .6, mode="nearest")
        if bias is not None:   # strongest near now, fading over the next half day as the weather moves on
            k = math.exp(-max(0, f - f_now) / 12)
            refc = np.where(refc > 0, refc + bias * k, refc)
        tcdc = gaussian_filter(tcdc, .6, mode="nearest") * cw
        for z in ZOOMS:
            xs, ys = tile_coords(z)
            for x in xs:
                d = os.path.join(OUT, "m", f"f{f:03d}", str(z), str(x)); os.makedirs(d, exist_ok=True)
                for y in ys:
                    Image.fromarray(render((refc, tcdc, rw), lat0, lon0, z, x, y), "RGBA").save(os.path.join(d, f"{y}.png"), "PNG", optimize=True)
        print(f"f{f:03d}: max {float(refc.max()):.0f} dBZ, mean cloud {float(tcdc.mean()):.0f}%")
        return f

    with ThreadPoolExecutor(2) as ex:
        done = sorted(h for h in ex.map(one, HOURS) if h is not None)
    if len(done) < 6:
        raise SystemExit(f"only {len(done)} hours built")
    man = {"cycle": cid, "calibrated": bias is not None, "run": cyc.strftime("%Y-%m-%dT%H:%MZ"), "hours": done,
           "bounds": [W, S_, E, N], "maxzoom": max(ZOOMS), "v": 4,
           "enc": {"r": "(dBZ+20)*3", "g": "cloud%*2.55", "b": "show 0..255"},
           "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    os.makedirs(OUT, exist_ok=True)
    json.dump(man, open(os.path.join(OUT, "manifest.json"), "w"))
    print(json.dumps(man))


if __name__ == "__main__":
    main()
