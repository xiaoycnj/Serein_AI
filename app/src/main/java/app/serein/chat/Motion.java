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
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (enabled.getAsBoolean() && button.isEnabled()) button.animate().scaleX(.98f).scaleY(.95f)
                            .setInterpolator(EASE).setDuration(95).start();
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (enabled.getAsBoolean()) button.animate().scaleX(1).scaleY(1)
                            .setInterpolator(SPRING).setDuration(290).start();
                    else {
                        button.animate().cancel();
                        button.setScaleX(1);
                        button.setScaleY(1);
                    }
                }
            }
            return false;
        });
    }
}
