# Solution Materials, Licenses and AI Disclosure

## 1. Non-NASA open-source materials

Every library, model and asset listed here is from a public source and was released before the event.

### Bundled in the app

| Material | Version | Used for | License |
|---|---|---|---|
| Kotlin standard library, kotlinx.coroutines (JetBrains) | 2.2.10 / 1.10.2 | Language, background work | Apache 2.0 |
| AndroidX Core, Activity, Lifecycle (Google / AOSP) | 1.18.0 / 1.10.1 / 2.8.7 | App framework | Apache 2.0 |
| Jetpack Compose UI + Material 3 (Google / AOSP) | BOM 2024.09.00 | All HUD drawing | Apache 2.0 |
| CameraX (Google / AOSP) | 1.5.0 | Back and front camera feeds | Apache 2.0 |
| MediaPipe Tasks Vision (Google) | 1.2.3 (per version catalog) | Hand, gesture and face tracking | Apache 2.0 |
| MediaPipe models: `hand_landmarker.task`, `gesture_recognizer.task`, `face_landmarker.task` (Google) | as downloaded | Hand landmarks, gesture labels, blink and iris tracking | Apache 2.0 (see MediaPipe model cards) |
| Vosk Android (Alpha Cephei) | 0.3.75 | Offline speech recognition | Apache 2.0 |
| `vosk-model-small-en-us-0.15` (Alpha Cephei) | 0.15 | English speech model | Apache 2.0 |
| JNA, Java Native Access | 5.13.0 | Native bridge for Vosk | Apache 2.0 / LGPL 2.1 (dual) |
| Google Play services Location (Google) | 21.3.0 | GPS fix | **Not open source**: proprietary, free under the Android SDK / Google APIs terms. *See note below.* |

### Build and test tools only (not shipped)

| Material | License |
|---|---|
| Gradle; Android Gradle Plugin; KSP; Foojay toolchain resolver | Apache 2.0 |
| Secrets Gradle Plugin; Google Services Gradle Plugin (Google) | Apache 2.0 |
| JUnit 4.13.2 | EPL 1.0 |
| Robolectric 4.16.1 | MIT |
| AndroidX Test, Espresso | Apache 2.0 |

### Non-NASA information (no code or assets copied)

- **Axiom Space AxEMU:** publicly stated suit figures only: 8 h EVA rating, 2 h cold rating, helmet lights.
- **Research used for the model design:**
  - Barger et al. 2014, astronaut sleep (*Lancet Neurology*).
  - Blink-rate and fatigue studies: *Nature Scientific Reports* 2025; FAA report AM-94/17; Maffei & Angrilli 2018.
  - PERCLOS drowsiness research.

### Map imagery: source to confirm

- The `mars_mola_tiles` images were added in the original app (commit df2ddf6).
- They appear to be renders of NASA MGS MOLA elevation data. Before submitting, confirm and cite the exact tile provider (e.g. NASA Mars Trek, USGS, OpenPlanetary) and its license.

> **Note on the location library.** Google Play services Location is the one bundled dependency that is not open source. Either disclose it as proprietary-but-free, or swap it for Android's built-in `LocationManager` (a small change in `GpsDistanceMonitor.kt`).

## 2. NASA materials (cited for completeness)

- Mars24 (NASA GISS)
- JPL *Approximate Positions of the Planets*
- NASA DONKI via api.nasa.gov
- Curiosity RAD dose rate
- Perseverance MEDA weather
- NASA SUITS telemetry server (TSS) fields and formulas
- NASA-STD-3001 (CO₂ limit)
- NASA Human Research Program: the 5 Hazards of Human Spaceflight
- NASA Cognition test battery
- MGS MOLA elevation data

## 3. AI disclosure

**AI tools used**

- **Android app, from the second commit onward (28a4708 to the current version):**
  - Claude (Anthropic), through Claude Code.
  - It wrote the code, the unit and UI tests, this README and the documentation.
- **Original app (commit df2ddf6):**
  - Built by the team with **[AI tool(s) used, e.g. Gemini in Android Studio, ChatGPT: team to fill in]**.
- **Web demo (Vercel):**
  - **[tool(s) used: team to fill in]**.

**Who contributed what.** All code was written by AI. The table shows where the ideas and decisions came from.

| Area | Team (STIBOTS) | AI (Claude) |
|---|---|---|
| Core concept | Hands-free AR HUD for Mars EVA. The original app: compass, Mars map, telemetry alerts, drills, gestures and voice. | — |
| Mental-health focus | Making the mind the priority. Hidden front-camera eye tracking with blink counting compared against research. Cognitive-load thresholds of 70 / 90 / 95, with the pulse spoken at emergency. | The method: personal blink baseline, PERCLOS, the fusion weights and "pull toward the worst signal". Also the rule that eyes alone cannot trigger a warning. |
| Structure | Mapping the dashboard to NASA's 5 Hazards (1–3 mind, 4 anti-atrophy, 5 live visualisation). Priority order: mind → vitals → radiation → visualisation. Mars focus, with Earth extreme environments as a secondary use. | How each hazard page is built. |
| NASA data and partner | The requirement to use NASA data and a simple predictive algorithm. Choosing Axiom as the partner. | Researching and choosing the specific datasets and formulas (Mars24, JPL, DONKI, RAD, MEDA, SUITS). The 10-minute linear forecast, the turn-back countdown, the solar-event risk heuristic and the gravity-load model. |
| Design | Fish-eye style. Static multi-tile windows you turn your head to see. Apple Vision Pro–style, uncluttered windows. Strict colour discipline (black and white, with green, yellow and red for status only), no emoji. Compass centred and everything else at the edges. | The visual implementation: the left vitals rail, the load glow on the screen edges, the side panel and button bars, and the lens shader. |
| Features | Gesture and voice controls, mic sensitivity calibration, "end all emergencies", centre lock, multi-device emergency alerts, crew shown on the compass and map with distances, a tutorial (shelved). | Technical approach: offline voice (Vosk) with noise calibration, gesture hold times and cooldowns, and the Wi-Fi crew link (UDP broadcast, multicast and subnet sweep). |
| Testing and fact-checking | Testing on real devices and reporting bugs (Point closing the dashboard, crew link failing, voice misfires). Challenging sources and claims ("source?"). Researching what data NASA publishes. Deciding the presentation tuning. | Automated tests (50). Checking the models against published reference values: the Perseverance landing time and the 2025 Earth–Mars closest approach. |

**Limits of this disclosure.** The AI's account covers only its own sessions. The team should check the rows above against its own records, and fill in the tools used for the original app and the web demo.
