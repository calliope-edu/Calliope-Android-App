package cc.calliope.mini.ui.adapter

/**
 * Listener interface for notifying when an item in a RecyclerView is moved.
 */
interface ItemMoveListener {
    /**
     * Called when an item is moved from one position to another.
     *
     * @param fromPosition The starting position of the moved item.
     * @param toPosition The ending position of the moved item.
     */
    fun onItemMove(fromPosition: Int, toPosition: Int)
}