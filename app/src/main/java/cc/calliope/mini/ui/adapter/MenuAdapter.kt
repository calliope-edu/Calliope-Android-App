package cc.calliope.mini.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cc.calliope.mini.databinding.ItemMenuBinding
import cc.calliope.mini.ui.model.MenuItem

class MenuAdapter(
    private val onItemClick: (MenuItem) -> Unit,
    private val onInfoClick: (MenuItem) -> Unit,
    private val itemMoveListener: ItemMoveListener
) : ListAdapter<MenuItem, MenuAdapter.MenuViewHolder>(MenuItemDiffCallback), ItemMoveListener {

    private val mutableItems = mutableListOf<MenuItem>()

    override fun submitList(list: List<MenuItem>?) {
        mutableItems.clear()
        list?.let { mutableItems.addAll(it) }
        super.submitList(list?.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val binding = ItemMenuBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MenuViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(mutableItems[position])
    }

    override fun getItemCount(): Int = mutableItems.size

    inner class MenuViewHolder(private val binding: ItemMenuBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MenuItem) {
            binding.iconImageView.setImageResource(item.iconRes)
            binding.titleTextView.setText(item.titleResId)
            binding.root.setOnClickListener { onItemClick(item) }
            binding.infoButton.setOnClickListener { onInfoClick(item) }
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        val movedItem = mutableItems.removeAt(fromPosition)
        mutableItems.add(toPosition, movedItem)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getCurrentOrder(): List<MenuItem> = mutableItems.mapIndexed { index, item ->
        item.copy(order = index)
    }

    fun getItemAt(position: Int): MenuItem? {
        return if (position in 0 until mutableItems.size) {
            mutableItems[position]
        } else {
            null
        }
    }
}