package cc.calliope.mini.ui.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.calliope.mini.ui.model.EditorType
import cc.calliope.mini.ui.model.MenuItem
import cc.calliope.mini.utils.settings.Settings

class MenuViewModel(private val context: Context) : ViewModel() {

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
                order = editor.defaultOrder
            )
        }
    }

    fun updateOrder(newItems: List<MenuItem>) {
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
    
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MenuViewModel::class.java)) {
                return MenuViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}