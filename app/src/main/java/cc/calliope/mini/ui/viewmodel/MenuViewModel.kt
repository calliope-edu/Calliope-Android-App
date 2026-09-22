package cc.calliope.mini.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cc.calliope.mini.ui.model.EditorType
import cc.calliope.mini.ui.model.MenuItem
import cc.calliope.mini.utils.settings.Settings

/**
 * The editors menu: which editors are shown and in what order. Holds the
 * application context only — it outlives any activity, so it must never be
 * handed one.
 */
class MenuViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    private val _menuItems = MutableLiveData<List<MenuItem>>()
    val menuItems: LiveData<List<MenuItem>> = _menuItems

    init {
        loadDefaultMenu()
    }

    private fun loadDefaultMenu() {
        _menuItems.value = EditorType.entries.map { editor ->
            MenuItem(
                id = editor.id,
                directoryName = editor.directoryName,
                titleResId = editor.titleResId,
                iconRes = editor.iconResId,
                infoResId = editor.infoResId,
                urlV2 = editor.urlV2,
                urlV3 = editor.urlV3,
                visible = Settings.isEditorVisible(context, editor.id),
                order = Settings.getEditorOrder(context, editor.id, editor.defaultOrder)
            )
        }
    }

    fun updateOrder(newItems: List<MenuItem>) {
        // Save order to preferences
        newItems.forEachIndexed { index, item ->
            Settings.setEditorOrder(context, item.id, index)
        }
        _menuItems.value = newItems
    }

    fun setVisibility(id: String, visible: Boolean) {
        Settings.setEditorVisible(context, id, visible)
        _menuItems.value = _menuItems.value?.map {
            if (it.id == id) it.copy(visible = visible) else it
        }
    }

    fun updateCustomUrl(newUrl: String) {
        _menuItems.value = _menuItems.value?.map {
            if (it.id == EditorType.CUSTOM.id) it.copy(
                urlV2 = newUrl,
                urlV3 = newUrl
            ) else it
        }
    }
    
    fun refreshMenu() {
        loadDefaultMenu()
    }
}
