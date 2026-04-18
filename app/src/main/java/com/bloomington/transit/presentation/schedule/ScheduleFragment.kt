package com.bloomington.transit.presentation.schedule

import android.graphics.Color
import android.os.Bundle
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
import kotlinx.coroutines.launch

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScheduleViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = ScheduleAdapter()
        binding.rvSchedule.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSchedule.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Populate route spinner
                    val routeNames = state.routes.map { "Route ${it.shortName} — ${it.longName}" }
                    val routeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, routeNames)
                    routeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerRoute.adapter = routeAdapter

                    // Populate stop spinner
                    val stopNames = state.stops.map { it.name }
                    val stopAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, stopNames)
                    stopAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerStop.adapter = stopAdapter

                    adapter.submitList(state.entries)

                    binding.tvEmpty.visibility =
                        if (state.entries.isEmpty() && state.selectedStopId.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        binding.spinnerRoute.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val route = viewModel.uiState.value.routes.getOrNull(pos) ?: return
                viewModel.selectRoute(route.routeId)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.spinnerStop.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val stop = viewModel.uiState.value.stops.getOrNull(pos) ?: return
                viewModel.selectStop(stop.stopId)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
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
            tvDelay.setTextColor(
                when {
                    entry.delayMin > 2 -> Color.RED
                    entry.delayMin < -1 -> Color.BLUE
                    else -> Color.parseColor("#4CAF50")
                }
            )
            // Highlight next departure
            root.setBackgroundColor(
                if (position == 0) Color.parseColor("#E8F5E9") else Color.TRANSPARENT
            )
        }
    }
}
