# Weather

A sleek Android weather app: GPS and saved locations, rain totals, wind, tides, moon phase and animated radar.

## How updates work

- **The app page** (`web/index.html`) is published to Cloudflare Pages at `https://willhrom-weather.pages.dev` on every push. The app loads it from there each time it opens, so changes show up with no reinstall. The last version that loaded is saved on the phone, so it still opens offline.
- **The Android shell** (`android/`) rarely changes. When it does, GitHub builds a new APK at
  `https://github.com/willhrom/Weather-App/releases/download/android/weather.apk`
  (private repo — sign in to github.com in Chrome on the phone first).

### One-time Cloudflare setup

1. Cloudflare dashboard → **My Profile → API Tokens → Create Token → Create Custom Token**. Permission: **Account → Cloudflare Pages → Edit**. Create it and copy the token.
2. Copy your **Account ID** (Workers & Pages overview, right side).
3. In this repo: **Settings → Secrets and variables → Actions → New repository secret**. Add `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
4. **Actions → Publish app page → Run workflow**.

## Install on your phone

1. Signed in to GitHub, open that link in Chrome on the phone. It downloads `weather.apk`.
2. Tap the download. Android asks to allow installs from Chrome the first time: tap **Settings → Allow from this source**, then go back and tap **Install**.
3. Open **Weather** and tap **Allow** when it asks for your location (choose "While using the app").

## Using it

- **☰ (top right)** or tapping the place name opens Locations: search any city, ZIP or airport code, tap a result to add it, tap × to remove. The chips under the name switch between places with one tap.
- **Follow my location** uses the phone's GPS. Turn it off to only use your saved places.
- Units (°F/°C, mph/km/h/knots, in/mm) are in the same panel.
- Pull down from the top, or tap ↻, to refresh. It also refreshes itself every 10 minutes.
- Map: tap it for full screen, then pinch and drag. The layers button switches between radar, satellite, MRMS rain totals, NWS alerts and aviation layers (METARs, G-AIRMETs, SIGMETs, CWAs, PIREPs). Tap an airport or shaded area for details. Back closes it.

## Updating later

Change `web/index.html` and push. Cloudflare publishes it in about a minute and the app picks it up next time it opens. Only changes under `android/` need a reinstall.

## Where the data comes from

All free, no keys: Open-Meteo (forecast, rain totals), NWS (alerts), AviationWeather.gov (METAR/TAF, G-AIRMET, SIGMET, CWA, PIREP), NOAA (tides), Iowa State Mesonet (NEXRAD radar, GOES satellite, MRMS rain totals; RainViewer outside the US), OpenFreeMap dark map. Moon is calculated on the phone.

The app's signing key is stored only in the `ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD` repository secrets, never in the code.
