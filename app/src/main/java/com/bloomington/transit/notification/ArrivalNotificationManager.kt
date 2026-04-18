package com.bloomington.transit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.bloomington.transit.R

class ArrivalNotificationManager(private val context: Context) {

    companion object {
        private const val ARRIVAL_CHANNEL_ID   = "bt_arrival_alerts"
        private const val ARRIVAL_CHANNEL_NAME = "Bus Arrival Alerts"

        private const val LIVE_CHANNEL_ID      = "bt_live_tracking"
        private const val LIVE_CHANNEL_NAME    = "Live Bus Tracking"

        private const val NOTIF_ID_ARRIVAL       = 1001
        private const val NOTIF_ID_TRIP_ARRIVAL  = 1002
        private const val NOTIF_ID_TRACKING_START = 1003
        private const val NOTIF_ID_LIVE_TRACKING  = 1004
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Track which (vehicle, stop) combos we've already alerted to avoid spam
    private val alerted = mutableSetOf<String>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(ARRIVAL_CHANNEL_ID, ARRIVAL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when your tracked bus is approaching a stop"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(LIVE_CHANNEL_ID, LIVE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live updates while tracking a bus"
                setShowBadge(false)
            }
        )
    }

    /** One-shot notification fired the moment the user taps Track. */
    fun notifyTrackingStarted(routeShortName: String, stopName: String) {
        val notif = NotificationCompat.Builder(context, ARRIVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Tracking Route $routeShortName")
            .setContentText("You'll be notified when the bus approaches $stopName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_ID_TRACKING_START, notif)
    }

    /** Ongoing notification updated every poll cycle while tracking is active. */
    fun updateLiveTracking(
        routeShortName: String,
        stopName: String,
        distanceMeters: Float?,
        etaLabel: String
    ) {
        val distText = when {
            distanceMeters == null          -> ""
            distanceMeters < 100f           -> " · nearby"
            distanceMeters < 1000f          -> " · ${distanceMeters.toInt()}m away"
            else                            -> " · ${"%.1f".format(distanceMeters / 1000f)}km away"
        }
        val etaText = if (etaLabel.isNotEmpty()) "Arrives $etaLabel" else "Tracking live…"

        val notif = NotificationCompat.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Route $routeShortName$distText")
            .setContentText("$etaText · to $stopName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)          // pinned — not swipeable
            .setOnlyAlertOnce(true)    // no sound/vibration on updates
            .build()
        manager.notify(NOTIF_ID_LIVE_TRACKING, notif)
    }

    /** Remove the live tracking notification (called when user stops tracking). */
    fun cancelLiveTracking() {
        manager.cancel(NOTIF_ID_LIVE_TRACKING)
    }

    /** Fires once per milestone (15, 10, 5 min) as the bus approaches the tracked stop. */
    fun notifyMilestone(routeShortName: String, stopName: String, minutesAway: Int) {
        val emoji = when {
            minutesAway <= 5  -> "🔴"
            minutesAway <= 10 -> "🟡"
            else              -> "🟢"
        }
        val notif = NotificationCompat.Builder(context, ARRIVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("$emoji Route $routeShortName — $minutesAway min away")
            .setContentText("Arriving at $stopName in ~$minutesAway minutes")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()
        // Use a unique ID per milestone so all three can show simultaneously
        manager.notify(NOTIF_ID_TRACKING_START + minutesAway, notif)
    }

    fun notifyTripApproaching(
        tripId: String,
        stopName: String,
        routeShortName: String,
        stopsAway: Int,
        minutesAway: Int
    ) {
        val key = "trip_${tripId}_${stopName}"
        if (alerted.contains(key)) return
        alerted.add(key)

        val stopsText = if (stopsAway == 1) "1 stop" else "$stopsAway stops"
        val timeText = if (minutesAway <= 1) "less than 1 min" else "~$minutesAway min"
        val notification = NotificationCompat.Builder(context, ARRIVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("🚌 Route $routeShortName is $stopsText away!")
            .setContentText("Arriving at $stopName in $timeText")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID_TRIP_ARRIVAL, notification)
    }

    fun resetTripAlert(tripId: String, stopName: String) {
        alerted.remove("trip_${tripId}_${stopName}")
    }

    fun notifyIfApproaching(
        vehicleId: String,
        stopName: String,
        routeShortName: String,
        distanceMeters: Float,
        thresholdMeters: Int
    ) {
        val key = "$vehicleId|$stopName"
        if (distanceMeters > thresholdMeters) {
            alerted.remove(key)
            return
        }
        if (alerted.contains(key)) return
        alerted.add(key)

        val distStr = if (distanceMeters < 100) "nearby" else "${distanceMeters.toInt()}m away"
        val notification = NotificationCompat.Builder(context, ARRIVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Route $routeShortName approaching")
            .setContentText("Your bus is $distStr from $stopName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID_ARRIVAL, notification)
    }
}
