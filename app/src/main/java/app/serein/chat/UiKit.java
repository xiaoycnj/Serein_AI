package app.serein.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

final class UiKit {
    private UiKit() {}

    static MaterialButton button(Context context, String label, int icon, int foreground, int background,
                                 Runnable action) {
        MaterialButton button = new MaterialButton(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(context, 8));
        button.setPaddingRelative(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setRippleColor(ColorStateList.valueOf((foreground & 0x00ffffff) | 0x24000000));
        if (icon != 0) {
            button.setIconResource(icon);
            button.setIconTint(ColorStateList.valueOf(foreground));
            button.setIconPadding(dp(context, 7));
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconSize(dp(context, 18));
        }
        if (action != null) button.setOnClickListener(view -> action.run());
        return button;
    }

    static MaterialButton iconButton(Context context, String label, int icon, int foreground,
                                     int background, Runnable action) {
        MaterialButton button = button(context, "", icon, foreground, background, action);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setIconPadding(0);
        button.setIconSize(dp(context, 20));
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(dp(context, 48));
        button.setMinHeight(dp(context, 48));
        button.setCornerRadius(dp(context, 24));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        return button;
    }

    static MaterialSwitch toggle(Context context, String label, int color, int accent, int line, boolean checked) {
        MaterialSwitch toggle = new MaterialSwitch(context);
        toggle.setText(label);
        toggle.setTextColor(color);
        toggle.setTextSize(16);
        toggle.setMinHeight(dp(context, 52));
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setTrackTintList(new ColorStateList(states, new int[]{accent, line}));
        toggle.setChecked(checked);
        return toggle;
    }

    static Slider slider(Context context, int color, int value, int max) {
        Slider slider = new Slider(context);
        slider.setValueFrom(0);
        slider.setValueTo(Math.max(1, max));
        slider.setStepSize(1);
        slider.setValue(Math.min(value, Math.max(1, max)));
        slider.setTrackActiveTintList(ColorStateList.valueOf(color));
        slider.setThumbTintList(ColorStateList.valueOf(color));
        slider.setHaloTintList(ColorStateList.valueOf((color & 0x00ffffff) | 0x24000000));
        slider.setLayoutParams(new LinearLayout.LayoutParams(0, dp(context, 48), 1));
        return slider;
    }

    static TextInputEditText inlineField(Context context) {
        TextInputEditText edit = new TextInputEditText(context);
        edit.setMinHeight(dp(context, 48));
        edit.setPadding(dp(context, 4), dp(context, 8), dp(context, 6), dp(context, 8));
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        edit.setBackground(null);
        return edit;
    }

    static TextInputEditText field(Context context, LinearLayout parent, String label, String value,
                                  int ink, int muted, int line, int accent) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(label);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        layout.setBoxStrokeColorStateList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                        {android.R.attr.state_hovered}, {}},
                new int[]{line, accent, muted, muted}));
        layout.setBoxStrokeWidth(dp(context, 1));
        layout.setBoxStrokeWidthFocused(dp(context, 2));
        layout.setDefaultHintTextColor(ColorStateList.valueOf(muted));
        var params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 8);
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setText(value);
        edit.setTextSize(15);
        edit.setTextColor(ink);
        edit.setSingleLine(true);
        edit.setBackground(null);
        edit.setMinHeight(dp(context, 52));
        edit.setPaddingRelative(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        layout.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(layout, params);
        return edit;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
