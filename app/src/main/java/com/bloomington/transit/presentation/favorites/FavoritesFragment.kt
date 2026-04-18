package com.bloomington.transit.presentation.favorites

import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.databinding.FragmentFavoritesBinding
import com.bloomington.transit.databinding.ItemFavoriteStopBinding
import com.bloomington.transit.domain.usecase.ScheduleEntry
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: FavoritesViewModel
    private lateinit var acAdapter: ArrayAdapter<String>
    private var currentAllStops: List<GtfsStop> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            FavoritesViewModelFactory(requireContext())
        ).get(FavoritesViewModel::class.java)

        val adapter = FavoritesAdapter { stopId -> viewModel.removeFavorite(stopId) }
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFavorites.adapter = adapter

        acAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf())
        binding.acAddStop.setAdapter(acAdapter)
        binding.acAddStop.threshold = 2
        binding.acAddStop.setOnItemClickListener { _, _, pos, _ ->
            if (pos < currentAllStops.size) {
                viewModel.addFavorite(currentAllStops[pos].stopId)
                binding.acAddStop.text.clear()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.favorites)
                    binding.tvEmpty.visibility =
                        if (state.favorites.isEmpty()) View.VISIBLE else View.GONE

                    // Refresh autocomplete when GTFS data becomes available
                    if (state.allStops.size != currentAllStops.size) {
                        currentAllStops = state.allStops
                        acAdapter.clear()
                        acAdapter.addAll(currentAllStops.map { "${it.name} (${it.stopId})" })
                        acAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class FavoritesAdapter(
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    private var items: List<FavoriteStopInfo> = emptyList()

    fun submitList(list: List<FavoriteStopInfo>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemFavoriteStopBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFavoriteStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val info = items[position]
        with(holder.binding) {
            tvStopName.text = info.stop.name
            tvArrivals.text = info.nextArrivals.joinToString("  |  ") {
                "Rt ${it.routeShortName} ${it.etaLabel}"
            }.ifEmpty { "No upcoming departures" }
            btnRemove.setOnClickListener { onRemove(info.stop.stopId) }
        }
    }
}

class FavoritesViewModelFactory(private val context: android.content.Context) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return FavoritesViewModel(context) as T
    }
}
