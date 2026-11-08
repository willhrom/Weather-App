#!/usr/bin/env python3
"""
Water clarity (Kd490) composite for US coastal waters, for the app's underwater-visibility estimate.

Sources (newest reading wins, per pixel, over the last 7 days):
  - NOAA-20 / NOAA-21 VIIRS Kd490, near real time, 750 m, daily                  [CoastWatch central ERDDAP]
The app first asks /api/clarity (functions/api/clarity.js) for the freshest single-spot reading, including
Aqua MODIS and S-NPP VIIRS; this composite is the fallback.
Clouds leave gaps, so each pixel keeps its most recent cloud-free value.
Falls back to the gap-filled DINEOF product (2 km, ~10 days behind) where nothing recent exists.

Output (in <out>/clarity/):  kd.png  -> kd490*1000 as 16 bits (red=high, green=low), blue = 255 - 30*age_days (0 = no data)
                             clarity.json -> grid geometry + dates
"""
import io, json, os, sys, time, tempfile, datetime as dt
import numpy as np, requests, xarray as xr
from PIL import Image

E = "https://coastwatch.noaa.gov/erddap/griddap/"
W = "https://coastwatch.pfeg.noaa.gov/erddap/griddap/"
OUT = os.path.join(sys.argv[1] if len(sys.argv) > 1 else "dist", "clarity")
LAT = (24.0, 50.0); LON = (-126.0, -66.0)
STEP = 0.03                       # output grid (~3 km)
# (server, dataset, variable, has altitude dim, stride for ~3 km)
# (the West Coast ERDDAP's fresher MODIS/VIIRS feeds refuse GitHub's build machines, so the app asks them
#  per spot through /api/clarity instead; this composite is the fallback)
NRT = [(E, "noaacwN20VIIRSkd490NSWUSDaily", "kd_490", True, 4),
       (E, "noaacwN21VIIRSkd490NSWUSDaily", "kd_490", True, 4)]
GAPFILL = "noaacwNPPN20S3AkdSCIDINEOF2kmDaily"
S = requests.Session(); S.headers["User-Agent"] = "WeatherApp/1.0 (personal weather app)"
lats = np.arange(LAT[1], LAT[0] - 1e-9, -STEP)        # north -> south rows
lons = np.arange(LON[0], LON[1] + 1e-9, STEP)
kd = np.full((len(lats), len(lons)), np.nan, "float32"); age = np.full(kd.shape, 255, "int16")
today = dt.datetime.now(dt.timezone.utc).date()


def get(url, tries=3):
    for i in range(tries):
        try:
            r = S.get(url, timeout=240)
            if r.status_code == 200: return r
            print("HTTP", r.status_code, url[:160], r.text[:200])
            if r.status_code in (400, 404): return None
        except requests.RequestException as e:
            print("retry", e)
        time.sleep(5 * (i + 1))
    return None


def axis(ds, name, srv=E):
    r = get(f"{srv}{ds}.json?{name}")
    return None if r is None else np.array([row[0] for row in r.json()["table"]["rows"]])


def grab(ds, t, stride, srv=E, var="kd_490", alt=True):
    """Kd490 for one time over the box, regridded to the output grid (nearest)."""
    la = axis(ds, "latitude", srv)
    if la is None: return None
    desc = la[0] > la[-1]
    lat_q = f"[({LAT[1]}):{stride}:({LAT[0]})]" if desc else f"[({LAT[0]}):{stride}:({LAT[1]})]"
    r = get(f"{srv}{ds}.nc?{var}[({t})]{'[(0.0)]' if alt else ''}{lat_q}[({LON[0]}):{stride}:({LON[1]})]")
    if r is None: return None
    with tempfile.NamedTemporaryFile(suffix=".nc", delete=False) as f: f.write(r.content); path = f.name
    d = xr.open_dataset(path)[var].squeeze().load(); os.unlink(path)
    v = d.values.astype("float32"); dla = d.latitude.values; dlo = d.longitude.values
    ii = np.clip(np.searchsorted(dla if not desc else -dla, lats if not desc else -lats), 0, len(dla) - 1)
    jj = np.clip(np.searchsorted(dlo, lons), 0, len(dlo) - 1)
    g = v[np.ix_(ii, jj)]
    # only accept grid points within one source step
    ok = (np.abs(dla[ii][:, None] - lats[:, None]) < STEP) & (np.abs(dlo[jj][None, :] - lons[None, :]) < STEP)
    g[~ok] = np.nan
    return g


def main():
    used = []
    # every source's last week of days, applied newest day first: a pixel keeps its most recent clear reading
    jobs = []
    for srv, ds, var, alt, stride in NRT:
        times = axis(ds, "time", srv)
        if times is None: print("unavailable:", ds); continue
        print(ds, "newest", times[-1][:10])
        for t in times[-7:]:
            day = dt.date.fromisoformat(t[:10])
            if (today - day).days <= 7: jobs.append((day, srv, ds, var, alt, stride, t))
    jobs.sort(key=lambda j: j[0], reverse=True)
    for day, srv, ds, var, alt, stride, t in jobs:
        a = (today - day).days
        g = grab(ds, t, stride, srv, var, alt)
        if g is None: continue
        new = np.isfinite(g) & (g > 0) & (a < age)
        kd[new] = g[new]; age[new] = a
        used.append(f"{ds} {t[:10]} +{int(new.sum())}")
        print(used[-1])
    times = axis(GAPFILL, "time")
    if times is not None:
        t = times[-1]; a = (today - dt.date.fromisoformat(t[:10])).days
        g = grab(GAPFILL, t, 1)
        if g is not None:
            new = np.isfinite(g) & (g > 0) & ~np.isfinite(kd)
            kd[new] = g[new]; age[new] = min(a, 8)
            used.append(f"{GAPFILL} {t[:10]} +{int(new.sum())}"); print(used[-1])
    # keep coastal water only (within ~0.45 deg of land) - that's where people swim and dive, and it keeps the file small
    land = ~np.isfinite(kd); near = land.copy()
    for _ in range(15):
        n2 = near.copy(); n2[1:] |= near[:-1]; n2[:-1] |= near[1:]; n2[:, 1:] |= near[:, :-1]; n2[:, :-1] |= near[:, 1:]; near = n2
    kd[~near] = np.nan
    valid = np.isfinite(kd)
    if not valid.any(): sys.exit("no clarity data")
    q = np.clip(np.rint(np.where(valid, kd, 0) * 1000), 0, 65535).astype("uint16")
    b = np.where(valid, np.clip(255 - 30 * np.clip(age, 0, 8), 1, 255), 0).astype("uint8")
    os.makedirs(OUT, exist_ok=True)
    Image.fromarray(np.dstack([(q >> 8).astype("uint8"), (q & 255).astype("uint8"), b]), "RGB").save(os.path.join(OUT, "kd.png"), optimize=True)
    json.dump({"lat0": float(lats[0]), "lon0": float(lons[0]), "step": STEP, "rows": len(lats), "cols": len(lons), "scale": 1000,
               "date": today.isoformat(), "newest": max([u.split()[1] for u in used], default=None), "sources": used,
               "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}, open(os.path.join(OUT, "clarity.json"), "w"))
    print("valid pixels", int(valid.sum()))


if __name__ == "__main__":
    main()
