package cn.wildfire.chat.kit.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import cn.wildfirechat.chat.R;

/** One sampled background shared by the plus circle and the input capsule. Never draws the editor. */
public final class ComposerGlassView extends View implements GlassBackdropRenderer.Target {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path shape = new Path();
    private final RectF plus = new RectF(), capsule = new RectF(), destination = new RectF();
    private Bitmap backdrop;
    private float padding;

    public ComposerGlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
    }
    @Override public View backdropView() { return this; }
    @Override public void setBackdrop(Bitmap bitmap, float padding) {
        backdrop = bitmap;
        this.padding = padding;
        invalidate();
    }
    public void setShapes(View plusView, View capsuleView) {
        plus.set(plusView.getLeft(), plusView.getTop(), plusView.getRight(), plusView.getBottom());
        capsule.set(capsuleView.getLeft(), capsuleView.getTop(), capsuleView.getRight(), capsuleView.getBottom());
        invalidate();
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // A match-parent decoration in a wrap-content FrameLayout must not contribute its
        // initial AT_MOST height; the editor determines the dock height in the first pass.
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        shape.reset();
        shape.addRoundRect(plus, plus.height() / 2, plus.height() / 2, Path.Direction.CW);
        shape.addRoundRect(capsule, dp(24), dp(24), Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(shape);
        if (backdrop != null && !backdrop.isRecycled()) {
            paint.setColor(0xffffffff);
            destination.set(-padding, -padding, getWidth() + padding, getHeight() + padding);
            canvas.drawBitmap(backdrop, null, destination, paint);
        }
        paint.setColor(getResources().getColor(backdrop == null ? R.color.composer_fallback : R.color.composer_material));
        canvas.drawPaint(paint);
        canvas.restoreToCount(save);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(getResources().getColor(R.color.composer_border));
        canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
