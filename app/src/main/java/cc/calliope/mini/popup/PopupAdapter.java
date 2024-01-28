package cc.calliope.mini.popup;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import cc.calliope.mini.R;

public class PopupAdapter extends ArrayAdapter<PopupItem> {
    private final Context context;
    private final List<PopupItem> popupItems;
    private final int type;
    public static final int TYPE_START = 1;
    public static final int TYPE_END = 2;

    @IntDef({TYPE_START, TYPE_END})
    @Retention(RetentionPolicy.SOURCE)
    public @interface menuType {
    }

    public PopupAdapter(Context context, @menuType int type, List<PopupItem> popupItems) {
        super(context,
                type == TYPE_START ? R.layout.popup_item_start : R.layout.popup_item_end, popupItems);
        this.context = context;
        this.type = type;
        this.popupItems = popupItems;
    }

    private static class ViewHolder {
        TextView title;
        ImageView icon;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        View view;

        if (convertView == null) {
            holder = new ViewHolder();
            LayoutInflater inflater = LayoutInflater.from(context);
            view = inflater.inflate(
                    type == TYPE_START ? R.layout.popup_item_start : R.layout.popup_item_end,
                    parent, false);
            holder.title = view.findViewById(R.id.custom_menu_item_title);
            holder.icon = view.findViewById(R.id.custom_menu_item_icon);
            view.setTag(holder);
        } else {
            view = convertView;
            holder = (ViewHolder) view.getTag();
        }

        holder.title.setText(popupItems.get(position).titleId());
        holder.icon.setImageResource(popupItems.get(position).iconId());
        return view;
    }
}