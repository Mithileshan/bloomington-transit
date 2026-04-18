package com.bloomington.transit.presentation.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.GetVehiclePositionsUseCase
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import com.bloomington.transit.notification.ArrivalNotificationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MapUiState(
    val vehicles: List<VehiclePosition> = emptyList(),
    val tripUpdates: List<TripUpdate> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class RouteMapViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TransitRepositoryImpl()
    private val getVehicles = GetVehiclePositionsUseCase(repository)
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val prefs = PreferencesManager(app)
    private val notifManager = ArrivalNotificationManager(app)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    val visibleRouteIds: StateFlow<Set<String>?> = prefs.visibleRouteIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favoriteStopIds: StateFlow<Set<String>> = prefs.favoriteStopIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val allRouteIds: List<String>
        get() = GtfsStaticCache.routes.keys.sorted()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val vehicles = getVehicles()
                    val updates = getTripUpdates()
                    _uiState.value = _uiState.value.copy(
                        vehicles = vehicles,
                        tripUpdates = updates,
                        isLoading = false,
                        error = null
                    )
                    checkTripTracking(vehicles)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Network error. Retrying…")
                }
                delay(10_000)
            }
        }
    }

    private suspend fun checkTripTracking(vehicles: List<VehiclePosition>) {
        val trackedTripId = prefs.trackedTripId.first()
        val trackedStopId = prefs.trackedStopId.first()
        if (trackedTripId.isEmpty() || trackedStopId.isEmpty()) return

        val vehicle = vehicles.find { it.tripId == trackedTripId } ?: return
        val tripStops = GtfsStaticCache.stopTimesByTrip[trackedTripId]
            ?.sortedBy { it.stopSequence } ?: return

        val targetIdx = tripStops.indexOfFirst { it.stopId == trackedStopId }
        if (targetIdx < 0) return

        // Find index of the stop the bus is currently at or just passed
        val currentIdx = tripStops.indexOfLast { it.stopSequence <= vehicle.currentStopSequence }
            .coerceAtLeast(0)

        val stopsAway = targetIdx - currentIdx
        if (stopsAway in 1..4) {
            val targetSt = tripStops[targetIdx]
            val arrivalSec = ArrivalTimeCalculator.stopTimeToUnixSec(targetSt.arrivalTime)
            val minutesAway = ((arrivalSec - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val stop = GtfsStaticCache.stops[trackedStopId]
            notifManager.notifyTripApproaching(
                tripId = trackedTripId,
                stopName = stop?.name ?: trackedStopId,
                routeShortName = route?.shortName ?: vehicle.routeId,
                stopsAway = stopsAway,
                minutesAway = minutesAway.toInt()
            )
        } else if (stopsAway <= 0) {
            // Bus passed the stop — clear tracking
            prefs.clearTracking()
        }
    }

    fun setVisibleRoutes(ids: Set<String>) {
        viewModelScope.launch { prefs.setVisibleRoutes(ids) }
    }
}
