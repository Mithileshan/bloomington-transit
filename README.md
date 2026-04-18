# Bloomington Transit App

A real-time Android transit tracker for Bloomington, IN built with Kotlin + native Android (no Android Studio required).

## Architecture: MVVM + Clean Architecture

```
Presentation (Fragments + ViewModels)
    ↓ Use Cases
Domain (Business Logic)
    ↓ Repository
Data (API + Local Cache)
```

Each screen owns a ViewModel backed by `StateFlow`. All live updates poll the GTFS-RT API every **10 seconds** using `repeatOnLifecycle(STARTED)` to pause automatically when the app is backgrounded.

## Features
- **Live Map** — All BT routes drawn as colored polylines; live bus positions update every 10s
- **Bus Tracker** — Follow a single bus on map; see next stops with ETA; set arrival alert radius
- **Schedule** — Select route + stop → departure table with live delays/ETAs
- **Trip Planner** — From/To stop search → direct + one-transfer route options
- **Favorites** — Save stops; live next-arrival countdown
- **Arrival Notifications** — In-app alert when tracked bus is within user-defined radius of a stop
- **Dark Mode** — Full Material3 dark theme, persisted in DataStore

## API
- **GTFS Static**: `https://bt.bloomington.in.gov/gtfs/google_transit.zip` — routes, stops, schedule, shapes
- **GTFS-RT**: `https://bt.bloomington.in.gov/gtfsrt/` — live vehicle positions + trip updates (protobuf)
- No API key required for Bloomington Transit feeds

## Build (Fully Automated — no Android Studio, no API keys)

### One-command setup
```powershell
# Run from project root in PowerShell
.\setup.ps1
```

The script automatically:
1. Verifies Java 17 is installed
2. Downloads Android SDK command-line tools (no Android Studio)
3. Installs SDK packages (`platform-tools`, `android-34`, `build-tools`, `emulator`)
4. Creates an Android Virtual Device (Pixel 7 emulator)
5. Updates `local.properties` with your SDK path
6. Verifies Bloomington Transit GTFS endpoints (tests primary + fallback URLs)
7. Auto-patches `NetworkModule.kt` with working URLs
8. Builds the debug APK

### Manual build (after setup)
```powershell
.\gradlew.bat assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
emulator -avd BT_Pixel7
```

## Dependencies
| Library | Purpose |
|---------|---------|
| Retrofit2 + OkHttp3 | HTTP client for GTFS-RT + static feeds |
| gtfs-realtime-bindings | Parse GTFS-Realtime protobuf binary |
| **OSMDroid 6.1.17** | OpenStreetMap — **no API key required** |
| Jetpack ViewModel + StateFlow | MVVM reactive state |
| Jetpack Navigation | Fragment navigation + back stack |
| DataStore Preferences | Persist favorites, settings |
| Material3 | DayNight theming, UI components |
| Kotlin Coroutines | Async polling, background work |

## Project Structure
See `spec.md` for full file-by-file implementation details.
