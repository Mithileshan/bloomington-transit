# Bloomington Transit App — Implementation Spec

## Tech Stack
- **Language**: Kotlin
- **Platform**: Android (minSdk 26, targetSdk 34)
- **Build**: Gradle 8.6 via CLI (no Android Studio required)
- **Architecture**: MVVM + Clean Architecture (3 layers)
- **IDE**: VS Code with Kotlin extension

---

## Architecture Overview

```
┌─────────────────────────────────┐
│       Presentation Layer        │
│  Fragments + ViewModels         │
│  StateFlow → UI updates         │
└────────────┬────────────────────┘
             │ calls Use Cases
┌────────────▼────────────────────┐
│         Domain Layer            │
│  Use Cases + ArrivalCalculator  │
│  Pure Kotlin, no Android deps   │
└────────────┬────────────────────┘
             │ calls Repository
┌────────────▼────────────────────┐
│          Data Layer             │
│  Repository → API Services      │
│  GtfsStaticCache (in-memory)    │
│  PreferencesManager (DataStore) │
└─────────────────────────────────┘
```

---

## File Map

### Data Layer
| File | Purpose |
|------|---------|
| `data/model/GtfsModels.kt` | GTFS static data classes (Route, Stop, Trip, Shape, StopTime, Calendar) |
| `data/model/RealtimeModels.kt` | GTFS-RT data classes (VehiclePosition, TripUpdate, StopTimeUpdate) |
| `data/local/GtfsStaticCache.kt` | Singleton in-memory cache for all parsed GTFS static data |
| `data/local/PreferencesManager.kt` | DataStore: favorites, alert distance, dark mode, tracked vehicle |
| `data/api/GtfsRealtimeService.kt` | Retrofit interface: VehiclePositions.pb, TripUpdates.pb |
| `data/api/GtfsStaticService.kt` | Retrofit interface: GTFS ZIP download |
| `data/api/NetworkModule.kt` | OkHttpClient + Retrofit singletons; base URLs |
| `data/api/GtfsStaticParser.kt` | Downloads + extracts + parses GTFS ZIP into GtfsStaticCache |
| `data/api/GtfsRtParser.kt` | Parses GTFS-RT protobuf bytes → VehiclePosition / TripUpdate lists |
| `data/repository/TransitRepository.kt` | Interface: getVehiclePositions(), getTripUpdates() |
| `data/repository/TransitRepositoryImpl.kt` | Calls services, delegates parsing to GtfsRtParser |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/util/ArrivalTimeCalculator.kt` | `formatEta(unixSec)` → "Due"/"1 min"/"X min"; GTFS time → Unix sec |
| `domain/usecase/GetVehiclePositionsUseCase.kt` | Wraps repository.getVehiclePositions() |
| `domain/usecase/GetTripUpdatesUseCase.kt` | Wraps repository.getTripUpdates() |
| `domain/usecase/GetScheduleForStopUseCase.kt` | Merges GTFS static + RT for a stop; returns ScheduleEntry list |
| `domain/usecase/PlanTripUseCase.kt` | Finds direct + one-transfer routes between two stops |
| `domain/usecase/CheckArrivalProximityUseCase.kt` | Calculates distance bus → stop; returns ProximityResult |

### Presentation Layer
| File | Purpose |
|------|---------|
| `presentation/MainActivity.kt` | NavHostFragment, BottomNav, dark mode toggle, GTFS load on startup |
| `presentation/map/RouteMapFragment.kt` | GoogleMap with all route polylines + live bus markers (tap → tracker) |
| `presentation/map/RouteMapViewModel.kt` | Polls every 10s; emits MapUiState |
| `presentation/tracker/BusTrackerFragment.kt` | Map following single bus; next stops list; alert distance seeker |
| `presentation/tracker/BusTrackerViewModel.kt` | Polls every 10s; proximity check; notification trigger |
| `presentation/schedule/ScheduleFragment.kt` | Route + stop spinners → RecyclerView departure table |
| `presentation/schedule/ScheduleViewModel.kt` | Route→stop population; 10s refresh |
| `presentation/schedule/ScheduleAdapter.kt` | (inside ScheduleFragment.kt) RecyclerView adapter for schedule rows |
| `presentation/planner/TripPlannerFragment.kt` | AutoComplete origin/dest → search → TripOptionAdapter |
| `presentation/planner/TripPlannerViewModel.kt` | Calls PlanTripUseCase on background dispatcher |
| `presentation/favorites/FavoritesFragment.kt` | Stop search to add; list with live ETAs; remove button |
| `presentation/favorites/FavoritesViewModel.kt` | Reads DataStore favorites; refreshes arrivals every 10s |
| `notification/ArrivalNotificationManager.kt` | Creates NotificationChannel; fires one-shot alert per vehicle+stop |

---

## API Endpoint Configuration
In `data/api/NetworkModule.kt` — update these constants after verifying at https://bloomington.in.gov/opendata:

```kotlin
const val GTFS_RT_BASE_URL = "https://bt.bloomington.in.gov/gtfsrt/"
const val GTFS_STATIC_URL  = "https://bt.bloomington.in.gov/gtfs/google_transit.zip"
```

Common alternatives to try if the above fail:
- `https://bloomington.in.gov/opendata/data/transit/VehiclePositions.pb`
- `https://bloomington.in.gov/opendata/data/transit/TripUpdates.pb`
- `https://bloomington.in.gov/opendata/data/transit/google_transit.zip`

---

## Polling Design
Each ViewModel that needs live data runs this pattern:

```kotlin
viewModelScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {  // pauses in background
        while (true) {
            fetchAndUpdateState()
            delay(10_000)               // 10-second interval
        }
    }
}
```

---

## Arrival Time Calculation
`ArrivalTimeCalculator.resolvedArrivalSec(staticStopTime, realtimeStopTimeUpdate?)`:
1. If GTFS-RT provides absolute `arrival.time` → use it directly
2. Else: parse GTFS static `HH:MM:SS` → today's Unix sec + `arrival.delay` (seconds)
3. `formatEta(sec)` → "Due" if ≤ 0 min, "X min" if ≤ 90 min, "H:MM AM/PM" if further

---

## Trip Planner Logic
`PlanTripUseCase.invoke(originStopId, destStopId)`:
1. **Direct**: Iterate `stopTimesByStop[origin]`; for each trip, check if `stopTimesByTrip[tripId]` contains dest at higher sequence
2. **One transfer**: Find all stops reachable from origin; find all stops that can reach dest; intersect → transfer point
3. Returns up to 10 results sorted by departure time

---

## Notification Flow
1. `BusTrackerViewModel` calls `CheckArrivalProximityUseCase` every poll cycle
2. If `distanceMeters ≤ thresholdMeters` → call `ArrivalNotificationManager.notifyIfApproaching()`
3. Manager uses a `Set<String>` keyed by `"$vehicleId|$stopName"` to fire at most once per approach
4. When bus moves away (distance > threshold), the key is removed → next approach fires again

---

## Setup Instructions

### One command (Windows PowerShell — fully automated)
```powershell
.\setup.ps1
```
Does everything: Java check → SDK download → package install → AVD creation → `local.properties` update → GTFS URL verification → APK build.

### Map: OpenStreetMap via OSMDroid — **no API key, no GCloud account**
Dependency: `org.osmdroid:osmdroid-android:6.1.17`

### Manual build (after setup)
```powershell
.\gradlew.bat assembleDebug
emulator -avd BT_Pixel7
adb install app\build\outputs\apk\debug\app-debug.apk
adb logcat -s GtfsStaticParser,TransitRepo
```

---

## VS Code Setup
Install these extensions:
- `fwcd.kotlin` — Kotlin syntax + basic completions
- `vscjava.vscode-gradle` — run Gradle tasks from sidebar
- `redhat.vscode-xml` — Android layout XML support

`.vscode/settings.json` (optional):
```json
{
  "kotlin.compiler.jvm.target": "17",
  "files.exclude": { "**/build": true }
}
```

---

## Verification Checklist
- [ ] `./gradlew assembleDebug` completes with BUILD SUCCESSFUL
- [ ] App launches on device/emulator without crash
- [ ] Map shows Bloomington area with route polylines
- [ ] Bus markers appear within 10s of launch
- [ ] Markers update position every 10 seconds
- [ ] Tapping a bus marker navigates to BusTrackerFragment
- [ ] Schedule tab shows arrivals for selected stop
- [ ] ETAs update every 10 seconds
- [ ] Trip Planner returns results for two connected stops
- [ ] Notification fires when bus simulated within alert radius
- [ ] Favorites persist across app restart
- [ ] Dark mode toggle changes theme immediately
