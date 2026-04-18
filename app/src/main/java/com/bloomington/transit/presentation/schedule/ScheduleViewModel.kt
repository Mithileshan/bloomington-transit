package com.bloomington.transit.presentation.schedule

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.BuildConfig
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.GtfsRoute
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.bloomington.transit.presentation.planner.PlacePrediction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class WalkResult(
    val stopName: String,
    val stopId: String,
    val distanceMeters: Int,
    val walkMinutes: Int,
    val routes: List<String>
)

data class ScheduleUiState(
    val routes: List<GtfsRoute> = emptyList(),
    val stops: List<GtfsStop> = emptyList(),
    val selectedRouteId: String = "",
    val selectedStopId: String = "",
    val entries: List<ScheduleEntry> = emptyList(),
    val isLoading: Boolean = true,
    val locationPredictions: List<PlacePrediction> = emptyList(),
    val walkResult: WalkResult? = null
)

class ScheduleViewModel : ViewModel() {

    private val repository = TransitRepositoryImpl()
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val getSchedule = GetScheduleForStopUseCase()
    private val http = OkHttpClient()
    private var searchDebounce: Job? = null

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState

    init {
        viewModelScope.launch {
            GtfsStaticCache.loaded.collect { loaded ->
                if (loaded) {
                    val routes = GtfsStaticCache.routes.values.sortedBy { it.shortName }
                    _uiState.value = _uiState.value.copy(routes = routes, isLoading = false)
                }
            }
        }
        startPolling()
    }

    fun selectRoute(routeId: String) {
        val tripIds = GtfsStaticCache.tripsByRoute[routeId] ?: emptyList()
        val stopIds = tripIds.flatMap { tripId ->
            GtfsStaticCache.stopTimesByTrip[tripId]?.map { it.stopId } ?: emptyList()
        }.toSet()
        val stops = stopIds.mapNotNull { GtfsStaticCache.stops[it] }.sortedBy { it.name }
        _uiState.value = _uiState.value.copy(
            selectedRouteId = routeId, stops = stops,
            selectedStopId = "", entries = emptyList()
        )
    }

    fun selectStop(stopId: String) {
        _uiState.value = _uiState.value.copy(selectedStopId = stopId)
        refreshSchedule(stopId)
    }

    fun fetchLocationPredictions(text: String) {
        searchDebounce?.cancel()
        if (text.length < 2) { _uiState.value = _uiState.value.copy(locationPredictions = emptyList()); return }
        searchDebounce = viewModelScope.launch {
            delay(300)
            val preds = withContext(Dispatchers.IO) { autocomplete(text) }
            _uiState.value = _uiState.value.copy(locationPredictions = preds)
        }
    }

    fun findNearestStop(placeId: String) {
        viewModelScope.launch {
            val coord = withContext(Dispatchers.IO) { getPlaceCoords(placeId) } ?: return@launch
            val stop = nearestStop(coord.first, coord.second) ?: return@launch
            val distM = FloatArray(1)
            Location.distanceBetween(coord.first, coord.second, stop.lat, stop.lon, distM)
            val meters = distM[0].toInt()
            val walkMin = (meters / 83.3).toInt().coerceAtLeast(1) // 5 km/h walking speed
            val routes = GtfsStaticCache.stopTimesByStop[stop.stopId]
                ?.mapNotNull { st -> GtfsStaticCache.trips[st.tripId]?.routeId }
                ?.distinct()
                ?.mapNotNull { rid -> GtfsStaticCache.routes[rid]?.shortName }
                ?.distinct()?.sorted() ?: emptyList()

            _uiState.value = _uiState.value.copy(
                locationPredictions = emptyList(),
                walkResult = WalkResult(stop.name, stop.stopId, meters, walkMin, routes)
            )
        }
    }

    fun clearWalkResult() {
        _uiState.value = _uiState.value.copy(walkResult = null, locationPredictions = emptyList())
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                val stopId = _uiState.value.selectedStopId
                if (stopId.isNotEmpty()) refreshSchedule(stopId)
            }
        }
    }

    private fun refreshSchedule(stopId: String) {
        viewModelScope.launch {
            try {
                val updates = getTripUpdates()
                val entries = getSchedule(stopId, updates)
                _uiState.value = _uiState.value.copy(entries = entries)
            } catch (_: Exception) { }
        }
    }

    private suspend fun autocomplete(text: String): List<PlacePrediction> {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/place/autocomplete/json" +
                    "?input=$q&location=39.1653,-86.5264&radius=50000&key=${BuildConfig.MAPS_API_KEY}"
            val body = http.newCall(Request.Builder().url(url).build()).execute().body?.string()
                ?: return emptyList()
            val preds = JSONObject(body).getJSONArray("predictions")
            (0 until preds.length()).map { i ->
                val p = preds.getJSONObject(i)
                PlacePrediction(p.getString("description"), p.getString("place_id"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun getPlaceCoords(placeId: String): Pair<Double, Double>? {
        return try {
            val url = "https://maps.googleapis.com/maps/api/place/details/json" +
                    "?place_id=$placeId&fields=geometry&key=${BuildConfig.MAPS_API_KEY}"
            val body = http.newCall(Request.Builder().url(url).build()).execute().body?.string()
                ?: return null
            val loc = JSONObject(body).getJSONObject("result").getJSONObject("geometry").getJSONObject("location")
            Pair(loc.getDouble("lat"), loc.getDouble("lng"))
        } catch (_: Exception) { null }
    }

    private fun nearestStop(lat: Double, lon: Double): GtfsStop? {
        val dist = FloatArray(1)
        return GtfsStaticCache.stops.values.minByOrNull { stop ->
            Location.distanceBetween(lat, lon, stop.lat, stop.lon, dist)
            dist[0]
        }
    }
}
