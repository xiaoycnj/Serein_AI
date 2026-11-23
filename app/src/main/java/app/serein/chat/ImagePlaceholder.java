package app.serein.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.function.BooleanSupplier;

/** Android adaptation of the inline placeholder in AstraAgent's image-candidates.css. */
final class ImagePlaceholder extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final Matrix cloudTransform = new Matrix();
    private final RadialGradient cloud;
    private final int panel, border, particles;
    private final float density;
    private final long start = SystemClock.uptimeMillis();
    private final BooleanSupplier motionEnabled;

    ImagePlaceholder(Context context, int panel, int ink, int accent, int border, BooleanSupplier motionEnabled) {
        super(context);
        this.panel = panel;
        this.border = border;
        this.particles = Appearance.blend(ink, accent, .45f);
        this.motionEnabled = motionEnabled;
        density = getResources().getDisplayMetrics().density;
        cloud = new RadialGradient(0, 0, 1, (accent & 0xffffff) | 0x60000000,
                accent & 0xffffff, Shader.TileMode.CLAMP);
        setWillNotDraw(false);

        TextView label = new TextView(context);
        label.setText("正在生成图片");
        label.setTextColor(Appearance.readable(ink, Color.BLACK, panel));
        label.setTextSize(12);
        label.setPadding(px(10), px(5), px(10), px(5));
        var background = new android.graphics.drawable.GradientDrawable();
        background.setColor(panel);
        background.setCornerRadius(px(20));
        background.setStroke(px(1), border);
        label.setBackground(background);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        var placement = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        placement.setMargins(px(12), 0, px(12), px(12));
        addView(label, placement);
    }

    private int px(int value) { return Math.round(density * value); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        bounds.set(.5f * density, .5f * density, w - .5f * density, h - .5f * density);
        clip.reset();
        clip.addRoundRect(bounds, px(22), px(22), Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean moving = isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE
                && motionEnabled.getAsBoolean();
        double time = moving ? (SystemClock.uptimeMillis() - start) / 1000.0 : 0;
        float w = getWidth(), h = getHeight();
        float drift = wave(time / 9.6);
        int saved = canvas.save();
        canvas.clipPath(clip);
        canvas.drawColor(panel);
        cloudTransform.setScale(w * (.29f + .2f * drift), h * (.26f + .19f * drift));
        cloudTransform.postTranslate(w * (.3f + .36f * drift), h * (.26f + .28f * drift));
        cloud.setLocalMatrix(cloudTransform);
        paint.setColor(Color.WHITE);
        paint.setShader(cloud);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        canvas.save();
        canvas.rotate(-8 + 24 * wave(time / 11.2), w * .5f, h * .46f);
        paint.setColor(particles);
        for (int row = 0; row < 9; row++) for (int col = 0; col < 9; col++) {
            float phase = moving ? wave((time + col * .105 + row * .155) / 2.8) : .22f;
            paint.setAlpha(Math.round(56 + 140 * phase));
            float x = w * (.15f + .0875f * col) + (col - 4) * density * .8f * phase;
            float y = h * (.13f + .079f * row) - density * 7 * phase;
            canvas.drawCircle(x, y, density * (.8f + 1.5f * phase), paint);
        }
        canvas.restore();
        canvas.restoreToCount(saved);
        paint.setColor(border);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        canvas.drawRoundRect(bounds, px(22), px(22), paint);
        paint.setStyle(Paint.Style.FILL);
        if (moving) postInvalidateDelayed(32);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) invalidate();
    }

    private static float wave(double cycle) { return (float) ((1 - Math.cos(2 * Math.PI * cycle)) / 2); }
}
