package cc.calliope.mini.fragment.editors;

import cc.calliope.mini.R;

public enum Editor {

    MAKECODE(
            R.string.title_make_code,
            R.drawable.ic_editors_makecode,
            R.string.info_make_code,
            "https://makecode.calliope.cc/?androidapp=1"),
    ROBERTA(
            R.string.title_roberta,
            R.drawable.ic_editors_roberta,
            R.string.info_roberta,
            "https://lab.open-roberta.org?loadSystem=calliope2017"),
    BLOCKS(
            R.string.title_blocks,
            R.drawable.ic_editors_blocks,
            R.string.info_blocks,
            "https://blocks.calliope.cc/"),
    CUSTOM(
            R.string.title_custom,
            R.drawable.ic_editors_custom,
            R.string.info_custom,
            "https://makecode.microbit.org/?androidapp=1");

    private final int titleResId;
    private final int iconResId;
    private final int infoResId;
    private final String url;

    Editor(int titleResId, int iconResId, int infoResId, String url) {
        this.titleResId = titleResId;
        this.iconResId = iconResId;
        this.infoResId = infoResId;
        this.url = url;
    }

    public int getTitleResId() {
        return titleResId;
    }

    public int getIconResId() {
        return iconResId;
    }

    public int getInfoResId() {
        return infoResId;
    }

    public String getUrl() {
        return url;
    }
}