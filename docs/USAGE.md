# Spatial HUD — How to Use

A heads-up display for a phone in a head mount. The rear camera shows the world, and the HUD
draws suit, body, mind and crew data on top of it. You control it with hand gestures and your voice.
Nothing needs to be touched while you wear it.

All suit, radiation and emergency data is simulated for training. Do not use it for real safety
decisions.

---

## 1. Before you start

- Android 7.0 or newer, with a rear camera. A front camera is optional (needed for eye tracking).
- Put the phone in landscape. The app locks itself to landscape.
- Allow all four permissions on first launch:
  - **Camera**: the view and hand gestures.
  - **Microphone**: voice commands (they work offline).
  - **Location**: distance walked, the live map, and crew positions.
  - The app also asks to pin the screen. Allow it. This keeps the HUD from being closed by accident.
- Turn on the phone's **Location / GPS** setting.
- For the crew link, every phone must be on the **same Wi-Fi**, or on one phone's **hotspot**.
  No internet is needed.

## 2. First 5 minutes

1. Open the app. Allow the permissions.
2. Put the phone in the head mount. You see the camera view with a compass at the top.
3. Hold up an **open palm** for about half a second. The dashboard opens.
4. **Turn your head** left and right. Each direction has its own page. Pages hang in the air
   around you.
5. **Look down** to see the Drills page.
6. Say **"emergency one"**. A pressure-leak drill starts and the HUD shows alerts.
7. Give a **thumbs up** to acknowledge the alert.
8. Say **"end all emergencies"** to stop the drill.
9. Make a **fist** and hold it for about 1 second. The dashboard closes.

That is the whole loop: open, look around, act, close.

## 3. The screen with the dashboard closed

| Where | What it shows |
|---|---|
| Top centre | Compass: your heading. White dots are crewmates (red = needs help). Arrows at the edges point to crewmates outside the compass view. Below it: time and phone battery. |
| Left side | Glance strip: **HR** (heart rate), **O₂** (oxygen time left), **RAD** (radiation dose rate, µSv/h), **CREW** (number linked and distance to the nearest), and the load ring (cognitive load, 0–100). |
| Far left and right edges | Light lines that grow and change colour with cognitive load. Red means a radiation storm. |
| Top right | Alert banner, when there is an alert. |
| Bottom right | The gesture currently detected, and a mic level while you speak. |

The left glance strip fades out while the dashboard is open, and comes back when you close it.

## 4. Hand gestures

Hold the gesture steady. Each gesture fires once. Drop your hand before you repeat it.

| Gesture | Hold | Does |
|---|---|---|
| Open palm | 0.6 s | Open the dashboard |
| Open palm (dashboard open) | 1 s | Close the dashboard |
| Fist | 0.9 s | Close the dashboard |
| Open hand, swipe left or right | — | Previous or next page |
| Point (index finger) | 0.7 s | Center lock on/off (the page follows your head) |
| Thumb up | 0.4 s | Acknowledge and silence alerts |
| V sign | 0.6 s | Eye-camera preview on/off |
| Thumb down | 0.9 s | Clear the active drill |
| Pinch (thumb + index), on the Environment page | — | Push or pull to zoom the map. Drag to pan. |

## 5. Voice commands

Speak clearly, one command at a time. Recognition runs on the phone, offline.

| Say | Does |
|---|---|
| "open dashboard" / "close dashboard" | Open or close the dashboard |
| "dashboard" | Quick open; say it again to close |
| "radiation" | Go to the Radiation page |
| "mental", "isolation", "sleep", "vitals" | Go to the Isolation page |
| "distance", "earth", "comms" | Go to the Distance page |
| "gravity", "exercise" | Go to the Gravity page |
| "environment", "suit", "map" | Go to the Environment page |
| "drills" | Go to the Drills page |
| "emergency one" … "emergency ten" | Start a drill |
| "clear drill" | Stop the current drill |
| "end all emergencies" / "all clear" | Stop the drill and silence all alerts |
| "acknowledge" / "copy" / "silence" | Silence current alerts |
| "center" / "center lock" | Center lock on/off |
| "recenter" | Put the current page straight ahead |
| "zoom in" / "zoom out" / "center map" | Map controls (Environment page) |
| "eyes" | Eye-camera preview on/off |
| "calibrate" | Re-measure background noise for the mic |
| "sensitivity up" / "sensitivity down" | Make voice recognition more or less sensitive |

Page commands work only while the dashboard is open.

## 6. Dashboard pages

The pages sit in a ring around you, about 40° apart. Turn your head to move between them, or
swipe. Center lock stops head-turning from changing the page.

| Page | Main cards |
|---|---|
| **Radiation** | Dose rate, sun activity (NASA DONKI), hand steadiness, route to shelter, EVA dose, career limit used |
| **Isolation** (home page) | Cognitive load with a 10-minute forecast, stress, sleep, heart rate, eye camera |
| **Distance** | Time left before you must turn back, oxygen, battery, walk-back time, signal delay to Earth, self-sufficiency card with **crew link status** |
| **Gravity** | Activity at Mars gravity (0.38 g), steps, bone and muscle notes |
| **Environment** | Live map (you, your track, crewmates), air temperature, suit pressure, CO₂, cold exposure, light and noise |
| **Drills** (look down) | Drill list and the checklist for the active drill |

The right column, "mind", is on every page: Mars time, the cognitive-load gauge, the main stress
driver, and status chips (Eyes, Lock, Breathe, Ack).

## 7. Drills

| # | Drill |
|---|---|
| 1 | Pressure leak |
| 2 | O₂ depletion |
| 3 | Solar radiation storm |
| 4 | CO₂ scrubber failure |
| 5 | Low suit power |
| 6 | Acute stress |
| 7 | Dust gale |
| 8 | Dehydration |
| 9 | Sensor dropout (HR and SpO₂ show "--") |
| 10 | Compound decompression (several faults at once) |

During a drill your crewmates see you as **needing help**. Stop it with "clear drill" or
"end all emergencies", or with a thumbs down.

## 8. Crew link (several phones)

Each phone sends its status about once a second to the other phones on the same network: name,
position, heading, heart rate, load and emergency state. The phones then show each other on the
compass, on the map and in the glance strip. If a crewmate needs help, the HUD says so aloud,
with distance and bearing.

**Setup**

1. Put all phones on the same Wi-Fi, or turn on a hotspot on one phone and join it from the
   others.
2. Open the app on every phone. Each phone gets a fixed name such as `Crew-7F3A`.
3. Allow Location on every phone and turn GPS on.
4. Go outside, or stand by a window. GPS indoors is weak or missing.

**Read the status.** Open the dashboard and go to **Distance** → *Self-sufficiency*:

| Text | Meaning |
|---|---|
| `Crew: searching on Wi-Fi` | No other phone heard yet |
| `Crew 2 · nearest 14 m` | Working: 2 crewmates, nearest 14 m away |
| `Crew 1 linked · no GPS on this phone` | Connected, but **this** phone has no position |
| `Crew 1 linked · no GPS from Crew-7F3A` | Connected, but **that** phone has no position |
| `Crew 1 linked · no GPS on either phone` | Connected, no positions at all |
| `Crew 1 linked · allow location on this phone` | Location permission is off |

The line under it is for troubleshooting:
`Crew-7F3A · 192.168.1.23 · sent 120 · self ok · peers 85 · gps ±12 m`

- **sent**: packets this phone has sent.
- **self ok**: this phone hears its own packets, so its network side works.
- **peers**: packets received from other phones. If it stays 0, the network is blocking traffic
  between phones. Use a phone hotspot instead.
- **gps**: accuracy of this phone's position. `gps none` means no position yet.

**Important:** "linked" means the phones are talking. A crewmate appears on the compass and the
map only when **both** phones have a GPS position. Without one, the crewmate is still counted and
emergency alerts still arrive, but with "location unknown".

A crewmate that goes silent for 20 seconds is removed.

## 9. Exiting the app

The HUD blocks Back and Home on purpose. To exit, **tap the top-right corner 5 times within
2 seconds**.

## 10. Troubleshooting

| Problem | Fix |
|---|---|
| Gestures not detected | Keep your hand in the rear camera's view, in good light. Hold the pose still. |
| Voice not working | Check the mic permission. Say "calibrate" in a quiet moment. Try "sensitivity up". |
| Pages change when I don't want them to | Turn on center lock (point, or say "center lock"). |
| Distance and map do not move | Location is off or there is no GPS fix. Go outside. Tap "Enable GPS" on the Environment page if it is shown. |
| Crew stays "searching" | The phones are not on the same network, or the router blocks traffic between devices (common on guest or public Wi-Fi). Use a phone hotspot. |
| Crew "linked" but no dots on the compass or map | One of the phones has no GPS position. The status line says which one. |
| Eye tracking unavailable | The phone cannot run the front and rear cameras at once. Everything else still works. |

## 11. For developers

- Android app: Kotlin, Jetpack Compose, CameraX, MediaPipe (hands and face), Vosk (offline speech).
- Build with Android Studio, or with Gradle 9.3.1 and Android SDK platform 36.1 / build-tools 36.1.
  The repo has no `gradlew` script.
- `debug.keystore` (in the project root) and `local.properties` are not committed. Create a
  debug keystore with alias `androiddebugkey` and password `android`.
- Optional: copy `.env.example` to `.env` and set `NASA_API_KEY` (free at api.nasa.gov) for live
  space-weather data. The default `DEMO_KEY` is heavily rate-limited.
- Crew link uses UDP port **47811**: broadcast, multicast group `239.255.47.11`, and direct
  unicast to known peers.
