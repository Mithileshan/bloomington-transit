package com.bloomington.transit.presentation.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.model.VehiclePosition
import com.bloomington.transit.databinding.FragmentRouteMapBinding
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
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

class RouteMapFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentRouteMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RouteMapViewModel by viewModels()
    private lateinit var prefs: PreferencesManager

    private var googleMap: GoogleMap? = null
    private val routePolylines = mutableMapOf<String, Polyline>()
    private val stopCircles = mutableListOf<Pair<Circle, com.bloomington.transit.data.model.GtfsStop>>()
    private val busMarkerMap = mutableMapOf<String, Marker>()

    private val bloomington = LatLng(39.1653, -86.5264)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            googleMap?.isMyLocationEnabled = true
            performFlyToLocation()
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
        binding.fabFilterRoutes.setOnClickListener { showRouteFilterDialog() }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        // Restore saved camera position
        viewLifecycleOwner.lifecycleScope.launch {
            val state = prefs.getMapState()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(state.lat, state.lon), state.zoom.toFloat()
            ))
        }

        map.setOnCircleClickListener { circle ->
            stopCircles.find { it.first == circle }?.let { (_, stop) ->
                showStopInfoDialog(stop)
            }
        }

        map.setOnMarkerClickListener { marker ->
            val vehicleId = marker.tag as? String
            if (vehicleId != null) {
                findNavController().navigate(
                    R.id.action_routeMapFragment_to_busTrackerFragment,
                    bundleOf("vehicleId" to vehicleId)
                )
                true
            } else false
        }

        drawAllRouteShapes(viewModel.visibleRouteIds.value)
        drawStopMarkers()
        observeState()
    }

    private fun drawAllRouteShapes(visibleIds: Set<String>?) {
        val map = googleMap ?: return
        routePolylines.values.forEach { it.remove() }
        routePolylines.clear()

        val effectiveIds: Set<String> = when (visibleIds) {
            null -> GtfsStaticCache.routes.values.filter { it.shortName == "6" }.map { it.routeId }.toSet()
            else -> visibleIds
        }
        if (effectiveIds.isEmpty() && visibleIds != null) return  // deselect all

        for ((shapeId, shapes) in GtfsStaticCache.shapes) {
            val routeId = GtfsStaticCache.trips.values.firstOrNull { it.shapeId == shapeId }?.routeId ?: shapeId
            if (routeId !in effectiveIds) continue

            val route = GtfsStaticCache.routes[routeId]
            val colorInt = try { Color.parseColor("#${route?.color ?: "1565C0"}") }
            catch (_: Exception) { Color.parseColor("#1565C0") }

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

    private fun drawStopMarkers() {
        val map = googleMap ?: return
        stopCircles.forEach { (c, _) -> c.remove() }
        stopCircles.clear()

        for (stop in GtfsStaticCache.stops.values) {
            val routeId = GtfsStaticCache.stopTimesByStop[stop.stopId]
                ?.firstOrNull()?.let { st -> GtfsStaticCache.trips[st.tripId]?.routeId }
            val fillColor = routeId?.let { rid ->
                GtfsStaticCache.routes[rid]?.color?.let { hex ->
                    runCatching { Color.parseColor("#$hex") }.getOrNull()
                }
            } ?: Color.parseColor("#FF8F00")

            val circle = map.addCircle(
                CircleOptions()
                    .center(LatLng(stop.lat, stop.lon))
                    .radius(10.0)
                    .fillColor(fillColor or 0xFF000000.toInt())
                    .strokeColor(Color.WHITE)
                    .strokeWidth(2f)
                    .clickable(true)
            )
            stopCircles.add(Pair(circle, stop))
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateBusMarkers(state.vehicles, viewModel.visibleRouteIds.value ?: emptySet())
                        binding.tvStatus.text = if (state.isLoading) "Loading…"
                        else "${state.vehicles.size} buses active"
                    }
                }
                launch {
                    viewModel.visibleRouteIds.collect { visibleIds ->
                        drawAllRouteShapes(visibleIds)
                        updateBusMarkers(viewModel.uiState.value.vehicles, visibleIds ?: emptySet())
                    }
                }
                launch {
                    GtfsStaticCache.loaded.filter { it }.collect {
                        drawAllRouteShapes(viewModel.visibleRouteIds.value)
                        drawStopMarkers()
                    }
                }
            }
        }
    }

    private fun updateBusMarkers(vehicles: List<VehiclePosition>, visibleIds: Set<String>) {
        val map = googleMap ?: return
        val filtered = if (visibleIds.isEmpty() && viewModel.visibleRouteIds.value == null) {
            // first-run: filter to route 6 buses
            val r6 = GtfsStaticCache.routes.values.filter { it.shortName == "6" }.map { it.routeId }.toSet()
            vehicles.filter { it.routeId in r6 }
        } else if (visibleIds.isEmpty()) {
            emptyList()
        } else {
            vehicles.filter { it.routeId in visibleIds }
        }

        val currentIds = filtered.map { it.vehicleId }.toSet()
        busMarkerMap.keys.filter { it !in currentIds }.forEach { id ->
            busMarkerMap.remove(id)?.remove()
        }

        for (vehicle in filtered) {
            val pos = LatLng(vehicle.lat, vehicle.lon)
            val route = GtfsStaticCache.routes[vehicle.routeId]
            val colorInt = try { Color.parseColor("#${route?.color ?: "1565C0"}") }
            catch (_: Exception) { Color.parseColor("#1565C0") }
            val title = "Route ${route?.shortName ?: vehicle.routeId}"

            val existing = busMarkerMap[vehicle.vehicleId]
            if (existing == null) {
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title(title)
                        .snippet("Bus ${vehicle.label.ifEmpty { vehicle.vehicleId }} — tap to track")
                        .icon(createBusDot(colorInt))
                )
                marker?.tag = vehicle.vehicleId
                if (marker != null) busMarkerMap[vehicle.vehicleId] = marker
            } else {
                existing.position = pos
                existing.title = title
            }
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

    private fun showStopInfoDialog(stop: com.bloomington.transit.data.model.GtfsStop) {
        AlertDialog.Builder(requireContext())
            .setTitle(stop.name)
            .setMessage("Stop ID: ${stop.stopId}")
            .setPositiveButton("Add to Favorites") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    prefs.addFavoriteStop(stop.stopId)
                    Toast.makeText(requireContext(), "${stop.name} added to favorites", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun flyToMyLocation() {
        val fineGranted = ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        performFlyToLocation()
    }

    @androidx.annotation.RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun performFlyToLocation() {
        val client = LocationServices.getFusedLocationProviderClient(requireActivity())
        client.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                googleMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 15f)
                )
            } else {
                // lastLocation can be null on emulator — request a fresh fix
                val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
                    .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                    .build()
                client.getCurrentLocation(req, null).addOnSuccessListener { loc ->
                    if (loc != null) {
                        googleMap?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f)
                        )
                    } else {
                        Toast.makeText(requireContext(), "Enable location in emulator extended controls", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showRouteFilterDialog() {
        val allIds = GtfsStaticCache.routes.keys.sorted()
        if (allIds.isEmpty()) {
            Toast.makeText(requireContext(), "Transit data still loading…", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = allIds.map { id ->
            val r = GtfsStaticCache.routes[id]
            "Route ${r?.shortName ?: id}: ${r?.longName ?: ""}"
        }.toTypedArray()

        val currentVisible = viewModel.visibleRouteIds.value
        val route6Ids = GtfsStaticCache.routes.values.filter { it.shortName == "6" }.map { it.routeId }.toSet()
        val checked = BooleanArray(allIds.size) { i ->
            when {
                currentVisible == null -> allIds[i] in route6Ids
                currentVisible.isEmpty() -> false
                else -> allIds[i] in currentVisible
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Show Routes")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Apply") { _, _ ->
                viewModel.setVisibleRoutes(allIds.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .setNeutralButton("Deselect All") { _, _ -> viewModel.setVisibleRoutes(emptySet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

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
        binding.map.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.map.onDestroy()
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.map.onLowMemory()
    }
}
