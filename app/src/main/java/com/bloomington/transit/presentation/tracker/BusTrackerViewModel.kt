package com.bloomington.transit.presentation.tracker

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.CheckArrivalProximityUseCase
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.GetVehiclePositionsUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.bloomington.transit.notification.ArrivalNotificationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TrackerUiState(
    val vehicle: VehiclePosition? = null,
    val nextStops: List<ScheduleEntry> = emptyList(),
    val isLoading: Boolean = true,
    val alertEnabled: Boolean = false,
    val alertStopId: String = "",
    val alertDistanceM: Int = 300
)

class BusTrackerViewModel(
    private val vehicleId: String,
    private val context: Context
) : ViewModel() {

    private val repository = TransitRepositoryImpl()
    private val getVehicles = GetVehiclePositionsUseCase(repository)
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val getSchedule = GetScheduleForStopUseCase()
    private val proximityCheck = CheckArrivalProximityUseCase()
    private val notifManager = ArrivalNotificationManager(context)
    private val prefs = PreferencesManager(context)

    private val _uiState = MutableStateFlow(TrackerUiState())
    val uiState: StateFlow<TrackerUiState> = _uiState

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val vehicles = getVehicles()
                    val updates = getTripUpdates()
                    val vehicle = vehicles.find { it.vehicleId == vehicleId }

                    // Compute next stops for this vehicle's current trip
                    val nextStops = if (vehicle != null) {
                        computeNextStops(vehicle, updates)
                    } else emptyList()

                    // Check proximity alert
                    val alertStopId = prefs.trackedStopId.first()
                    val alertDist = prefs.alertDistanceMeters.first()
                    if (alertStopId.isNotEmpty() && vehicle != null) {
                        val result = proximityCheck(vehicleId, alertStopId, alertDist, vehicles)
                        if (result != null && result.isWithinThreshold) {
                            notifManager.notifyIfApproaching(
                                vehicleId = vehicleId,
                                stopName = result.stopName,
                                routeShortName = result.routeShortName,
                                distanceMeters = result.distanceMeters,
                                thresholdMeters = alertDist
                            )
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        vehicle = vehicle,
                        nextStops = nextStops,
                        isLoading = false,
                        alertStopId = alertStopId,
                        alertDistanceM = alertDist
                    )
                } catch (_: Exception) { }
                delay(10_000)
            }
        }
    }

    private fun computeNextStops(vehicle: VehiclePosition, updates: List<TripUpdate>): List<ScheduleEntry> {
        val tripStops = GtfsStaticCache.stopTimesByTrip[vehicle.tripId] ?: return emptyList()
        val afterSeq = vehicle.currentStopSequence
        val upcoming = tripStops.filter { it.stopSequence >= afterSeq }.take(5)
        return upcoming.mapNotNull { st ->
            val tripUpdate = updates.find { it.tripId == vehicle.tripId }
            val stu = tripUpdate?.stopTimeUpdates?.find { it.stopId == st.stopId }
            val arrSec = com.bloomington.transit.domain.util.ArrivalTimeCalculator.resolvedArrivalSec(st, stu)
            val stop = GtfsStaticCache.stops[st.stopId] ?: return@mapNotNull null
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val trip = GtfsStaticCache.trips[vehicle.tripId]
            ScheduleEntry(
                stopId = st.stopId,
                stopName = stop.name,
                routeId = vehicle.routeId,
                routeShortName = route?.shortName ?: "",
                headsign = trip?.headsign ?: "",
                scheduledArrivalSec = com.bloomington.transit.domain.util.ArrivalTimeCalculator.stopTimeToUnixSec(st.arrivalTime),
                liveArrivalSec = arrSec,
                etaLabel = com.bloomington.transit.domain.util.ArrivalTimeCalculator.formatEta(arrSec),
                delayMin = 0,
                tripId = vehicle.tripId
            )
        }
    }

    fun setAlert(stopId: String) {
        viewModelScope.launch {
            prefs.setTrackedVehicle(vehicleId, stopId)
            _uiState.value = _uiState.value.copy(alertEnabled = true, alertStopId = stopId)
        }
    }

    fun clearAlert() {
        viewModelScope.launch {
            prefs.clearTracking()
            _uiState.value = _uiState.value.copy(alertEnabled = false, alertStopId = "")
        }
    }

    fun setAlertDistance(meters: Int) {
        viewModelScope.launch {
            prefs.setAlertDistance(meters)
            _uiState.value = _uiState.value.copy(alertDistanceM = meters)
        }
    }
}
