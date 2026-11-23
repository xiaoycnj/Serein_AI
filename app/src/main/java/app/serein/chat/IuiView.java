package app.serein.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.Map;
import java.util.function.BiPredicate;

final class IuiView {
    record Colors(int panel, int ink, int muted, int line, int tint, int accent) {}

    static View create(Context context, ChatCard card, Map<String, Object> state, Colors colors,
                       BiPredicate<String, Object> save, Runnable addDraft) {
        var surface = UiKit.readingSurface(context, colors.panel(), colors.line());
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int inset = UiKit.dp(context, 14);
        body.setPadding(inset, inset, inset, inset);
        surface.addView(body);
        addText(body, card.title(), 16, colors.ink(), true);
        addText(body, card.description(), 14, colors.muted(), false);
        boolean interactive = false;
        var checkTint = new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{colors.accent(), colors.muted()});
        for (ChatCard.Block block : card.blocks()) {
            var divider = new View(context);
            var rule = new LinearLayout.LayoutParams(-1, UiKit.dp(context, 1));
            rule.setMargins(0, UiKit.dp(context, 10), 0, UiKit.dp(context, 8));
            divider.setBackgroundColor(colors.line());
            body.addView(divider, rule);
            addText(body, block.title(), 14, colors.ink(), true);
            addText(body, block.text(), 14, colors.muted(), false);
            switch (block.type()) {
                case "checklist" -> {
                    interactive = true;
                    for (ChatCard.Item item : block.items()) {
                        String key = block.id() + "/" + item.id();
                        MaterialCheckBox box = new MaterialCheckBox(context);
                        box.setButtonTintList(checkTint);
                        box.setText(optionText(item));
                        box.setTextColor(colors.ink());
                        box.setMinHeight(UiKit.dp(context, 48));
                        box.setChecked(Boolean.TRUE.equals(state.getOrDefault(key, item.checked())));
                        box.addOnCheckedStateChangedListener((button, checked) -> {
                            if (!save.test(key, checked == MaterialCheckBox.STATE_CHECKED)) {
                                button.setCheckedState(checked == MaterialCheckBox.STATE_CHECKED
                                        ? MaterialCheckBox.STATE_UNCHECKED : MaterialCheckBox.STATE_CHECKED);
                            }
                        });
                        body.addView(box);
                    }
                }
                case "choices" -> {
                    interactive = true;
                    RadioGroup group = new RadioGroup(context);
                    var ids = new android.util.SparseArray<String>();
                    for (ChatCard.Item item : block.items()) {
                        MaterialRadioButton option = new MaterialRadioButton(context);
                        option.setId(View.generateViewId());
                        ids.put(option.getId(), item.id());
                        option.setButtonTintList(checkTint);
                        option.setText(optionText(item));
                        option.setTextColor(colors.ink());
                        option.setMinHeight(UiKit.dp(context, 48));
                        group.addView(option);
                        if (item.id().equals(state.get(block.id()))) group.check(option.getId());
                    }
                    group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                        private int previous = group.getCheckedRadioButtonId();

                        @Override public void onCheckedChanged(RadioGroup source, int id) {
                            if (id == previous || ids.get(id) == null) return;
                            if (save.test(block.id(), ids.get(id))) previous = id;
                            else {
                                source.setOnCheckedChangeListener(null);
                                source.check(previous);
                                source.setOnCheckedChangeListener(this);
                            }
                        }
                    });
                    body.addView(group);
                }
                case "metrics", "cards" -> {
                    for (ChatCard.Item item : block.items()) {
                        addText(body, item.label(), 14, colors.ink(), true);
                        addText(body, item.value(), block.type().equals("metrics") ? 20 : 15, colors.ink(), false);
                        addText(body, item.description(), 14, colors.muted(), false);
                    }
                }
            }
        }
        if (interactive) {
            var button = UiKit.button(context, "加入草稿", R.drawable.ic_plus, colors.ink(), colors.tint(), addDraft);
            var params = new LinearLayout.LayoutParams(-2, -2);
            params.topMargin = UiKit.dp(context, 8);
            body.addView(button, params);
        }
        return surface;
    }

    private static String optionText(ChatCard.Item item) {
        return item.label() + (item.value().isEmpty() ? "" : " · " + item.value())
                + (item.description().isEmpty() ? "" : "\n" + item.description());
    }

    private static void addText(LinearLayout parent, String value, int size, int color, boolean heading) {
        if (value.isBlank()) return;
        TextView text = new TextView(parent.getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, 0, 0, UiKit.dp(parent.getContext(), 4));
        if (heading) {
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            text.setAccessibilityHeading(true);
        }
        parent.addView(text);
    }
}
