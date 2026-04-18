package com.bloomington.transit.presentation.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.databinding.ItemStopScheduleRowBinding
import com.bloomington.transit.databinding.SheetStopScheduleBinding
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StopScheduleSheet : BottomSheetDialogFragment() {

    private var _binding: SheetStopScheduleBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(stopId: String) = StopScheduleSheet().apply {
            arguments = Bundle().also { it.putString("stopId", stopId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetStopScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stopId = arguments?.getString("stopId") ?: return
        val stop = GtfsStaticCache.stops[stopId]
        val prefs = PreferencesManager(requireContext())

        binding.tvScheduleTitle.text = "${stop?.name ?: stopId} Schedule"
        binding.btnBack.setOnClickListener { dismiss() }

        val adapter = StopScheduleAdapter { entry ->
            lifecycleScope.launch {
                prefs.setTrackedTrip(entry.tripId, stopId)
                Toast.makeText(
                    requireContext(),
                    "Tracking Route ${entry.routeShortName} — you'll be notified 4 stops before arrival",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        binding.rvStopSchedule.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStopSchedule.adapter = adapter

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.Default) {
                try {
                    val updates = GetTripUpdatesUseCase(TransitRepositoryImpl())()
                    GetScheduleForStopUseCase()(stopId, updates)
                } catch (_: Exception) {
                    GetScheduleForStopUseCase()(stopId, emptyList())
                }
            }
            adapter.submitList(entries)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class StopScheduleAdapter(
    private val onTrack: (ScheduleEntry) -> Unit
) : RecyclerView.Adapter<StopScheduleAdapter.VH>() {

    private var items: List<ScheduleEntry> = emptyList()

    fun submitList(list: List<ScheduleEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemStopScheduleRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStopScheduleRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.binding.tvRowRoute.text = "${entry.routeShortName} ${entry.headsign}"
        holder.binding.tvRowTime.text = entry.etaLabel
        holder.binding.btnTrack.setOnClickListener { onTrack(entry) }
        holder.itemView.setBackgroundColor(
            if (position == 0) android.graphics.Color.parseColor("#FFF3E0")
            else android.graphics.Color.TRANSPARENT
        )
    }
}
