# Spatial HUD — web version

The Android HUD rebuilt for the browser, so anyone can try it from a link. It uses the device's own
camera, microphone and sensors. Nothing is uploaded; video and audio are processed on the device.

Same models as the Android app: MediaPipe gesture / hand / face (`.task` files) and the Vosk offline
speech model, copied from `../app/src/main/assets` at build time. The simulation logic (cognitive
load, suit, radiation, Mars clock, drills, gestures, crew roster) is a line-by-line TypeScript port,
checked by the same unit tests (`npm test`).

## Run locally

```bash
cd web
npm install
npm run dev
```

Open http://localhost:3000. Camera and microphone need `localhost` or HTTPS.

## Deploy to Vercel

1. Import the GitHub repo in Vercel.
2. **Root Directory:** `web`.
3. Keep **"Include files outside the root directory in the Build Step"** enabled (the default).
   The build copies the models from `../app/src/main/assets`.
4. Optional environment variable: `NASA_API_KEY` (free at api.nasa.gov) for live space weather.
   Without it, `DEMO_KEY` is used.
5. Deploy. Share the URL (or a QR code of it).

## What works where

| | Phone (Chrome / Safari) | Laptop (Chrome / Edge / Safari / Firefox) |
|---|---|---|
| Camera view + hand gestures | Rear camera | Webcam (mirrored) |
| Eye tracking | No (one camera at a time) | Yes, same webcam |
| Head-turn pages | Motion sensors (iPhone asks once) | Drag the view, or ← → keys |
| Voice | Offline Vosk (~42 MB, loads in the background) | Same |
| GPS / map | Yes | Wi-Fi location if allowed; demo position otherwise |
| Crew link | Simulated crew + other tabs of the same browser (`?room=` code) | Same |

Fallbacks so nobody gets stuck: the **Controls** button (every action as a button, plus a box to type
voice commands) and the keyboard (Space, arrows, A, L, 1–0, X). A guided tour runs on first start.

## Differences from the Android app

- Crew link: browsers cannot use UDP on the local Wi-Fi. The web build links tabs of the same
  browser (open "2nd HUD" from Controls) and adds two simulated crewmates. Real multi-device linking
  needs a hosted realtime service; `src/runtime/crewLink.ts` has the `CrewTransport` interface for it.
- No kiosk lock, step counter only where the browser exposes motion, light sensor not available.
