package com.bloomington.transit.domain.usecase

import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.GtfsStopTime
import com.bloomington.transit.data.model.StopTimeUpdate
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import java.util.Calendar

data class ScheduleEntry(
    val stopId: String,
    val stopName: String,
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val scheduledArrivalSec: Long,
    val liveArrivalSec: Long,
    val etaLabel: String,
    val delayMin: Int,
    val tripId: String
)

class GetScheduleForStopUseCase {

    operator fun invoke(
        stopId: String,
        tripUpdates: List<TripUpdate>,
        lookAheadMinutes: Int = 180
    ): List<ScheduleEntry> {
        val nowSec = System.currentTimeMillis() / 1000L
        val cutoffSec = nowSec + lookAheadMinutes * 60L

        val stopTimes: List<GtfsStopTime> =
            GtfsStaticCache.stopTimesByStop[stopId] ?: return emptyList()

        val todayServiced = activeTodayServiceIds()
        // Fall back to all services if none run today (e.g. weekends with no service)
        val activeServices = todayServiced.ifEmpty {
            GtfsStaticCache.calendars.keys.toSet()
        }

        val realtimeLookup = mutableMapOf<String, StopTimeUpdate>()
        for (tu in tripUpdates) {
            val stu = tu.stopTimeUpdates.find { it.stopId == stopId }
            if (stu != null) realtimeLookup[tu.tripId] = stu
        }

        val entries = stopTimes.mapNotNull { st ->
            val trip = GtfsStaticCache.trips[st.tripId] ?: return@mapNotNull null
            if (!activeServices.contains(trip.serviceId)) return@mapNotNull null

            val stu = realtimeLookup[st.tripId]
            val scheduledSec = ArrivalTimeCalculator.stopTimeToUnixSec(st.arrivalTime)
            val liveSec = ArrivalTimeCalculator.resolvedArrivalSec(st, stu)

            if (liveSec < nowSec || liveSec > cutoffSec) return@mapNotNull null

            val route = GtfsStaticCache.routes[trip.routeId]
            val stop = GtfsStaticCache.stops[stopId]
            val delayMin = ((liveSec - scheduledSec) / 60L).toInt()

            ScheduleEntry(
                stopId = stopId,
                stopName = stop?.name ?: stopId,
                routeId = trip.routeId,
                routeShortName = route?.shortName ?: trip.routeId,
                headsign = trip.headsign,
                scheduledArrivalSec = scheduledSec,
                liveArrivalSec = liveSec,
                etaLabel = ArrivalTimeCalculator.formatEta(liveSec),
                delayMin = delayMin,
                tripId = st.tripId
            )
        }.sortedBy { it.liveArrivalSec }

        // If still empty (all departures passed for today), show full day schedule
        if (entries.isEmpty()) {
            return stopTimes.mapNotNull { st ->
                val trip = GtfsStaticCache.trips[st.tripId] ?: return@mapNotNull null
                val route = GtfsStaticCache.routes[trip.routeId]
                val stop = GtfsStaticCache.stops[stopId]
                val scheduledSec = ArrivalTimeCalculator.stopTimeToUnixSec(st.arrivalTime)
                ScheduleEntry(
                    stopId = stopId,
                    stopName = stop?.name ?: stopId,
                    routeId = trip.routeId,
                    routeShortName = route?.shortName ?: trip.routeId,
                    headsign = trip.headsign,
                    scheduledArrivalSec = scheduledSec,
                    liveArrivalSec = scheduledSec,
                    etaLabel = ArrivalTimeCalculator.formatEta(scheduledSec),
                    delayMin = 0,
                    tripId = st.tripId
                )
            }.sortedBy { it.scheduledArrivalSec }.take(20)
        }

        return entries
    }

    private fun activeTodayServiceIds(): Set<String> {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return GtfsStaticCache.calendars.values.filter { c ->
            when (dow) {
                Calendar.MONDAY -> c.monday
                Calendar.TUESDAY -> c.tuesday
                Calendar.WEDNESDAY -> c.wednesday
                Calendar.THURSDAY -> c.thursday
                Calendar.FRIDAY -> c.friday
                Calendar.SATURDAY -> c.saturday
                Calendar.SUNDAY -> c.sunday
                else -> false
            }
        }.map { it.serviceId }.toSet()
    }
}
