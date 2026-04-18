package com.bloomington.transit.presentation.planner

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.graphics.Color
import com.bloomington.transit.R
import com.bloomington.transit.databinding.FragmentTripPlannerBinding
import com.bloomington.transit.databinding.ItemJourneyLegBinding
import com.bloomington.transit.databinding.ItemTransferArrowBinding
import com.bloomington.transit.databinding.ItemTripOptionBinding
import com.bloomington.transit.domain.usecase.JourneyPlan
import kotlinx.coroutines.launch

class TripPlannerFragment : Fragment() {

    private var _binding: FragmentTripPlannerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TripPlannerViewModel by viewModels()

    private lateinit var originAdapter: PlaceAdapter
    private lateinit var destAdapter: PlaceAdapter
    private var originPredictions: List<PlacePrediction> = emptyList()
    private var destPredictions: List<PlacePrediction> = emptyList()

    // Suppress text-change callbacks while we programmatically set text after selection
    private var suppressOriginWatch = false
    private var suppressDestWatch = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTripPlannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tripAdapter = TripOptionAdapter()
        binding.rvTripOptions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTripOptions.adapter = tripAdapter

        originAdapter = PlaceAdapter(requireContext(), mutableListOf())
        destAdapter = PlaceAdapter(requireContext(), mutableListOf())

        binding.acOrigin.setAdapter(originAdapter)
        binding.acDest.setAdapter(destAdapter)
        binding.acOrigin.threshold = 2
        binding.acDest.threshold = 2

        binding.acOrigin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressOriginWatch) viewModel.fetchOriginPredictions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.acDest.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressDestWatch) viewModel.fetchDestPredictions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.acOrigin.setOnItemClickListener { parent, _, pos, _ ->
            val selectedText = parent.getItemAtPosition(pos) as? String ?: return@setOnItemClickListener
            val pred = originPredictions.firstOrNull { it.description == selectedText } ?: return@setOnItemClickListener
            viewModel.selectOrigin(pred)
            suppressOriginWatch = true
            binding.acOrigin.setText(pred.description)
            suppressOriginWatch = false
            binding.acOrigin.dismissDropDown()
            hideKeyboard()
        }

        binding.acDest.setOnItemClickListener { parent, _, pos, _ ->
            val selectedText = parent.getItemAtPosition(pos) as? String ?: return@setOnItemClickListener
            val pred = destPredictions.firstOrNull { it.description == selectedText } ?: return@setOnItemClickListener
            viewModel.selectDest(pred)
            suppressDestWatch = true
            binding.acDest.setText(pred.description)
            suppressDestWatch = false
            binding.acDest.dismissDropDown()
            hideKeyboard()
        }

        binding.btnSearch.setOnClickListener {
            hideKeyboard()
            viewModel.search()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.progressBar.visibility = if (state.isSearching) View.VISIBLE else View.GONE
                        binding.tvNoResults.visibility = if (state.noResults) View.VISIBLE else View.GONE
                        binding.tvStatusMsg.visibility = if (state.statusMsg.isNotEmpty()) View.VISIBLE else View.GONE
                        binding.tvStatusMsg.text = state.statusMsg
                        tripAdapter.submitList(state.journeys)
                    }
                }
                launch {
                    viewModel.originPredictions.collect { preds ->
                        originPredictions = preds
                        originAdapter.clear()
                        originAdapter.addAll(preds.map { it.description })
                        originAdapter.notifyDataSetChanged()
                        if (preds.isNotEmpty() && binding.acOrigin.hasFocus()) {
                            binding.acOrigin.showDropDown()
                        }
                    }
                }
                launch {
                    viewModel.destPredictions.collect { preds ->
                        destPredictions = preds
                        destAdapter.clear()
                        destAdapter.addAll(preds.map { it.description })
                        destAdapter.notifyDataSetChanged()
                        if (preds.isNotEmpty() && binding.acDest.hasFocus()) {
                            binding.acDest.showDropDown()
                        }
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.acOrigin.clearFocus()
        binding.acDest.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PlaceAdapter(context: Context, items: MutableList<String>) :
    ArrayAdapter<String>(context, R.layout.item_place_suggestion, R.id.tv_place_name, items) {

    // Disable the built-in filter so ACTV doesn't reorder/hide items behind our back.
    // The Places API already does the filtering; we show exactly what we receive.
    override fun getFilter() = object : android.widget.Filter() {
        override fun performFiltering(c: CharSequence?) = FilterResults().apply {
            values = (0 until count).map { getItem(it) }
            count = this@PlaceAdapter.count
        }
        override fun publishResults(c: CharSequence?, r: FilterResults?) = notifyDataSetChanged()
    }
}

class TripOptionAdapter : RecyclerView.Adapter<TripOptionAdapter.VH>() {

    private var items: List<JourneyPlan> = emptyList()

    fun submitList(list: List<JourneyPlan>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemTripOptionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTripOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val journey = items[position]
        val inflater = LayoutInflater.from(holder.itemView.context)
        with(holder.binding) {
            val dur = journey.totalDurationMin
            val transferLabel = when (journey.transferCount) {
                0 -> "Direct"
                1 -> "1 transfer"
                else -> "${journey.transferCount} transfers"
            }
            tvDuration.text = "${dur} min · $transferLabel"
            tvTimeRange.text = "${journey.departureStr} → ${journey.arrivalStr}"

            llLegs.removeAllViews()
            journey.legs.forEachIndexed { i, leg ->
                // Transfer arrow between legs
                if (i > 0) {
                    val arrowBinding = ItemTransferArrowBinding.inflate(inflater, llLegs, false)
                    val prevLeg = journey.legs[i - 1]
                    val waitMin = ((leg.departureSec - prevLeg.arrivalSec) / 60).toInt().coerceAtLeast(0)
                    arrowBinding.tvTransferNote.text =
                        "↕  Transfer at ${prevLeg.alightStopName}  ·  ${waitMin}m wait"
                    llLegs.addView(arrowBinding.root)
                }

                val legBinding = ItemJourneyLegBinding.inflate(inflater, llLegs, false)
                legBinding.tvRouteBadge.text = leg.routeShortName

                val badgeColor = try {
                    Color.parseColor("#${leg.color.ifEmpty { "1565C0" }}")
                } catch (_: Exception) { Color.parseColor("#1565C0") }
                legBinding.tvRouteBadge.backgroundTintList = ColorStateList.valueOf(badgeColor)

                legBinding.tvBoardInfo.text = "▲ ${leg.boardStopName}  ·  ${leg.departureTimeStr}"
                legBinding.tvAlightInfo.text = "▼ ${leg.alightStopName}  ·  ${leg.arrivalTimeStr}"

                llLegs.addView(legBinding.root)
            }
        }
    }
}
