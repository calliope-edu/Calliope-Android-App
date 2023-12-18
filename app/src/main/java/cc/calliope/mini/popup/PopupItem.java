package cc.calliope.mini.popup;

public class PopupItem {
    private final int titleId;
    private final int iconId;

    public PopupItem(int titleId, int iconId) {
        this.titleId = titleId;
        this.iconId = iconId;
    }

    public int getTitleId() {
        return titleId;
    }

    public int getIconId() {
        return iconId;
    }
}
