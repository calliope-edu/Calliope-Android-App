package cc.calliope.mini.ui.model

import androidx.annotation.StringRes

data class MenuItem(
    val id: String,               // Unique ID for identification
    @StringRes val titleResId: Int,     // String resource ID for title
    val iconRes: Int,             // Drawable resource ID for icon
    @StringRes val infoResId: Int,      // String resource ID for description/info
    val urlV2: String,            // URL for MINI_V2
    val urlV3: String,            // URL for MINI_V3
    val visible: Boolean = true, // Whether the item is visible
    val order: Int = 0           // Order for display sorting
)