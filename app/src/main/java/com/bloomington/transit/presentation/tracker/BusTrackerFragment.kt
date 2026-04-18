package com.bloomington.transit.presentation.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.databinding.FragmentBusTrackerBinding
import com.bloomington.transit.presentation.schedule.ScheduleAdapter
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.launch

class BusTrackerFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentBusTrackerBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: BusTrackerViewModel

    private var googleMap: GoogleMap? = null
    private var busMarker: Marker? = null
    private val stopMarkers = mutableListOf<Marker>()
    private var routeShapeDrawn = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBusTrackerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vehicleId = arguments?.getString("vehicleId") ?: ""
        viewModel = ViewModelProvider(this, BusTrackerViewModelFactory(vehicleId, requireContext()))
            .get(BusTrackerViewModel::class.java)

        binding.trackerMap.onCreate(savedInstanceState)
        binding.trackerMap.getMapAsync(this)

        binding.rvNextStops.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNextStops.adapter = ScheduleAdapter()

        binding.seekAlertDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val meters = (progress + 1) * 100
                binding.tvAlertDistance.text = "${meters}m"
                if (fromUser) viewModel.setAlertDistance(meters)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnClearAlert.setOnClickListener { viewModel.clearAlert() }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        map.setOnMarkerClickListener { marker ->
            val stopId = marker.tag as? String
            if (stopId != null) {
                viewModel.setAlert(stopId)
                true
            } else false
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val map = googleMap ?: return@collect
                    val vehicle = state.vehicle
                    if (vehicle != null) {
                        val pos = LatLng(vehicle.lat, vehicle.lon)
                        val route = GtfsStaticCache.routes[vehicle.routeId]
                        val colorInt = try { Color.parseColor("#${route?.color ?: "1565C0"}") }
                        catch (_: Exception) { Color.parseColor("#1565C0") }

                        if (busMarker == null) {
                            val marker = map.addMarker(
                                MarkerOptions()
                                    .position(pos)
                                    .title("Bus ${vehicle.label.ifEmpty { vehicle.vehicleId }}")
                                    .icon(createBusDot(colorInt))
                            )
                            busMarker = marker
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
                        } else {
                            busMarker!!.position = pos
                            map.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        }

                        if (!routeShapeDrawn) {
                            drawRouteShape(vehicle.tripId, colorInt)
                            routeShapeDrawn = true
                        }
                    }

                    (binding.rvNextStops.adapter as ScheduleAdapter).submitList(state.nextStops)

                    val alertText = if (state.alertStopId.isNotEmpty()) {
                        val stop = GtfsStaticCache.stops[state.alertStopId]
                        "Alert set for ${stop?.name ?: state.alertStopId} within ${state.alertDistanceM}m"
                    } else "Tap a stop marker to set arrival alert"
                    binding.tvAlertStatus.text = alertText
                }
            }
        }
    }

    private fun drawRouteShape(tripId: String, colorInt: Int) {
        val map = googleMap ?: return
        val trip = GtfsStaticCache.trips[tripId] ?: return
        val shapes = GtfsStaticCache.shapes[trip.shapeId]
        if (shapes != null) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(shapes.map { LatLng(it.lat, it.lon) })
                    .color(colorInt)
                    .width(10f)
                    .geodesic(false)
            )
        }

        val stopTimes = GtfsStaticCache.stopTimesByTrip[tripId] ?: return
        stopTimes.take(8).forEach { st ->
            val stop = GtfsStaticCache.stops[st.stopId] ?: return@forEach
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lon))
                    .title(stop.name)
                    .snippet("Tap to set arrival alert")
                    .icon(createStopDot())
            )
            marker?.tag = stop.stopId
            if (marker != null) stopMarkers.add(marker)
        }
    }

    private fun createBusDot(color: Int): BitmapDescriptor {
        val size = 40
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, fill)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, stroke)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun createStopDot(): BitmapDescriptor {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8F00"); style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, fill)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, stroke)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    override fun onResume() {
        super.onResume()
        binding.trackerMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.trackerMap.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.trackerMap.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.trackerMap.onDestroy()
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.trackerMap.onLowMemory()
    }
}

class BusTrackerViewModelFactory(
    private val vehicleId: String,
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BusTrackerViewModel(vehicleId, context) as T
    }
}
