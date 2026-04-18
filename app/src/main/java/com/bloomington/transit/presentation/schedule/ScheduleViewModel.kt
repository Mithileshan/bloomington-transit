package com.bloomington.transit.presentation.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.GtfsRoute
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val routes: List<GtfsRoute> = emptyList(),
    val stops: List<GtfsStop> = emptyList(),
    val selectedRouteId: String = "",
    val selectedStopId: String = "",
    val entries: List<ScheduleEntry> = emptyList(),
    val isLoading: Boolean = true
)

class ScheduleViewModel : ViewModel() {

    private val repository = TransitRepositoryImpl()
    private val getTripUpdates = GetTripUpdatesUseCase(repository)
    private val getSchedule = GetScheduleForStopUseCase()

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState

    init {
        loadRoutes()
        startPolling()
    }

    private fun loadRoutes() {
        val routes = GtfsStaticCache.routes.values.sortedBy { it.shortName }
        _uiState.value = _uiState.value.copy(routes = routes, isLoading = false)
    }

    fun selectRoute(routeId: String) {
        // Get stops served by this route (from stop_times)
        val tripIds = GtfsStaticCache.tripsByRoute[routeId] ?: emptyList()
        val stopIds = tripIds.flatMap { tripId ->
            GtfsStaticCache.stopTimesByTrip[tripId]?.map { it.stopId } ?: emptyList()
        }.toSet()
        val stops = stopIds.mapNotNull { GtfsStaticCache.stops[it] }
            .sortedBy { it.name }
        _uiState.value = _uiState.value.copy(
            selectedRouteId = routeId,
            stops = stops,
            selectedStopId = "",
            entries = emptyList()
        )
    }

    fun selectStop(stopId: String) {
        _uiState.value = _uiState.value.copy(selectedStopId = stopId)
        refreshSchedule(stopId)
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
}
