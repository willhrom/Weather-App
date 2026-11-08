// Water clarity (Kd490) at one spot, from the freshest satellite pass that saw it through the clouds.
// GET /api/clarity?lat=37.76&lon=-122.53 -> {"kd":0.088,"date":"2026-11-06","source":"Aqua MODIS","km":2.1} (or {"none":true})
// Asks several near-real-time feeds for the last 8 days around the spot and keeps the newest clear reading.
const SRC = [
  { name: "NOAA-20 VIIRS", base: "https://coastwatch.noaa.gov/erddap/griddap/noaacwN20VIIRSkd490NSWUSDaily", v: "kd_490", alt: true, res: 0.75 },
  { name: "NOAA-21 VIIRS", base: "https://coastwatch.noaa.gov/erddap/griddap/noaacwN21VIIRSkd490NSWUSDaily", v: "kd_490", alt: true, res: 0.75 },
  { name: "Aqua MODIS", base: "https://coastwatch.pfeg.noaa.gov/erddap/griddap/erdMH1kd4901day_R2022NRT", v: "k490", alt: false, res: 4 },
  { name: "S-NPP VIIRS", base: "https://coastwatch.pfeg.noaa.gov/erddap/griddap/nesdisVHNkd490Daily", v: "kd_490", alt: true, res: 4 },
];
const UA = { "User-Agent": "WeatherApp/1.0 (personal weather app)" };
const D = 0.12; // search box half-width, degrees

async function tget(url, ms) {
  const c = new AbortController(), t = setTimeout(() => c.abort(), ms);
  try { return await fetch(url, { headers: UA, signal: c.signal, cf: { cacheTtl: 3600 } }); } finally { clearTimeout(t); }
}

async function one(s, lat, lon) {
  // latitude can run either way on these grids: try north-to-south first, then the other order
  for (const [a, b] of [[lat + D, lat - D], [lat - D, lat + D]]) {
    const q = `${s.v}[(last-7):1:(last)]${s.alt ? "[(0.0)]" : ""}[(${a.toFixed(3)}):1:(${b.toFixed(3)})][(${(lon - D).toFixed(3)}):1:(${(lon + D).toFixed(3)})]`;
    let r;
    try { r = await tget(`${s.base}.json?${q}`, 15000); } catch (e) { return null; }
    if (r.status === 404 && a > b) continue;           // wrong latitude order (or no data in range)
    if (!r.ok) return null;
    const j = await r.json(), cols = j.table.columnNames, rows = j.table.rows;
    const it = cols.indexOf("time"), ila = cols.indexOf("latitude"), ilo = cols.indexOf("longitude"), iv = cols.indexOf(s.v);
    let best = null;
    for (const row of rows) {
      const v = row[iv];
      if (v == null || !(v > 0) || v > 10) continue;
      const day = String(row[it]).slice(0, 10), dy = (row[ila] - lat) * 111, dx = (row[ilo] - lon) * 111 * Math.cos(lat * Math.PI / 180), km = Math.hypot(dx, dy);
      if (!best || day > best.date || (day === best.date && km < best.km)) best = { kd: Math.round(v * 1000) / 1000, date: day, km: Math.round(km * 10) / 10, source: s.name, res: s.res };
    }
    return best;
  }
  return null;
}

export async function onRequestGet({ request, waitUntil }) {
  const u = new URL(request.url), lat = +u.searchParams.get("lat"), lon = +u.searchParams.get("lon");
  if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) return new Response("bad lat/lon", { status: 400 });
  const rl = (Math.round(lat * 20) / 20).toFixed(2), rn = (Math.round(lon * 20) / 20).toFixed(2);
  const cache = caches.default, key = new Request(`https://clarity.cache/v1/${rl}/${rn}`);
  const hit = await cache.match(key);
  if (hit) return hit;
  const got = (await Promise.all(SRC.map(s => one(s, +rl, +rn).catch(() => null)))).filter(Boolean);
  // newest day wins; on the same day prefer the sharper (750 m) sensor, then the closer reading
  got.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : a.res - b.res || a.km - b.km));
  const body = JSON.stringify(got[0] ? { ...got[0], tried: SRC.length, answered: got.length } : { none: true, tried: SRC.length });
  const res = new Response(body, { headers: {
    "content-type": "application/json", "cache-control": "public, max-age=10800",
    "access-control-allow-origin": "*", "x-content-type-options": "nosniff" } });
  waitUntil(cache.put(key, res.clone()));
  return res;
}
