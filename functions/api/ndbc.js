// Relay for NOAA's National Data Buoy Center feeds, which don't allow cross-site requests.
// GET /api/ndbc?f=latest   -> latest observation from every station (text, refreshed ~every 5 min)
// GET /api/ndbc?f=stations -> active stations as "id|lat|lon|name|type" lines (refreshed daily)
const SRC = {
  latest: ["https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt", 300],
  stations: ["https://www.ndbc.noaa.gov/activestations.xml", 86400],
};
export async function onRequestGet({ request, waitUntil }) {
  const f = new URL(request.url).searchParams.get("f");
  if (!SRC[f]) return new Response("unknown feed", { status: 400 });
  const [src, ttl] = SRC[f], cache = caches.default, key = new Request("https://ndbc.cache/" + f);
  const hit = await cache.match(key);
  if (hit) return hit;
  const up = await fetch(src, { headers: { "User-Agent": "WeatherApp/1.0 (personal weather app)" }, cf: { cacheTtl: ttl } });
  if (!up.ok) return new Response("upstream " + up.status, { status: 502 });
  let body = await up.text();
  if (f === "stations") {
    body = [...body.matchAll(/<station id="([^"]+)" lat="([^"]+)" lon="([^"]+)"[^>]*?name="([^"]*)"[^>]*?type="([^"]*)"/g)]
      .map(m => [m[1], m[2], m[3], m[4].replace(/&amp;/g, "&").replace(/\s*\(\d+\)\s*$/, "").trim(), m[5]].join("|")).join("\n");
  }
  const res = new Response(body, { headers: {
    "content-type": "text/plain; charset=utf-8", "cache-control": `public, max-age=${ttl}`,
    "access-control-allow-origin": "*", "x-content-type-options": "nosniff" } });
  waitUntil(cache.put(key, res.clone()));
  return res;
}
