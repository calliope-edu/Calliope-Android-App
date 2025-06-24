package cc.calliope.mini.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import cc.calliope.mini.R;

public final class DialogUtils {
    private DialogUtils() {
    }

    private static Pair<AlertDialog, View> createDialog(Context context, int layoutResId) {
        View view = LayoutInflater.from(context).inflate(layoutResId, null);
        AlertDialog alertDialog = new AlertDialog.Builder(context)
                .setView(view)
                .create();
        setTransparentBackground(alertDialog);
        return new Pair<>(alertDialog, view);
    }

    private static void setTransparentBackground(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public static void showEditDialog(Context context, String title, String input, OnEditDialogListener listener) {
        Pair<AlertDialog, View> pair = createDialog(context, R.layout.dialog_edit);
        AlertDialog alertDialog = pair.first;
        View view = pair.second;

        TextView dialogTitle = view.findViewById(R.id.dialog_title);
        EditText dialogInput = view.findViewById(R.id.dialog_input);
        Button dialogOkButton = view.findViewById(R.id.dialog_ok);
        Button dialogCancelButton = view.findViewById(R.id.dialog_cancel);

        dialogTitle.setText(title);
        dialogInput.setText(input);

        dialogOkButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOkButtonClicked(dialogInput.getText().toString());
            }
            alertDialog.dismiss();
        });

        dialogCancelButton.setOnClickListener(v -> alertDialog.dismiss());
        alertDialog.show();
    }

    public static void showInfoDialog(Context context, String title, String message) {
        Pair<AlertDialog, View> pair = createDialog(context, R.layout.dialog_info);
        AlertDialog alertDialog = pair.first;
        View view = pair.second;

        TextView dialogTitle = view.findViewById(R.id.dialog_title);
        TextView dialogMessage = view.findViewById(R.id.dialog_message);
        Button dialogCancelButton = view.findViewById(R.id.dialog_cancel);

        dialogTitle.setText(title);
        dialogMessage.setText(message);

        dialogCancelButton.setOnClickListener(v -> alertDialog.dismiss());

        alertDialog.show();
    }

    public static void showWarningDialog(Context context, String title, String message, OnWarningDialogListener listener) {
        Pair<AlertDialog, View> pair = createDialog(context, R.layout.dialog_warning);
        AlertDialog alertDialog = pair.first;
        View view = pair.second;

        TextView dialogTitle = view.findViewById(R.id.dialog_title);
        TextView dialogMessage = view.findViewById(R.id.dialog_message);
        Button dialogOkButton = view.findViewById(R.id.dialog_ok);
        Button dialogCancelButton = view.findViewById(R.id.dialog_cancel);

        dialogTitle.setText(title);
        dialogMessage.setText(message);

        dialogOkButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOkButtonClicked();
            }
            alertDialog.dismiss();
        });

        dialogCancelButton.setOnClickListener(v -> alertDialog.dismiss());
        alertDialog.show();
    }

    public interface OnEditDialogListener {
        void onOkButtonClicked(String output);
    }

    public interface OnWarningDialogListener {
        void onOkButtonClicked();
    }
}