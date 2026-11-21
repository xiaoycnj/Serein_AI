package app.serein.chat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.icu.text.BreakIterator;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

final class ReplyText extends TextView {
    private final ValueAnimator[] animators = new ValueAnimator[3];
    private final float[] progress = {1, 1, 1};
    private int started;

    ReplyText(Context context) { super(context); }

    boolean hasOpening() { return started > 0; }

    void update(SpannableStringBuilder content, boolean animateOpening, float radius) {
        if ((animateOpening && started < 3) || animating()) {
            int[][] ranges = opening(content.toString());
            for (int i = 0; i < ranges.length; i++) {
                if (animateOpening && i >= started) {
                    startReveal(i);
                    started = i + 1;
                }
                if (animators[i] != null) {
                    content.setSpan(new RevealSpan(i, radius), ranges[i][0], ranges[i][1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        setText(content, BufferType.SPANNABLE);
    }

    private boolean animating() {
        for (ValueAnimator animator : animators) if (animator != null) return true;
        return false;
    }

    private void startReveal(int index) {
        // Software text drawing supports mask filters on individual glyphs.
        if (!animating()) setLayerType(LAYER_TYPE_SOFTWARE, null);
        progress[index] = 0;
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animators[index] = animator;
        animator.setInterpolator(new PathInterpolator(.2f, .7f, .25f, 1));
        animator.setDuration(240);
        animator.addUpdateListener(frame -> {
            progress[index] = (float) frame.getAnimatedValue();
            if (progress[index] == 1f) {
                animators[index] = null;
                if (!animating()) clearReveal();
            }
            invalidate();
        });
        animator.start();
    }

    static int[][] opening(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        int[][] ranges = new int[3][];
        int count = 0;
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            int cp = text.codePointAt(start);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.getType(cp) == Character.FORMAT) continue;
            ranges[count++] = new int[]{start, end};
            if (count == 3) break;
        }
        return java.util.Arrays.copyOf(ranges, count);
    }

    private void clearReveal() {
        if (getText() instanceof android.text.Spannable text) {
            for (RevealSpan span : text.getSpans(0, text.length(), RevealSpan.class)) text.removeSpan(span);
        }
        setLayerType(LAYER_TYPE_NONE, null);
    }

    @Override protected void onDetachedFromWindow() {
        if (animating()) {
            for (int i = 0; i < animators.length; i++) {
                if (animators[i] != null) animators[i].cancel();
                animators[i] = null;
                progress[i] = 1;
            }
            clearReveal();
        }
        super.onDetachedFromWindow();
    }

    private final class RevealSpan extends CharacterStyle {
        private final int index;
        private final BlurMaskFilter[] blur = new BlurMaskFilter[10];

        RevealSpan(int index, float radius) {
            this.index = index;
            for (int i = 1; i < blur.length; i++) blur[i] = new BlurMaskFilter(radius * i / 9, BlurMaskFilter.Blur.NORMAL);
        }

        @Override public void updateDrawState(TextPaint paint) {
            float fraction = progress[index];
            paint.setAlpha(Math.round(paint.getAlpha() * fraction));
            paint.setMaskFilter(blur[Math.round((1 - fraction) * 9)]);
        }
    }
}
