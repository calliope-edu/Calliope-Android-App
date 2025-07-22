package cc.calliope.mini.ui.model

import cc.calliope.mini.R

enum class EditorType(
    val id: String,
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
        iconResId = R.drawable.ic_editors_makecode,
        infoResId = R.string.info_make_code,
        urlV2 = "https://makecode.calliope.cc/?androidapp=1",
        urlV3 = "https://makecode.calliope.cc/?androidapp=1",
        defaultOrder = 0
    ),

    ROBERTA(
        id = "roberta",
        titleResId = R.string.title_roberta,
        iconResId = R.drawable.ic_editors_roberta,
        infoResId = R.string.info_roberta,
        urlV2 = "https://lab.open-roberta.org/?loadSystem=calliope2017",
        urlV3 = "https://lab.open-roberta.org/?loadSystem=calliopev3",
        defaultOrder = 1
    ),

    BLOCKS(
        id = "blocks",
        titleResId = R.string.title_blocks,
        iconResId = R.mipmap.icon_abgerundet,
        infoResId = R.string.info_blocks,
        urlV2 = "https://blocks.calliope.cc/",
        urlV3 = "https://blocks.calliope.cc/",
        defaultOrder = 2
    ),

    PYTHON(
        id = "python",
        titleResId = R.string.title_python,
        iconResId = R.drawable.python_logo,
        infoResId = R.string.info_python,
        urlV2 = "https://python.calliope.cc/",
        urlV3 = "https://python.calliope.cc/",
        defaultOrder = 3
    ),

    CUSTOM(
        id = "custom",
        titleResId = R.string.title_custom,
        iconResId = R.drawable.ic_editors_custom,
        infoResId = R.string.info_custom,
        urlV2 = "https://makecode.calliope.cc/beta?androidapp=1",
        urlV3 = "https://makecode.calliope.cc/beta?androidapp=1",
        defaultOrder = 4
    );
}