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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    // null = first install (show Route 6 only), empty = show nothing, non-empty = show those
    val visibleRouteIds: StateFlow<Set<String>?> = prefs.visibleRouteIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Network error. Retrying..."
                    )
                }
                delay(10_000)
            }
        }
    }

    fun setVisibleRoutes(ids: Set<String>) {
        viewModelScope.launch { prefs.setVisibleRoutes(ids) }
    }
}
