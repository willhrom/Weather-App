# Weather

A sleek Android weather app: GPS and saved locations, rain totals, wind, tides, moon phase and animated radar.

## Download link

GitHub builds the app every time `web/` or `android/` changes (Actions tab, about 5 minutes). The newest build is always at:

`https://github.com/willhrom/weather-app/releases/download/android/weather.apk`

This repo is private, so sign in to github.com in Chrome on your phone before opening that link (or open **Releases** in the repo and tap `weather.apk`).

## 2. Install on your phone

1. Signed in to GitHub, open that link in Chrome on the phone. It downloads `weather.apk`.
2. Tap the download. Android asks to allow installs from Chrome the first time: tap **Settings → Allow from this source**, then go back and tap **Install**.
3. Open **Weather** and tap **Allow** when it asks for your location (choose "While using the app").

## Using it

- **Location pin (top right)** opens Locations: search any city or ZIP, tap a result to add it, tap × to remove. The chips under the name switch between places with one tap.
- **Follow my location** uses the phone's GPS. Turn it off to only use your saved places.
- Units (°F/°C, mph/km/h/knots, in/mm) are in the same panel.
- Pull down from the top, or tap ↻, to refresh. It also refreshes itself every 10 minutes.
- Radar: tap the corner button for full screen, then pinch and drag. Back closes it.

## Updating later

Replace `web/index.html` in the repository with a new version. GitHub rebuilds automatically, and opening the same link (or **Get the latest version of the app** in the Locations panel) installs the update over the old one, keeping your places and settings.

## Where the data comes from

All free, no keys needed: Open-Meteo (forecast, rain totals, season total), NOAA (tides from the nearest station within ~55 miles; elsewhere modelled sea level), NEXRAD radar via Iowa State Mesonet in the US and RainViewer elsewhere, CARTO dark map. Moon phase, rise and set are calculated on the phone.

`android/app/weather.keystore` just lets each build install over the last one. It isn't a secret.
