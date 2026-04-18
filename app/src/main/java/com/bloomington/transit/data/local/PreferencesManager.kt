package com.bloomington.transit.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bt_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_FAVORITES = stringSetPreferencesKey("favorite_stops")
        private val KEY_ALERT_DISTANCE_M = intPreferencesKey("alert_distance_m")
        private val KEY_TRACKED_VEHICLE = stringPreferencesKey("tracked_vehicle_id")
        private val KEY_TRACKED_STOP = stringPreferencesKey("tracked_stop_id")
        private val KEY_TRACKED_TRIP = stringPreferencesKey("tracked_trip_id")
        private val KEY_VISIBLE_ROUTES = stringSetPreferencesKey("visible_routes")
        private val KEY_MAP_LAT = floatPreferencesKey("map_lat")
        private val KEY_MAP_LON = floatPreferencesKey("map_lon")
        private val KEY_MAP_ZOOM = floatPreferencesKey("map_zoom")
    }

    val favoriteStopIds: Flow<Set<String>> = context.dataStore.data
        .map { it[KEY_FAVORITES] ?: emptySet() }

    val alertDistanceMeters: Flow<Int> = context.dataStore.data
        .map { it[KEY_ALERT_DISTANCE_M] ?: 300 }

    val trackedVehicleId: Flow<String> = context.dataStore.data
        .map { it[KEY_TRACKED_VEHICLE] ?: "" }

    val trackedStopId: Flow<String> = context.dataStore.data
        .map { it[KEY_TRACKED_STOP] ?: "" }

    val trackedTripId: Flow<String> = context.dataStore.data
        .map { it[KEY_TRACKED_TRIP] ?: "" }

    // null  = never configured (first install) → show only Route 6 by default
    // empty = user explicitly deselected all
    // non-empty = user's explicit selection
    val visibleRouteIds: Flow<Set<String>?> = context.dataStore.data
        .map { prefs -> if (KEY_VISIBLE_ROUTES in prefs) prefs[KEY_VISIBLE_ROUTES] else null }

    suspend fun addFavoriteStop(stopId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current + stopId
        }
    }

    suspend fun removeFavoriteStop(stopId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current - stopId
        }
    }

    suspend fun setAlertDistance(meters: Int) {
        context.dataStore.edit { it[KEY_ALERT_DISTANCE_M] = meters }
    }

    suspend fun setTrackedTrip(tripId: String, stopId: String) {
        context.dataStore.edit {
            it[KEY_TRACKED_TRIP] = tripId
            it[KEY_TRACKED_STOP] = stopId
            it[KEY_TRACKED_VEHICLE] = ""
        }
    }

    suspend fun setVisibleRoutes(routeIds: Set<String>) {
        context.dataStore.edit { it[KEY_VISIBLE_ROUTES] = routeIds }
    }

    data class MapState(val lat: Double, val lon: Double, val zoom: Double)

    suspend fun getMapState(): MapState {
        val prefs = context.dataStore.data.first()
        return MapState(
            lat = (prefs[KEY_MAP_LAT] ?: 39.1653f).toDouble(),
            lon = (prefs[KEY_MAP_LON] ?: -86.5264f).toDouble(),
            zoom = (prefs[KEY_MAP_ZOOM] ?: 13f).toDouble()
        )
    }

    suspend fun saveMapState(lat: Double, lon: Double, zoom: Double) {
        context.dataStore.edit {
            it[KEY_MAP_LAT] = lat.toFloat()
            it[KEY_MAP_LON] = lon.toFloat()
            it[KEY_MAP_ZOOM] = zoom.toFloat()
        }
    }

    suspend fun setTrackedVehicle(vehicleId: String, stopId: String) {
        context.dataStore.edit {
            it[KEY_TRACKED_VEHICLE] = vehicleId
            it[KEY_TRACKED_STOP] = stopId
        }
    }

    suspend fun clearTracking() {
        context.dataStore.edit {
            it[KEY_TRACKED_VEHICLE] = ""
            it[KEY_TRACKED_STOP] = ""
        }
    }
}
