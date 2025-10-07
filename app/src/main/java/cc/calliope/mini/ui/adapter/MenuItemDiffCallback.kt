package cc.calliope.mini.ui.adapter

import androidx.recyclerview.widget.DiffUtil
import cc.calliope.mini.ui.model.MenuItem

object MenuItemDiffCallback : DiffUtil.ItemCallback<MenuItem>() {
    override fun areItemsTheSame(oldItem: MenuItem, newItem: MenuItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: MenuItem, newItem: MenuItem): Boolean {
        return oldItem == newItem
    }
}