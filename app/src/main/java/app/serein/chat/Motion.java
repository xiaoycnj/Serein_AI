package app.serein.chat;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

final class Motion {
    private static final TimeInterpolator SPRING = fraction -> fraction >= 1f ? 1f :
            (float) (1 - Math.exp(-7 * fraction) * (Math.cos(10 * fraction) + .7 * Math.sin(10 * fraction)));
    private static final TimeInterpolator EASE = new PathInterpolator(.2f, .78f, .25f, 1f);

    static void enter(View view, float dx, float dy, float scale, boolean enabled) {
        if (!enabled) return;
        view.setTranslationX(dx);
        view.setTranslationY(dy);
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setAlpha(.82f);
        view.animate().translationX(0).translationY(0).scaleX(1).scaleY(1).alpha(1)
                .setInterpolator(SPRING).setDuration(330).start();
    }

    static void press(View view, java.util.function.BooleanSupplier enabled) {
        view.setOnTouchListener((button, event) -> {
            if (!enabled.getAsBoolean() || !button.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> button.animate().scaleX(.96f).scaleY(.93f)
                        .setInterpolator(EASE).setDuration(95).start();
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> button.animate().scaleX(1).scaleY(1)
                        .setInterpolator(SPRING).setDuration(320).start();
            }
            return false;
        });
    }
}
