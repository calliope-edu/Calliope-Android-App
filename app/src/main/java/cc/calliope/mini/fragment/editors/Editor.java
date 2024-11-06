package cc.calliope.mini.fragment.editors;

import cc.calliope.mini.R;

public enum Editor {

    MAKECODE(
            R.string.title_make_code,
            R.drawable.ic_editors_makecode,
            R.string.info_make_code,
            "https://makecode.calliope.cc/?androidapp=1",
            "https://makecode.calliope.cc/?androidapp=1"),
    ROBERTA(
            R.string.title_roberta,
            R.drawable.ic_editors_roberta,
            R.string.info_roberta,
            "https://lab.open-roberta.org/?loadSystem=calliope2017",
            "https://lab.open-roberta.org/?loadSystem=calliopev3"),
//    BLOCKS(
//            R.string.title_blocks,
//            R.mipmap.icon_abgerundet,
//            R.string.info_blocks,
//            "https://blocks.calliope.cc/",
//            "https://blocks.calliope.cc/"),
    PYTHON(
            R.string.title_python,
            R.drawable.python_logo,
            R.string.info_python,
            "https://python.calliope.cc/",
            "https://python.calliope.cc/"),
    CUSTOM(
            R.string.title_custom,
            R.drawable.ic_editors_custom,
            R.string.info_custom,
            "https://makecode.calliope.cc/beta?androidapp=1",
            "https://makecode.calliope.cc/beta?androidapp=1");

    private final int titleResId;
    private final int iconResId;
    private final int infoResId;
    private final String url_v2;
    private final String url_v3;

    Editor(int titleResId, int iconResId, int infoResId, String url_v2, String url_v3) {
        this.titleResId = titleResId;
        this.iconResId = iconResId;
        this.infoResId = infoResId;
        this.url_v2 = url_v2;
        this.url_v3 = url_v3;
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

    public String getUrl_v2() {
        return url_v2;
    }

    public String getUrl_v3() {
        return url_v3;
    }
}