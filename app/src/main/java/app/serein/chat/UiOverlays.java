package app.serein.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

final class UiOverlays {
    static MaterialAlertDialogBuilder dialog(Context context, int fill, int stroke) {
        return new MaterialAlertDialogBuilder(context)
                .setBackground(UiKit.surface(context, fill, 12, stroke))
                .setBackgroundInsetStart(UiKit.dp(context, 20))
                .setBackgroundInsetEnd(UiKit.dp(context, 20));
    }

    static void show(AlertDialog dialog, boolean secure, boolean motion, int actionColor) {
        var window = dialog.getWindow();
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!motion) window.setWindowAnimations(0);
        dialog.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            var button = dialog.getButton(which);
            if (button == null) continue;
            button.setTextColor(actionColor);
            button.setAllCaps(false);
            button.setMinHeight(UiKit.dp(button.getContext(), 48));
        }
    }

    static void showForm(AppCompatDialog dialog, boolean secure, boolean motion, int width) {
        var window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.CENTER);
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!motion) window.setWindowAnimations(0);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    static ArrayAdapter<String> menuRows(Context context, String[] labels, int ink) {
        return menuRows(context, labels, ink, -1, 0);
    }

    static ArrayAdapter<String> menuRows(Context context, String[] labels, int ink, int selected, int selectedFill) {
        return new ArrayAdapter<>(context, R.layout.serein_menu_row, labels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setTextColor(ink);
                row.setActivated(position == selected);
                row.setBackgroundColor(position == selected ? selectedFill : android.graphics.Color.TRANSPARENT);
                return row;
            }
        };
    }

    static Snackbar notice(Context context, View host, View anchor, String message, int fill, int ink, boolean motion) {
        if (!motion) context = new ContextThemeWrapper(context, R.style.SereinNoMotion);
        Snackbar bar = Snackbar.make(context, host, message, Snackbar.LENGTH_LONG);
        bar.setAnimationMode(Snackbar.ANIMATION_MODE_FADE);
        bar.setTextColor(ink);
        bar.getView().setBackground(UiKit.surface(context, fill, 8, 0));
        bar.setBackgroundTintList(ColorStateList.valueOf(fill));
        if (anchor != null && anchor.isAttachedToWindow()) bar.setAnchorView(anchor);
        return bar;
    }
}
