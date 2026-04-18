package com.bloomington.transit.data.api

import com.bloomington.transit.data.model.StopTimeUpdate
import com.bloomington.transit.data.model.TripUpdate
import com.bloomington.transit.data.model.VehiclePosition
import com.google.transit.realtime.GtfsRealtime

object GtfsRtParser {

    fun parseVehiclePositions(bytes: ByteArray): List<VehiclePosition> {
        val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
        return feed.entityList.mapNotNull { entity ->
            if (!entity.hasVehicle()) return@mapNotNull null
            val v = entity.vehicle
            if (!v.hasPosition()) return@mapNotNull null
            VehiclePosition(
                vehicleId = if (v.hasVehicle()) v.vehicle.id else entity.id,
                tripId = if (v.hasTrip()) v.trip.tripId else "",
                routeId = if (v.hasTrip()) v.trip.routeId else "",
                lat = v.position.latitude.toDouble(),
                lon = v.position.longitude.toDouble(),
                bearing = v.position.bearing,
                speed = v.position.speed,
                timestamp = if (v.hasTimestamp()) v.timestamp else 0L,
                currentStopSequence = v.currentStopSequence,
                label = if (v.hasVehicle()) v.vehicle.label else ""
            )
        }
    }

    fun parseTripUpdates(bytes: ByteArray): List<TripUpdate> {
        val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
        return feed.entityList.mapNotNull { entity ->
            if (!entity.hasTripUpdate()) return@mapNotNull null
            val tu = entity.tripUpdate
            TripUpdate(
                tripId = if (tu.hasTrip()) tu.trip.tripId else "",
                routeId = if (tu.hasTrip()) tu.trip.routeId else "",
                vehicleId = if (tu.hasVehicle()) tu.vehicle.id else "",
                timestamp = if (tu.hasTimestamp()) tu.timestamp else 0L,
                stopTimeUpdates = tu.stopTimeUpdateList.map { stu ->
                    StopTimeUpdate(
                        stopId = stu.stopId,
                        stopSequence = stu.stopSequence,
                        arrivalDelaySec = if (stu.hasArrival()) stu.arrival.delay else 0,
                        arrivalTime = if (stu.hasArrival() && stu.arrival.hasTime()) stu.arrival.time else 0L,
                        departureDelaySec = if (stu.hasDeparture()) stu.departure.delay else 0,
                        departureTime = if (stu.hasDeparture() && stu.departure.hasTime()) stu.departure.time else 0L
                    )
                }
            )
        }
    }
}
