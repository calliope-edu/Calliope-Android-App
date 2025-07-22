package cc.calliope.mini.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import cc.calliope.mini.ui.model.EditorType
import cc.calliope.mini.ui.model.MenuItem

class MenuViewModel : ViewModel() {

    private val _menuItems = MutableLiveData<List<MenuItem>>()
    val menuItems: LiveData<List<MenuItem>> = _menuItems

    init {
        loadDefaultMenu()
    }

    private fun loadDefaultMenu() {
        _menuItems.value = EditorType.entries.map { editor ->
            MenuItem(
                id = editor.id,
                titleResId = editor.titleResId,
                iconRes = editor.iconResId,
                infoResId = editor.infoResId,
                urlV2 = editor.urlV2,
                urlV3 = editor.urlV3,
                order = editor.defaultOrder
            )
        }
    }

    fun updateOrder(newItems: List<MenuItem>) {
        _menuItems.value = newItems
    }

    fun setVisibility(id: String, visible: Boolean) {
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
}