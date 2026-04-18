package com.bloomington.transit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.bloomington.transit.R

class ArrivalNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "bt_arrival_alerts"
        private const val CHANNEL_NAME = "Bus Arrival Alerts"
        private const val NOTIF_ID = 1001
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Track which (vehicle, stop) combos we've already alerted to avoid spam
    private val alerted = mutableSetOf<String>()

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when your tracked bus is approaching a stop"
        }
        manager.createNotificationChannel(channel)
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
            // Bus moved away — reset so we can alert again next approach
            alerted.remove(key)
            return
        }
        if (alerted.contains(key)) return
        alerted.add(key)

        val distStr = if (distanceMeters < 100) "nearby" else "${distanceMeters.toInt()}m away"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Route $routeShortName approaching")
            .setContentText("Your bus is $distStr from $stopName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID, notification)
    }
}
