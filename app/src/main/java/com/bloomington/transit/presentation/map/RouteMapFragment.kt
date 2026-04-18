package com.bloomington.transit.presentation.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bloomington.transit.R
import com.bloomington.transit.data.local.GtfsStaticCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.databinding.FragmentRouteMapBinding
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private data class RouteItem(val routeId: String, val displayName: String, val colorInt: Int)

class RouteMapFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentRouteMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RouteMapViewModel by viewModels()
    private lateinit var prefs: PreferencesManager

    private var googleMap: GoogleMap? = null
    private val routePolylines = mutableMapOf<String, Polyline>()
    private val stopCircles = mutableListOf<Pair<Circle, GtfsStop>>()
    private val busMarkerMap = mutableMapOf<String, Marker>()
    private val favoriteMarkers = mutableMapOf<String, Marker>()

    private var routeItems: List<RouteItem> = emptyList()
    private var spinnerReady = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            googleMap?.isMyLocationEnabled = true
            fetchLocationAndAutoSelect()
        } else {
            Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRouteMapBinding.inflate(inflater, container, false)
        prefs = PreferencesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.map.onCreate(savedInstanceState)
        binding.map.getMapAsync(this)
        binding.fabMyLocation.setOnClickListener { flyToMyLocation() }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        val fineOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk || coarseOk) map.isMyLocationEnabled = true

        viewLifecycleOwner.lifecycleScope.launch {
            val state = prefs.getMapState()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(state.lat, state.lon), state.zoom.toFloat()))
        }

        map.setOnCircleClickListener { circle ->
            stopCircles.find { it.first == circle }?.let { (_, stop) -> showStopInfoDialog(stop) }
        }

        map.setOnMarkerClickListener { marker ->
            val vehicleId = marker.tag as? String ?: return@setOnMarkerClickListener false
            findNavController().navigate(
                R.id.action_routeMapFragment_to_busTrackerFragment,
                bundleOf("vehicleId" to vehicleId)
            )
            true
        }

        buildSpinner()
        observeState()

        if (fineOk || coarseOk) {
            fetchLocationAndAutoSelect()
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // ---------------------------------------------------------------------------
    // Spinner
    // ---------------------------------------------------------------------------

    private fun buildSpinner() {
        if (!GtfsStaticCache.isLoaded) {
            viewLifecycleOwner.lifecycleScope.launch {
                GtfsStaticCache.loaded.filter { it }.collect { buildSpinner() }
            }
            return
        }

        val defaultColor = Color.parseColor("#1565C0")
        val allItem = RouteItem("", "All Routes", defaultColor)
        val routes = GtfsStaticCache.routes.values
            .sortedWith(compareBy { it.shortName.padStart(4, '0') })
            .map { route ->
                val color = runCatching { Color.parseColor("#${route.color}") }.getOrDefault(defaultColor)
                RouteItem(route.routeId, "Route ${route.shortName} — ${route.longName}", color)
            }
        routeItems = listOf(allItem) + routes

        spinnerReady = false
        binding.spinnerRoute.adapter = RouteSpinnerAdapter(requireContext(), routeItems)

        // Restore current ViewModel selection
        val currentId = viewModel.selectedRouteId.value
        if (currentId.isNotEmpty()) {
            val pos = routeItems.indexOfFirst { it.routeId == currentId }
            if (pos >= 0) binding.spinnerRoute.setSelection(pos, false)
        }

        binding.spinnerRoute.post { spinnerReady = true }

        binding.spinnerRoute.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnerReady) return
                val item = routeItems.getOrNull(pos) ?: return
                if (item.routeId != viewModel.selectedRouteId.value) {
                    viewModel.selectRoute(item.routeId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ---------------------------------------------------------------------------
    // Location auto-select
    // ---------------------------------------------------------------------------

    private fun fetchLocationAndAutoSelect() {
        val fineOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) return

        val client = LocationServices.getFusedLocationProviderClient(requireActivity())
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f))
            } else {
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()
                client.getCurrentLocation(req, null).addOnSuccessListener { fresh ->
                    if (fresh != null) {
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fresh.latitude, fresh.longitude), 15f))
                    }
                }
            }
        }
    }

    private fun applyRouteSelection(routeId: String) {
        viewModel.selectRoute(routeId)
        val pos = routeItems.indexOfFirst { it.routeId == routeId }
        if (pos >= 0) binding.spinnerRoute.setSelection(pos, false)
    }

    // ---------------------------------------------------------------------------
    // State observation
    // ---------------------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        val routeId = viewModel.selectedRouteId.value
                        updateBusMarkers(state.vehicles, routeId)
                        binding.tvStatus.text = when {
                            state.isLoading -> "Loading real-time data…"
                            state.error != null -> "${state.error} • Retrying in 10s"
                            else -> {
                                val busCount = if (routeId.isEmpty()) state.vehicles.size
                                              else state.vehicles.count { it.routeId == routeId }
                                val time = if (state.lastUpdatedMs > 0)
                                    SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(state.lastUpdatedMs))
                                else "—"
                                "$busCount buses active  •  Updated $time"
                            }
                        }
                    }
                }
                launch {
                    viewModel.selectedRouteId.collect { routeId ->
                        drawRouteShapes(routeId)
                        drawStopMarkers(routeId)
                        updateBusMarkers(viewModel.uiState.value.vehicles, routeId)
                    }
                }
                launch {
                    GtfsStaticCache.loaded.filter { it }.collect {
                        if (routeItems.isEmpty()) buildSpinner()
                        val routeId = viewModel.selectedRouteId.value
                        drawRouteShapes(routeId)
                        drawStopMarkers(routeId)
                        drawFavoriteStars(viewModel.favoriteStopIds.value)
                    }
                }
                launch {
                    viewModel.favoriteStopIds.collect { favIds -> drawFavoriteStars(favIds) }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Map drawing
    // ---------------------------------------------------------------------------

    private fun drawRouteShapes(selectedRouteId: String) {
        val map = googleMap ?: return
        routePolylines.values.forEach { it.remove() }
        routePolylines.clear()

        for ((shapeId, shapes) in GtfsStaticCache.shapes) {
            val routeId = GtfsStaticCache.trips.values.firstOrNull { it.shapeId == shapeId }?.routeId ?: continue
            if (selectedRouteId.isNotEmpty() && routeId != selectedRouteId) continue

            val route = GtfsStaticCache.routes[routeId]
            val colorInt = runCatching { Color.parseColor("#${route?.color ?: "1565C0"}") }
                .getOrDefault(Color.parseColor("#1565C0"))

            val polyline = map.addPolyline(
                PolylineOptions()
                    .addAll(shapes.map { LatLng(it.lat, it.lon) })
                    .color(colorInt)
                    .width(12f)
                    .geodesic(false)
            )
            routePolylines[shapeId] = polyline
        }
    }

    private fun drawStopMarkers(selectedRouteId: String) {
        val map = googleMap ?: return
        stopCircles.forEach { (c, _) -> c.remove() }
        stopCircles.clear()

        val stopsToShow: Collection<GtfsStop> = if (selectedRouteId.isEmpty()) {
            GtfsStaticCache.stops.values
        } else {
            val tripIds = GtfsStaticCache.tripsByRoute[selectedRouteId] ?: emptyList()
            val stopIds = tripIds.flatMap { tid ->
                GtfsStaticCache.stopTimesByTrip[tid]?.map { it.stopId } ?: emptyList()
            }.toSet()
            stopIds.mapNotNull { GtfsStaticCache.stops[it] }
        }

        val routeColor = GtfsStaticCache.routes[selectedRouteId]?.color
            ?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }
            ?: Color.parseColor("#FF8F00")

        for (stop in stopsToShow) {
            val fillColor = if (selectedRouteId.isNotEmpty()) {
                routeColor or 0xFF000000.toInt()
            } else {
                val rid = GtfsStaticCache.stopTimesByStop[stop.stopId]
                    ?.firstOrNull()?.let { st -> GtfsStaticCache.trips[st.tripId]?.routeId }
                rid?.let { r -> GtfsStaticCache.routes[r]?.color
                    ?.let { hex -> runCatching { Color.parseColor("#$hex") }.getOrNull() }
                } ?: Color.parseColor("#FF8F00")
            }

            val circle = map.addCircle(
                CircleOptions()
                    .center(LatLng(stop.lat, stop.lon))
                    .radius(10.0)
                    .fillColor(fillColor)
                    .strokeColor(Color.WHITE)
                    .strokeWidth(2f)
                    .clickable(true)
            )
            stopCircles.add(Pair(circle, stop))
        }
    }

    private fun updateBusMarkers(vehicles: List<VehiclePosition>, selectedRouteId: String) {
        val map = googleMap ?: return
        val filtered = if (selectedRouteId.isEmpty()) vehicles
                       else vehicles.filter { it.routeId == selectedRouteId }

        val currentIds = filtered.map { it.vehicleId }.toSet()
        busMarkerMap.keys.filter { it !in currentIds }.forEach { id -> busMarkerMap.remove(id)?.remove() }

        for (vehicle in filtered) {
            val pos = LatLng(vehicle.lat, vehicle.lon)
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val colorInt = runCatching { Color.parseColor("#${route?.color ?: "1565C0"}") }
                .getOrDefault(Color.parseColor("#1565C0"))

            val existing = busMarkerMap[vehicle.vehicleId]
            if (existing == null) {
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title("Route ${route?.shortName ?: vehicle.routeId}")
                        .snippet("Bus ${vehicle.label.ifEmpty { vehicle.vehicleId }} — tap to track")
                        .icon(createBusDot(colorInt))
                )
                marker?.tag = vehicle.vehicleId
                if (marker != null) busMarkerMap[vehicle.vehicleId] = marker
            } else {
                existing.position = pos
            }
        }
    }

    private fun drawFavoriteStars(favStopIds: Set<String>) {
        val map = googleMap ?: return
        favoriteMarkers.keys.filter { it !in favStopIds }.forEach { id ->
            favoriteMarkers.remove(id)?.remove()
        }
        for (stopId in favStopIds) {
            if (favoriteMarkers.containsKey(stopId)) continue
            val stop = GtfsStaticCache.stops[stopId] ?: continue
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lon))
                    .title("⭐ ${stop.name}")
                    .snippet("Favorite stop")
                    .icon(createStarIcon())
            )
            if (marker != null) favoriteMarkers[stopId] = marker
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun flyToMyLocation() {
        val fineOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        fetchLocationAndAutoSelect()
    }

    private fun showStopInfoDialog(stop: GtfsStop) {
        val sheet = StopInfoSheet.newInstance(stop.stopId)
        sheet.onViewSchedule = {
            StopScheduleSheet.newInstance(stop.stopId, viewModel.selectedRouteId.value)
                .show(parentFragmentManager, "schedule")
        }
        sheet.show(parentFragmentManager, "stop_info")
    }

    private fun createBusDot(color: Int): BitmapDescriptor {
        val size = 40
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, fill)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, stroke)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun createStarIcon(): BitmapDescriptor {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFA000"); style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BF360C"); style = Paint.Style.STROKE; strokeWidth = 5f }
        val path = android.graphics.Path()
        val cx = size / 2f; val cy = size / 2f
        val outerR = size / 2f - 6; val innerR = outerR * 0.42f
        for (i in 0 until 10) {
            val angle = Math.PI / 5 * i - Math.PI / 2
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // ---------------------------------------------------------------------------
    // Lifecycle passthrough
    // ---------------------------------------------------------------------------

    override fun onResume() { super.onResume(); binding.map.onResume() }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        googleMap?.cameraPosition?.let { cam ->
            viewLifecycleOwner.lifecycleScope.launch {
                prefs.saveMapState(cam.target.latitude, cam.target.longitude, cam.zoom.toDouble())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.map?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.map.onDestroy()
        _binding = null
    }

    override fun onLowMemory() { super.onLowMemory(); binding.map.onLowMemory() }
}

// ---------------------------------------------------------------------------
// Spinner adapter — each route item rendered in its own route color
// ---------------------------------------------------------------------------

private class RouteSpinnerAdapter(context: Context, private val items: List<RouteItem>) :
    ArrayAdapter<RouteItem>(context, android.R.layout.simple_spinner_item, items) {

    init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View =
        style(super.getView(pos, convertView, parent), items[pos], bold = true)

    override fun getDropDownView(pos: Int, convertView: View?, parent: ViewGroup): View =
        style(super.getDropDownView(pos, convertView, parent), items[pos], bold = false)

    private fun style(view: View, item: RouteItem, bold: Boolean): View {
        view.findViewById<TextView>(android.R.id.text1)?.apply {
            text = item.displayName
            setTextColor(item.colorInt)
            setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        return view
    }
}
