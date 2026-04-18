package com.bloomington.transit.presentation.planner

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.BuildConfig
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.domain.usecase.JourneyPlan
import com.bloomington.transit.domain.usecase.PlanTripUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class PlacePrediction(val description: String, val placeId: String)

data class PlannerUiState(
    val journeys: List<JourneyPlan> = emptyList(),
    val isSearching: Boolean = false,
    val noResults: Boolean = false,
    val statusMsg: String = ""
)

class TripPlannerViewModel : ViewModel() {

    private val planTrip = PlanTripUseCase()
    private val http = OkHttpClient()

    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState

    private val _originPredictions = MutableStateFlow<List<PlacePrediction>>(emptyList())
    val originPredictions: StateFlow<List<PlacePrediction>> = _originPredictions

    private val _destPredictions = MutableStateFlow<List<PlacePrediction>>(emptyList())
    val destPredictions: StateFlow<List<PlacePrediction>> = _destPredictions

    private var originPlaceId = ""
    private var destPlaceId = ""

    private var originDebounce: Job? = null
    private var destDebounce: Job? = null

    fun fetchOriginPredictions(text: String) {
        originPlaceId = ""
        originDebounce?.cancel()
        if (text.length < 2) { _originPredictions.value = emptyList(); return }
        originDebounce = viewModelScope.launch {
            delay(300)
            _originPredictions.value = autocomplete(text)
        }
    }

    fun fetchDestPredictions(text: String) {
        destPlaceId = ""
        destDebounce?.cancel()
        if (text.length < 2) { _destPredictions.value = emptyList(); return }
        destDebounce = viewModelScope.launch {
            delay(300)
            _destPredictions.value = autocomplete(text)
        }
    }

    fun selectOrigin(prediction: PlacePrediction) {
        originPlaceId = prediction.placeId
        _originPredictions.value = emptyList()
    }

    fun selectDest(prediction: PlacePrediction) {
        destPlaceId = prediction.placeId
        _destPredictions.value = emptyList()
    }

    fun search() {
        if (originPlaceId.isEmpty() || destPlaceId.isEmpty()) {
            _uiState.value = PlannerUiState(
                noResults = true,
                statusMsg = "Please select origin and destination from the dropdown suggestions."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = PlannerUiState(isSearching = true, statusMsg = "Locating places…")

            // Wait for GTFS static data if it hasn't finished loading yet
            if (!GtfsStaticCache.loaded.value) {
                _uiState.value = PlannerUiState(isSearching = true, statusMsg = "Loading transit data, please wait…")
                GtfsStaticCache.loaded.first { it }
            }

            Log.d("PlanTrip", "originPlaceId=$originPlaceId destPlaceId=$destPlaceId")

            val originCoord = getPlaceCoords(originPlaceId)
            val destCoord = getPlaceCoords(destPlaceId)
            Log.d("PlanTrip", "originCoord=$originCoord destCoord=$destCoord")

            if (originCoord == null || destCoord == null) {
                _uiState.value = PlannerUiState(noResults = true, statusMsg = "Could not resolve location.")
                return@launch
            }

            val originStop = nearestStop(originCoord.first, originCoord.second)
            val destStop = nearestStop(destCoord.first, destCoord.second)

            Log.d("PlanTrip", "originStop=${originStop?.stopId} ${originStop?.name}")
            Log.d("PlanTrip", "destStop=${destStop?.stopId} ${destStop?.name}")

            if (originStop == null || destStop == null || originStop.stopId == destStop.stopId) {
                _uiState.value = PlannerUiState(noResults = true)
                return@launch
            }

            val journeys = withContext(Dispatchers.Default) {
                planTrip(originStop.stopId, destStop.stopId)
            }
            _uiState.value = PlannerUiState(
                journeys = journeys,
                isSearching = false,
                noResults = journeys.isEmpty(),
                statusMsg = if (journeys.isNotEmpty())
                    "From: ${originStop.name}  →  To: ${destStop.name}"
                else ""
            )
        }
    }

    private suspend fun autocomplete(text: String): List<PlacePrediction> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/place/autocomplete/json" +
                    "?input=$q&location=39.1653,-86.5264&radius=50000" +
                    "&key=${BuildConfig.MAPS_API_KEY}"
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .body?.string() ?: return@withContext emptyList()
            val predictions = JSONObject(body).getJSONArray("predictions")
            (0 until predictions.length()).map { i ->
                val p = predictions.getJSONObject(i)
                PlacePrediction(p.getString("description"), p.getString("place_id"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun getPlaceCoords(placeId: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://maps.googleapis.com/maps/api/place/details/json" +
                    "?place_id=$placeId&fields=geometry&key=${BuildConfig.MAPS_API_KEY}"
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .body?.string() ?: return@withContext null
            val loc = JSONObject(body).getJSONObject("result")
                .getJSONObject("geometry").getJSONObject("location")
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
