package cc.calliope.mini.ui.model

import cc.calliope.mini.R
import java.util.Locale

enum class EditorType(
    val id: String,  // lowercase for preferences
    val titleResId: Int,
    val iconResId: Int,
    val infoResId: Int,
    val urlV2: String,
    val urlV3: String,
    val defaultOrder: Int
) {
    MAKECODE(
        id = "makecode",
        titleResId = R.string.title_make_code,
        iconResId = R.drawable.ic_make_code_inset,
        infoResId = R.string.info_make_code,
        urlV2 = "https://makecode.calliope.cc/",
        urlV3 = "https://makecode.calliope.cc/",
        defaultOrder = 0
    ),

    ROBERTA(
        id = "roberta",
        titleResId = R.string.title_roberta,
        iconResId = R.drawable.ic_roberta_inset,
        infoResId = R.string.info_roberta,
        urlV2 = "https://lab.open-roberta.org/?loadSystem=calliope2017",
        urlV3 = "https://lab.open-roberta.org/?loadSystem=calliopev3",
        defaultOrder = 1
    ),

    BLOCKS(
        id = "blocks",
        titleResId = R.string.title_blocks,
        iconResId = R.drawable.ic_blocks_inset,
        infoResId = R.string.info_blocks,
        urlV2 = "https://blocks.calliope.cc/",
        urlV3 = "https://blocks.calliope.cc/",
        defaultOrder = 2
    ),

    PYTHON(
        id = "python",
        titleResId = R.string.title_python,
        iconResId = R.drawable.ic_python_inset,
        infoResId = R.string.info_python,
        urlV2 = "https://python.calliope.cc/",
        urlV3 = "https://python.calliope.cc/",
        defaultOrder = 3
    ),

    CARDBOARD_CONTROL(
        id = "cardboard_control",
        titleResId = R.string.title_cardboard_control,
        iconResId = R.drawable.ic_editors_cardboard_control,
        infoResId = R.string.info_cardboard_control,
        urlV2 = "https://cardboard.lofirobot.com/control-calliope/",
        urlV3 = "https://cardboard.lofirobot.com/control-calliope/",
        defaultOrder = 5
    ),

    CARDBOARD_FACE(
        id = "cardboard_face",
        titleResId = R.string.title_cardboard_face,
        iconResId = R.drawable.ic_editors_cardboard_face,
        infoResId = R.string.info_cardboard_face,
        urlV2 = "https://cardboard.lofirobot.com/face-app/",
        urlV3 = "https://cardboard.lofirobot.com/face-app/",
        defaultOrder = 6
    ),

    CUSTOM(
        id = "custom",
        titleResId = R.string.title_custom,
        iconResId = R.drawable.ic_custom_inset,
        infoResId = R.string.info_custom,
        urlV2 = "https://makecode.calliope.cc/beta",
        urlV3 = "https://makecode.calliope.cc/beta",
        defaultOrder = 4
    );

    /**
     * Directory name for file storage.
     * Uses enum name (UPPERCASE) for backwards compatibility with existing files.
     */
    val directoryName: String
        get() = name

    fun getLocalizedUrl(boardVersion: Int): String {
        val baseUrl = if (boardVersion == 2) urlV2 else urlV3
        if (this != MAKECODE) return baseUrl

        val langTag = Locale.getDefault().toLanguageTag()
        val separator = if ('?' in baseUrl) '&' else '?'
        return "${baseUrl}${separator}lang=$langTag"
    }
}
