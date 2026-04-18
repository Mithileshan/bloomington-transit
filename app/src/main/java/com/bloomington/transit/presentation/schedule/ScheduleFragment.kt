package com.bloomington.transit.presentation.schedule

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.R
import com.bloomington.transit.databinding.FragmentScheduleBinding
import com.bloomington.transit.databinding.ItemScheduleEntryBinding
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.bloomington.transit.presentation.planner.PlacePrediction
import kotlinx.coroutines.launch

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScheduleViewModel by viewModels()

    private var suppressSpinners = false
    private var lastRouteIds: List<String> = emptyList()
    private var lastStopIds: List<String> = emptyList()

    private var locationPredictions: List<PlacePrediction> = emptyList()
    private lateinit var locationAdapter: ArrayAdapter<String>
    private var suppressLocationWatch = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- Schedule list ---
        val scheduleAdapter = ScheduleAdapter()
        binding.rvSchedule.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSchedule.adapter = scheduleAdapter

        // --- Location search autocomplete ---
        locationAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf())
        binding.acLocationSearch.setAdapter(locationAdapter)
        binding.acLocationSearch.threshold = 2

        binding.acLocationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressLocationWatch) {
                    val text = s?.toString().orEmpty()
                    viewModel.fetchLocationPredictions(text)
                    binding.btnClearLocation.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                    if (text.isEmpty()) viewModel.clearWalkResult()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.acLocationSearch.setOnItemClickListener { _, _, pos, _ ->
            val pred = locationPredictions.getOrNull(pos) ?: return@setOnItemClickListener
            suppressLocationWatch = true
            binding.acLocationSearch.setText(pred.description)
            binding.acLocationSearch.dismissDropDown()
            suppressLocationWatch = false
            viewModel.findNearestStop(pred.placeId)
        }

        binding.btnClearLocation.setOnClickListener {
            suppressLocationWatch = true
            binding.acLocationSearch.setText("")
            suppressLocationWatch = false
            binding.btnClearLocation.visibility = View.GONE
            viewModel.clearWalkResult()
        }

        // --- Route / Stop spinners ---
        binding.spinnerRoute.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (suppressSpinners) return
                val route = viewModel.uiState.value.routes.getOrNull(pos) ?: return
                viewModel.selectRoute(route.routeId)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.spinnerStop.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (suppressSpinners) return
                val stop = viewModel.uiState.value.stops.getOrNull(pos) ?: return
                viewModel.selectStop(stop.stopId)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // --- Collect state ---
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->

                    // Location predictions
                    if (state.locationPredictions != locationPredictions) {
                        locationPredictions = state.locationPredictions
                        locationAdapter.clear()
                        locationAdapter.addAll(locationPredictions.map { it.description })
                        locationAdapter.notifyDataSetChanged()
                        if (locationPredictions.isNotEmpty() && binding.acLocationSearch.hasFocus()) {
                            binding.acLocationSearch.showDropDown()
                        }
                    }

                    // Walk result card
                    val walk = state.walkResult
                    if (walk != null) {
                        binding.cardWalkResult.visibility = View.VISIBLE
                        binding.tvWalkStopName.text = "📍 Nearest stop: ${walk.stopName}"
                        binding.tvWalkInfo.text = "Walk ~${walk.walkMinutes} min (${walk.distanceMeters}m)"
                        binding.tvWalkRoutes.text = "Routes: ${walk.routes.joinToString(", ")}"
                    } else {
                        binding.cardWalkResult.visibility = View.GONE
                    }

                    // Route spinner — only rebuild when list changes
                    val newRouteIds = state.routes.map { it.routeId }
                    if (newRouteIds != lastRouteIds) {
                        lastRouteIds = newRouteIds
                        suppressSpinners = true
                        val names = state.routes.map { "Route ${it.shortName} — ${it.longName}" }
                        val ra = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
                        ra.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        binding.spinnerRoute.adapter = ra
                        suppressSpinners = false
                    }

                    // Stop spinner — only rebuild when list changes
                    val newStopIds = state.stops.map { it.stopId }
                    if (newStopIds != lastStopIds) {
                        lastStopIds = newStopIds
                        suppressSpinners = true
                        val names = state.stops.map { it.name }
                        val sa = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
                        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        binding.spinnerStop.adapter = sa
                        suppressSpinners = false
                    }

                    scheduleAdapter.submitList(state.entries)
                    binding.tvEmpty.visibility =
                        if (state.entries.isEmpty() && state.selectedStopId.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {

    private var items: List<ScheduleEntry> = emptyList()

    fun submitList(list: List<ScheduleEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemScheduleEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScheduleEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        with(holder.binding) {
            tvRouteName.text = "Route ${entry.routeShortName}"
            tvHeadsign.text = entry.headsign
            tvEta.text = entry.etaLabel
            tvDelay.text = when {
                entry.delayMin > 2 -> "+${entry.delayMin}m late"
                entry.delayMin < -1 -> "${-entry.delayMin}m early"
                else -> "On time"
            }
            tvDelay.setTextColor(when {
                entry.delayMin > 2 -> Color.RED
                entry.delayMin < -1 -> Color.BLUE
                else -> Color.parseColor("#4CAF50")
            })
            root.setBackgroundColor(
                if (position == 0) Color.parseColor("#E8F5E9") else Color.TRANSPARENT
            )
        }
    }
}
