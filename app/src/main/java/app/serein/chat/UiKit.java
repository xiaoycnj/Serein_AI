package app.serein.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
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
        styleField(layout, muted, line, accent);
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

    private static void styleField(TextInputLayout layout, int muted, int line, int accent) {
        Context context = layout.getContext();
        layout.setBoxCornerRadii(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        layout.setBoxStrokeColorStateList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                        {android.R.attr.state_hovered}, {}},
                new int[]{line, accent, muted, muted}));
        layout.setBoxStrokeWidth(dp(context, 1));
        layout.setBoxStrokeWidthFocused(dp(context, 2));
        layout.setDefaultHintTextColor(ColorStateList.valueOf(muted));
    }

    static void dropdown(Context context, LinearLayout parent, String label, String[] options, int selected,
                         int ink, int muted, int line, int accent, int fill, int selectedFill,
                         java.util.function.IntConsumer choose) {
        TextInputLayout layout = (TextInputLayout) LayoutInflater.from(context)
                .inflate(R.layout.serein_dropdown, parent, false);
        layout.setHint(label);
        styleField(layout, muted, line, accent);
        layout.setEndIconTintList(ColorStateList.valueOf(muted));
        MaterialAutoCompleteTextView value = layout.findViewById(R.id.serein_dropdown_value);
        value.setTextColor(ink);
        value.setKeyListener(null);
        value.setDropDownBackgroundDrawable(surface(context, fill, 8, line));
        value.setAdapter(UiOverlays.menuRows(context, options, ink, selected, selectedFill));
        value.setText(options[selected], false);
        value.setOnItemClickListener((list, row, index, id) -> {
            value.setText(options[selected], false);
            choose.accept(index);
        });
        var params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 8);
        parent.addView(layout, params);
    }

    static GradientDrawable surface(Context context, int fill, int radius, int border) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(fill);
        surface.setCornerRadius(dp(context, radius));
        if (border != 0) surface.setStroke(dp(context, 1), border);
        return surface;
    }

    static GradientDrawable sheetSurface(Context context, int fill, int border) {
        GradientDrawable surface = surface(context, fill, 0, border);
        int radius = dp(context, 12);
        surface.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return surface;
    }

    static MaterialCardView readingSurface(Context context, int fill, int stroke) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(context, 8));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(context, 1));
        card.setStrokeColor(stroke);
        card.setCardBackgroundColor(fill);
        return card;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
