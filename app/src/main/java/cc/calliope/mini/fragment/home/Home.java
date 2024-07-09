package cc.calliope.mini.fragment.home;

import cc.calliope.mini.R;

public enum Home {

    WELCOME(
            R.string.title_welcome,
            R.drawable.welcome,
            R.string.info_welcome),
    DEMO(
            R.string.title_demo,
            R.drawable.anim_demo,
            R.string.info_demo),
    BATTERY(
            R.string.title_battery,
            R.drawable.anim_battery,
            R.string.info_battery);

    private final int titleResId;
    private final int iconResId;
    private final int infoResId;

    Home(int titleResId, int iconResId, int infoResId) {
        this.titleResId = titleResId;
        this.iconResId = iconResId;
        this.infoResId = infoResId;
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
}