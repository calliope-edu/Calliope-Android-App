package cc.calliope.mini.ui.fragment.editors

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cc.calliope.mini.R
import cc.calliope.mini.databinding.FragmentEditorsBinding
import cc.calliope.mini.ui.adapter.MenuAdapter
import cc.calliope.mini.ui.model.MenuItem
import cc.calliope.mini.ui.viewmodel.MenuViewModel
import cc.calliope.mini.ui.dialog.DialogUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import cc.calliope.mini.ui.adapter.ItemMoveListener
import cc.calliope.mini.ui.SnackbarHelper
import android.graphics.Canvas
import android.graphics.drawable.VectorDrawable
import android.graphics.Color
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable

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
            ItemTouchHelper.LEFT
        ) {
            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val background = Color.RED.toDrawable()
                    val context = requireContext()
                    val icon = AppCompatResources.getDrawable(context, R.drawable.delete_icon) as? VectorDrawable
                    
                    if (dX < 0) { // Swiping to the left
                        background.setBounds(
                            itemView.right + dX.toInt(),
                            itemView.top,
                            itemView.right,
                            itemView.bottom
                        )
                        background.draw(c)
                        
                        // Draw delete icon
                        icon?.let {
                            val iconMargin = (itemView.height - it.intrinsicHeight) / 2
                            val iconTop = itemView.top + iconMargin
                            val iconBottom = iconTop + it.intrinsicHeight
                            val iconLeft = itemView.right - iconMargin - it.intrinsicWidth
                            val iconRight = itemView.right - iconMargin
                            it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            it.draw(c)
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.LEFT) {
                    val position = viewHolder.adapterPosition
                    val item = adapter.getItemAt(position)
                    if (item != null) {
                        // Hide the editor
                        viewModel.setVisibility(item.id, false)
                        // Show a snackbar with undo option
                        showUndoSnackbar(item)
                    }
                }
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

        when (item.id) {
            "cardboard_control", "cardboard_face" -> {
                val action = EditorsFragmentDirections.actionEditorsToWebBle(url, item.id)
                findNavController().navigate(action)
            }
            else -> {
                val action = EditorsFragmentDirections.actionEditorsToWeb(url, item.id)
                findNavController().navigate(action)
            }
        }
    }

    private fun showInfoDialog(item: MenuItem) {
        DialogUtils.showInfoDialog(
            requireContext(),
            getString(item.titleResId),
            getString(item.infoResId)
        )
    }

    private fun showUndoSnackbar(item: MenuItem) {
        SnackbarHelper.warningSnackbar(
            binding.root,
            "${getString(item.titleResId)} ${getString(R.string.editor_hidden)}"
        ).setAction(getString(R.string.undo)) {
            // Show the editor again
            viewModel.setVisibility(item.id, true)
        }.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}