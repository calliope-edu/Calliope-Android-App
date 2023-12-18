package cc.calliope.mini.dialog;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import cc.calliope.mini.R;

public class DialogUtils {

    public static void showEditDialog(Context context, String title, String input, OnEditDialogListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_edit, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(view);

        TextView dialogTitle = view.findViewById(R.id.dialog_title);
        EditText dialogInput = view.findViewById(R.id.dialog_input);
        Button dialogOkButton = view.findViewById(R.id.dialog_ok);
        Button dialogCancelButton = view.findViewById(R.id.dialog_cancel);

        dialogTitle.setText(title);
        dialogInput.setText(input);

        AlertDialog alertDialog = builder.create();
        Window window = alertDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
        }

        dialogOkButton.setOnClickListener(v -> {
            String inputText = dialogInput.getText().toString();
            if (listener != null) {
                listener.onOkButtonClicked(inputText);
            }
            alertDialog.dismiss();
        });

        dialogCancelButton.setOnClickListener(v -> alertDialog.dismiss());
        alertDialog.show();
    }

    public static void showWarningDialog(Context context, String title, String message, OnWarningDialogListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_warning, null);
        builder.setView(view);

        TextView dialogTitle = view.findViewById(R.id.dialog_title);
        TextView dialogMessage = view.findViewById(R.id.dialog_message);
        Button dialogOkButton = view.findViewById(R.id.dialog_ok);
        Button dialogCancelButton = view.findViewById(R.id.dialog_cancel);

        dialogTitle.setText(title);
        dialogMessage.setText(message);

        AlertDialog alertDialog = builder.create();
        Window window = alertDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
        }

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