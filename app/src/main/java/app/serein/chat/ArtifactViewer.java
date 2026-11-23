package app.serein.chat;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

final class ArtifactViewer {
    private static final int CODE_PREVIEW_LENGTH = 24_000;
    private static final int ROW_PREVIEW_LIMIT = 24;
    private static final int COLUMN_PREVIEW_LIMIT = 10;

    record Colors(int ink, int muted, int panel, int line, int tint, int accent) {}

    static String label(ChatArtifacts.Block block) {
        if (block instanceof ChatArtifacts.CodeBlock code) {
            return code.language().isBlank() ? "代码块" : "代码块 · " + code.language();
        }
        var table = (ChatArtifacts.Table) block;
        return "表格 · " + Math.max(0, table.rows().size() - 1) + " 行 × " + table.columns() + " 列";
    }

    static AlertDialog dialog(MaterialAlertDialogBuilder builder, Context context, ChatArtifacts.Block block,
                              Colors colors, Runnable copy, Runnable save) {
        var scroll = new ScrollView(context);
        var body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(context, 20);
        body.setPadding(horizontal, dp(context, 6), horizontal, dp(context, 12));
        scroll.addView(body);
        boolean code = block instanceof ChatArtifacts.CodeBlock;
        if (block instanceof ChatArtifacts.CodeBlock snippet) showCode(body, snippet, colors);
        else showTable(body, (ChatArtifacts.Table) block, colors);
        var dialog = builder.setTitle(label(block)).setView(scroll)
                .setNegativeButton("关闭", null)
                .setNeutralButton(code ? "复制代码" : "复制表格", null)
                .setPositiveButton(code ? "保存代码" : "导出 CSV", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> copy.run());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save.run());
        });
        return dialog;
    }

    private static void showCode(LinearLayout body, ChatArtifacts.CodeBlock code, Colors colors) {
        Context context = body.getContext();
        var wrap = UiKit.toggle(context, "自动换行", colors.ink(), colors.accent(), colors.line(), false);
        body.addView(wrap, new LinearLayout.LayoutParams(-1, -2));

        var frame = new FrameLayout(context);
        frame.setBackground(UiKit.surface(context, colors.tint(), 8, colors.line()));
        body.addView(frame, new LinearLayout.LayoutParams(-1, -2));
        var horizontal = new HorizontalScrollView(context);
        horizontal.setFillViewport(true);
        var source = label(context, "", colors.ink(), 14);
        source.setTypeface(Typeface.MONOSPACE);
        source.setTextIsSelectable(true);
        source.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        source.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        int previewEnd = ChatArtifacts.boundary(code.source(), Math.min(code.source().length(), CODE_PREVIEW_LENGTH));
        source.setText(code.source().substring(0, previewEnd));
        int inset = dp(context, 10);
        source.setPadding(inset, inset, inset, inset);
        horizontal.addView(source, new FrameLayout.LayoutParams(-2, -2));
        frame.addView(horizontal, new FrameLayout.LayoutParams(-1, -2));
        wrap.setOnCheckedChangeListener((button, checked) -> {
            frame.removeAllViews();
            horizontal.removeAllViews();
            if (checked) frame.addView(source, new FrameLayout.LayoutParams(-1, -2));
            else {
                horizontal.addView(source, new FrameLayout.LayoutParams(-2, -2));
                frame.addView(horizontal, new FrameLayout.LayoutParams(-1, -2));
            }
        });
        if (previewEnd < code.source().length()) addLimit(body, "预览已截断，复制和保存仍为完整代码。", colors);
    }

    private static void showTable(LinearLayout body, ChatArtifacts.Table table, Colors colors) {
        Context context = body.getContext();
        var horizontal = new HorizontalScrollView(context);
        horizontal.setFillViewport(true);
        var grid = new TableLayout(context);
        int rowCount = Math.min(table.rows().size(), ROW_PREVIEW_LIMIT);
        int columnCount = Math.min(table.columns(), COLUMN_PREVIEW_LIMIT);
        for (int i = 0; i < rowCount; i++) {
            var row = new TableRow(context);
            row.setBackgroundColor(i == 0 ? colors.tint() : colors.panel());
            List<String> cells = table.rows().get(i);
            for (int column = 0; column < columnCount; column++) {
                String value = column < cells.size() ? cells.get(column) : "";
                int end = ChatArtifacts.boundary(value, Math.min(value.length(), 260));
                var cell = label(context, value.substring(0, end) + (end < value.length() ? "…" : ""), colors.ink(), 14);
                cell.setMaxLines(3);
                cell.setEllipsize(TextUtils.TruncateAt.END);
                int inset = dp(context, 8);
                cell.setPadding(inset, inset, inset, inset);
                cell.setAccessibilityHeading(i == 0);
                if (i == 0) cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                row.addView(cell, new TableRow.LayoutParams(dp(context, 124), -2));
            }
            grid.addView(row);
        }
        horizontal.addView(grid, new FrameLayout.LayoutParams(-2, -2));
        body.addView(horizontal);
        if (rowCount < table.rows().size() || columnCount < table.columns()) {
            addLimit(body, "预览仅显示部分行列，复制和导出仍为完整表格。", colors);
        }
    }

    private static void addLimit(LinearLayout body, String message, Colors colors) {
        var label = label(body.getContext(), message, colors.muted(), 13);
        label.setPadding(0, dp(body.getContext(), 8), 0, 0);
        body.addView(label);
    }

    private static TextView label(Context context, String content, int color, int size) {
        var text = new TextView(context);
        text.setText(content);
        text.setTextColor(color);
        text.setTextSize(size);
        return text;
    }

    private static int dp(Context context, int value) { return UiKit.dp(context, value); }
}
