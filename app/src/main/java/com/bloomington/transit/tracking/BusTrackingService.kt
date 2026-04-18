package com.bloomington.transit.tracking

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bloomington.transit.R
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.GetVehiclePositionsUseCase
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import com.bloomington.transit.notification.ArrivalNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BusTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = TransitRepositoryImpl()
    private val getVehicles = GetVehiclePositionsUseCase(repository)
    private val getTripUpdates = GetTripUpdatesUseCase(repository)

    companion object {
        private const val TAG = "BusTrackingService"
        private const val FOREGROUND_NOTIF_ID = 1004
        private val VEHICLE_MILESTONES = listOf(15, 10, 5)

        const val ACTION_START = "com.bloomington.transit.START_TRACKING"
        const val ACTION_STOP  = "com.bloomington.transit.STOP_TRACKING"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, BusTrackingService::class.java).apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BusTrackingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val notifManager = ArrivalNotificationManager(this)
        startForeground(
            FOREGROUND_NOTIF_ID,
            NotificationCompat.Builder(this, "bt_live_tracking")
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle("Bus Tracking Active")
                .setContentText("Monitoring your journey…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        )

        val prefs = PreferencesManager(this)
        scope.launch {
            val mode = prefs.trackingMode.first()
            if (mode == "trip") {
                runJourneyMode(prefs, notifManager)
            } else {
                runVehicleMode(prefs, notifManager)
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // Vehicle mode — track a specific bus vehicle approaching a stop
    // -------------------------------------------------------------------------

    private suspend fun runVehicleMode(prefs: PreferencesManager, notifManager: ArrivalNotificationManager) {
        val alertedMilestones = mutableSetOf<Int>()
        while (scope.isActive) {
            try {
                val vehicleId   = prefs.trackedVehicleId.first()
                val alertStopId = prefs.trackedStopId.first()
                if (vehicleId.isEmpty() || alertStopId.isEmpty()) { stopSelf(); return }

                val vehicles   = getVehicles()
                val updates    = getTripUpdates()
                val vehicle    = vehicles.find { it.vehicleId == vehicleId }

                if (vehicle != null) {
                    val routeShortName = GtfsStaticCache.routes[vehicle.routeId]?.shortName ?: ""
                    val stopName       = GtfsStaticCache.stops[alertStopId]?.name ?: alertStopId
                    val tripStops      = GtfsStaticCache.stopTimesByTrip[vehicle.tripId] ?: emptyList()
                    val alertStopTime  = tripStops
                        .filter { it.stopSequence >= vehicle.currentStopSequence }
                        .find { it.stopId == alertStopId }

                    if (alertStopTime != null) {
                        val stu         = updates.find { it.tripId == vehicle.tripId }
                            ?.stopTimeUpdates?.find { it.stopId == alertStopId }
                        val arrSec      = ArrivalTimeCalculator.resolvedArrivalSec(alertStopTime, stu)
                        val minutesAway = ((arrSec - System.currentTimeMillis() / 1000L) / 60L).toInt()

                        notifManager.updateLiveTracking(routeShortName, stopName, null,
                            ArrivalTimeCalculator.formatEta(arrSec))

                        for (milestone in VEHICLE_MILESTONES) {
                            if (minutesAway in 0..milestone && milestone !in alertedMilestones) {
                                alertedMilestones.add(milestone)
                                notifManager.notifyMilestone(routeShortName, stopName, minutesAway)
                            }
                        }
                        if (minutesAway < 0 && alertedMilestones.containsAll(VEHICLE_MILESTONES)) {
                            prefs.clearTracking()
                            notifManager.cancelLiveTracking()
                            stopSelf(); return
                        }
                    } else {
                        notifManager.updateLiveTracking(routeShortName,
                            GtfsStaticCache.stops[alertStopId]?.name ?: alertStopId, null, "")
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Vehicle poll error: ${e.message}") }
            delay(15_000)
        }
    }

    // -------------------------------------------------------------------------
    // Journey mode — track a scheduled trip from boarding stop to destination
    //                Notifies every 2 min once within 30 min of departure
    // -------------------------------------------------------------------------

    private suspend fun runJourneyMode(prefs: PreferencesManager, notifManager: ArrivalNotificationManager) {
        var lastNotifiedMinute = Int.MAX_VALUE
        val destArrivalMilestones = mutableSetOf<Int>()

        while (scope.isActive) {
            try {
                val tripId         = prefs.trackedTripId.first()
                val boardingStopId = prefs.trackedStopId.first()
                val destStopId     = prefs.trackedDestStopId.first()
                if (tripId.isEmpty() || boardingStopId.isEmpty() || destStopId.isEmpty()) {
                    stopSelf(); return
                }

                val updates        = getTripUpdates()
                val tripStops      = GtfsStaticCache.stopTimesByTrip[tripId] ?: emptyList()
                val boardingSt     = tripStops.find { it.stopId == boardingStopId }
                val destSt         = tripStops.find { it.stopId == destStopId }
                val route          = GtfsStaticCache.routes[GtfsStaticCache.trips[tripId]?.routeId ?: ""]
                val routeShortName = route?.shortName ?: ""
                val boardingName   = GtfsStaticCache.stops[boardingStopId]?.name ?: boardingStopId
                val destName       = GtfsStaticCache.stops[destStopId]?.name ?: destStopId

                if (boardingSt != null) {
                    val tripUpdate   = updates.find { it.tripId == tripId }
                    val boardingStu  = tripUpdate?.stopTimeUpdates?.find { it.stopId == boardingStopId }
                    val boardingSec  = ArrivalTimeCalculator.resolvedArrivalSec(boardingSt, boardingStu)
                    val minutesToBoard = ((boardingSec - System.currentTimeMillis() / 1000L) / 60L).toInt()

                    Log.d(TAG, "Journey mode: $minutesToBoard min to board at $boardingName")

                    // Update foreground notification
                    val fgText = when {
                        minutesToBoard > 0 -> "Bus in $minutesToBoard min at $boardingName"
                        else               -> "You're on the bus → $destName"
                    }
                    notifManager.updateLiveTracking(routeShortName, boardingName, null, fgText)

                    // Phase 1: Bus approaching boarding stop — every 2 min countdown
                    if (minutesToBoard in 0..30) {
                        // Fire when ETA first crosses a 2-min boundary (30, 28, 26 … 2, 0)
                        val boundary = (minutesToBoard / 2) * 2 // round down to even
                        if (boundary < lastNotifiedMinute) {
                            lastNotifiedMinute = boundary
                            notifManager.notifyJourneyCountdown(
                                routeShortName, boardingName, destName, minutesToBoard)
                        }
                    }

                    // Phase 2: Bus has departed boarding stop — track arrival at destination
                    if (minutesToBoard < 0 && destSt != null) {
                        val destStu    = tripUpdate?.stopTimeUpdates?.find { it.stopId == destStopId }
                        val destSec    = ArrivalTimeCalculator.resolvedArrivalSec(destSt, destStu)
                        val minToDest  = ((destSec - System.currentTimeMillis() / 1000L) / 60L).toInt()

                        for (milestone in listOf(5, 2, 0)) {
                            if (minToDest <= milestone && milestone !in destArrivalMilestones) {
                                destArrivalMilestones.add(milestone)
                                notifManager.notifyMilestone(routeShortName, destName, minToDest)
                            }
                        }

                        // Journey complete
                        if (minToDest < -2) {
                            prefs.clearTracking()
                            notifManager.cancelLiveTracking()
                            notifManager.cancelJourneyUpdate()
                            stopSelf(); return
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Journey poll error: ${e.message}") }
            delay(30_000)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        val nm = ArrivalNotificationManager(this)
        nm.cancelLiveTracking()
        nm.cancelJourneyUpdate()
        super.onDestroy()
    }
}
