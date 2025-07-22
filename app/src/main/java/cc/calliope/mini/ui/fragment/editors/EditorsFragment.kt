package cc.calliope.mini.ui.fragment.editors

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cc.calliope.mini.databinding.FragmentEditorsBinding
import cc.calliope.mini.ui.adapter.MenuAdapter
import cc.calliope.mini.ui.model.MenuItem
import cc.calliope.mini.ui.viewmodel.MenuViewModel
import cc.calliope.mini.ui.dialog.DialogUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import cc.calliope.mini.ui.adapter.ItemMoveListener

class EditorsFragment : Fragment() {

    private var _binding: FragmentEditorsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MenuViewModel by viewModels { 
        MenuViewModel.Factory(requireContext()) 
    }
    private lateinit var adapter: MenuAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MenuAdapter(
            onItemClick = { openEditor(it) },
            onInfoClick = { showInfoDialog(it) },
            itemMoveListener = object : ItemMoveListener {
                override fun onItemMove(fromPosition: Int, toPosition: Int) {
                    adapter.onItemMove(fromPosition, toPosition)
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No-op
            }

            override fun isLongPressDragEnabled(): Boolean = true
        })

        itemTouchHelper.attachToRecyclerView(binding.recyclerView)

        viewModel.menuItems.observe(viewLifecycleOwner) { items ->
            // Filter out invisible items and sort by order
            val visibleItems = items.filter { it.visible }.sortedBy { it.order }
            adapter.submitList(visibleItems)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh menu when returning from settings
        viewModel.refreshMenu()
    }

    override fun onPause() {
        super.onPause()
        // Save order to ViewModel or SharedPreferences
        viewModel.updateOrder(adapter.getCurrentOrder())
    }

    private fun openEditor(item: MenuItem) {
        val context = requireContext()
        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val boardVersion = preferences.getInt("current_device_version", -1)

        // Use custom URL from settings if the item is "custom"
        val url = if (item.id == "custom") {
            cc.calliope.mini.utils.settings.Settings.getCustomLink(context)
        } else {
            if (boardVersion == 2) item.urlV2 else item.urlV3
        }

        val action = EditorsFragmentDirections.actionEditorsToWeb(url, item.id)
        findNavController().navigate(action)
    }

    private fun showInfoDialog(item: MenuItem) {
        DialogUtils.showInfoDialog(
            requireContext(),
            getString(item.titleResId),
            getString(item.infoResId)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}