package cn.wildfire.chat.kit.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import cn.wildfirechat.chat.R;

/** Floating material bar. Position is continuous, shared by the pager and the spring. */
public class GlassTabBar extends ViewGroup implements GlassBackdropRenderer.Target {
    @Override public View backdropView() { return this; }
    public interface OnSelectListener { void onSelect(int index); }

    private final FrameLayout[] items = new FrameLayout[3];
    private final ImageView[] icons = new ImageView[3];
    private final TextView[] labels = new TextView[3];
    private final TextView[] badges = new TextView[3];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final int normalColor, selectedColor;
    private float position, target, velocity, scale = 1, targetScale = 1, scaleVelocity;
    private int selectedIndex, hoveredIndex;
    private boolean dragging, running;
    private long lastFrame;
    private Bitmap backdrop;
    private float backdropPadding;
    private float surfaceActivity, targetSurfaceActivity, surfaceActivityVelocity;
    private float surfaceShift, targetSurfaceShift, surfaceShiftVelocity;
    private RadialGradient surfaceLight;
    private final Matrix surfaceLightMatrix = new Matrix();
    private OnSelectListener listener;

    public GlassTabBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClipChildren(false);
        normalColor = color(R.color.bottom_nav_unselected_color);
        selectedColor = color(R.color.bottom_nav_selected_color);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(color(R.color.glass_tab_fallback));
        outline.setCornerRadius(dp(31));
        setBackground(outline);
        setElevation(dp(8));
        int[] titles = {R.string.glass_tab_messages, R.string.glass_tab_contacts, R.string.glass_tab_me};
        int[] images = {R.drawable.ic_nav_chat_state, R.drawable.ic_nav_contacts_state, R.drawable.ic_nav_me_state};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            FrameLayout item = new FrameLayout(context);
            items[i] = item;
            item.setFocusable(true);
            item.setContentDescription(context.getString(titles[i]));
            item.setOnClickListener(v -> { if (listener != null) listener.onSelect(index); });
            icons[i] = new ImageView(context);
            icons[i].setImageResource(images[i]);
            icons[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams((int) dp(24), (int) dp(24), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            iconParams.topMargin = (int) dp(9);
            item.addView(icons[i], iconParams);
            labels[i] = new TextView(context);
            labels[i].setText(titles[i]);
            labels[i].setTextSize(11);
            labels[i].setGravity(android.view.Gravity.CENTER);
            labels[i].setTypeface(null, android.graphics.Typeface.BOLD);
            labels[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, (int) dp(20), Gravity.TOP);
            labelParams.topMargin = (int) dp(35);
            item.addView(labels[i], labelParams);
            badges[i] = new TextView(context);
            badges[i].setTextColor(0xffffffff);
            badges[i].setTextSize(10);
            badges[i].setGravity(android.view.Gravity.CENTER);
            badges[i].setPadding((int) dp(4), 0, (int) dp(4), 0);
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(color(R.color.coral));
            badge.setCornerRadius(dp(8));
            badges[i].setBackground(badge);
            badges[i].setVisibility(GONE);
            badges[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (int) dp(16), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            badgeParams.topMargin = (int) dp(5);
            item.addView(badges[i], badgeParams);
            addView(item);
        }
        applyColors();
    }

    private int color(int resource) { return getResources().getColor(resource); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    public void setOnSelectListener(OnSelectListener listener) { this.listener = listener; }
    public void setSelectedIndex(int index) {
        selectedIndex = Math.max(0, Math.min(2, index));
        for (int i = 0; i < 3; i++) items[i].setSelected(i == selectedIndex);
    }
    public void setBadge(int index, int count) {
        String text = count > 99 ? "99+" : String.valueOf(count);
        badges[index].setText(text);
        badges[index].setVisibility(count > 0 ? VISIBLE : GONE);
        items[index].setContentDescription(count > 0
                ? getResources().getString(R.string.glass_tab_unread, labels[index].getText(), text)
                : labels[index].getText());
        requestLayout();
    }
    public void setPosition(float value, boolean animated) {
        if (dragging) return;
        target = Math.max(0, Math.min(2, value));
        if (animated) startSpring();
        else { position = target; velocity = 0; applyColors(); invalidate(); }
    }
    /** Material response is shared by every page, including short pages with a plain backdrop. */
    public void setScrollEffect(float shift, float activity) {
        targetSurfaceShift = Math.max(-1, Math.min(1, shift));
        targetSurfaceActivity = Math.max(0, Math.min(1, activity));
        startSpring();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0) {
            surfaceLight = new RadialGradient(0, 0, width * .65f,
                    0xffffffff, 0x00ffffff, Shader.TileMode.CLAMP);
        }
    }

    public void setBackdrop(Bitmap bitmap, float padding) {
        backdrop = bitmap;
        backdropPadding = padding;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.min(MeasureSpec.getSize(widthSpec), getResources().getDimensionPixelSize(R.dimen.glass_tab_max_width));
        int height = resolveSize((int) dp(62), heightSpec);
        setMeasuredDimension(width, height);
        int slot = (int) ((width - dp(8)) / 3);
        for (FrameLayout item : items) item.measure(MeasureSpec.makeMeasureSpec(slot, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float slot = (getWidth() - dp(8)) / 3f;
        for (int i = 0; i < 3; i++) {
            int x = Math.round(dp(4) + i * slot), width = Math.round(slot);
            items[i].layout(x, 0, x + width, getHeight());
            // Anchor the badge's left edge just to the right of the icon center.
            badges[i].setTranslationX(dp(6) + badges[i].getMeasuredWidth() / 2f);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        bounds.set(0, 0, getWidth(), getHeight());
        clip.reset();
        clip.addRoundRect(bounds, getHeight() / 2f, getHeight() / 2f, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        if (backdrop != null) {
            paint.setColor(0xffffffff);
            canvas.drawBitmap(backdrop, null, new RectF(-backdropPadding, -backdropPadding,
                    getWidth() + backdropPadding, getHeight() + backdropPadding), paint);
        }
        int material = color(backdrop == null ? R.color.glass_tab_fallback : R.color.glass_tab_material);
        float activity = Math.max(0, Math.min(1, surfaceActivity));
        // Reveal a little more of the real blurred scene while scrolling. The fallback remains
        // opaque enough to read when no sample is available.
        if (backdrop != null) {
            int alpha = Math.round(android.graphics.Color.alpha(material) * (1 - .18f * activity));
            material = ColorUtils.setAlphaComponent(material, alpha);
        }
        paint.setColor(material);
        canvas.drawPaint(paint);
        if (surfaceLight != null && activity > .001f) {
            surfaceLightMatrix.setTranslate(getWidth() * (.3f + surfaceShift * .12f),
                    getHeight() * (.5f + surfaceShift * .8f));
            surfaceLight.setLocalMatrix(surfaceLightMatrix);
            paint.setShader(surfaceLight);
            paint.setAlpha(Math.round(30 * activity));
            canvas.drawPaint(paint);
            paint.setShader(null);
        }
        canvas.restoreToCount(save);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(color(R.color.glass_tab_border));
        bounds.inset(dp(.5f), dp(.5f));
        canvas.drawRoundRect(bounds, getHeight() / 2f, getHeight() / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
        float slot = (getWidth() - dp(8)) / 3f;
        float stretch = Math.min(Math.abs(velocity) * .035f, .22f);
        float halfWidth = slot * scale * (1 + stretch) / 2;
        float halfHeight = (getHeight() - dp(8)) * scale * (1 - stretch * .4f) / 2;
        float cx = dp(4) + (position + .5f) * slot, cy = getHeight() / 2f;
        bounds.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        paint.setColor(color(R.color.glass_tab_indicator));
        canvas.drawRoundRect(bounds, halfHeight, halfHeight, paint);
    }
    private void applyColors() {
        for (int i = 0; i < 3; i++) {
            float weight = Math.max(0, 1 - Math.abs(position - i));
            int tint = ColorUtils.blendARGB(normalColor, selectedColor, weight);
            icons[i].setImageState(weight > .5f ? new int[]{android.R.attr.state_checked} : new int[0], true);
            icons[i].setColorFilter(tint);
            labels[i].setTextColor(tint);
        }
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override public boolean onTouchEvent(MotionEvent event) {
        float finger = Math.max(0, Math.min(2, (event.getX() - dp(4)) / ((getWidth() - dp(8)) / 3f) - .5f));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                hoveredIndex = Math.round(finger);
                targetScale = 1.12f;
                target = finger;
                startSpring();
                return true;
            case MotionEvent.ACTION_MOVE:
                target = finger;
                if (hoveredIndex != Math.round(finger)) {
                    hoveredIndex = Math.round(finger);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                startSpring();
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                targetScale = 1;
                target = Math.round(finger);
                startSpring();
                items[(int) target].performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                targetScale = 1;
                target = selectedIndex;
                startSpring();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }
    private void startSpring() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(spring);
    }
    private final Runnable spring = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min((now - lastFrame) / 1000f, 1f / 30);
            lastFrame = now;
            // Substeps keep the damped spring stable even after a dropped frame.
            for (int i = 0; i < 2; i++) {
                float step = dt / 2;
                velocity += (380 * (target - position) - 29.24f * velocity) * step;
                position += velocity * step;
                scaleVelocity += (380 * (targetScale - scale) - 29.24f * scaleVelocity) * step;
                scale += scaleVelocity * step;
                surfaceActivityVelocity += (380 * (targetSurfaceActivity - surfaceActivity) - 29.24f * surfaceActivityVelocity) * step;
                surfaceActivity += surfaceActivityVelocity * step;
                surfaceShiftVelocity += (380 * (targetSurfaceShift - surfaceShift) - 29.24f * surfaceShiftVelocity) * step;
                surfaceShift += surfaceShiftVelocity * step;
            }
            boolean settled = Math.abs(target - position) < .001f && Math.abs(velocity) < .01f
                    && Math.abs(targetScale - scale) < .001f && Math.abs(scaleVelocity) < .01f
                    && Math.abs(targetSurfaceActivity - surfaceActivity) < .001f && Math.abs(surfaceActivityVelocity) < .01f
                    && Math.abs(targetSurfaceShift - surfaceShift) < .001f && Math.abs(surfaceShiftVelocity) < .01f;
            if (settled) {
                position = target; scale = targetScale; velocity = scaleVelocity = 0;
                surfaceActivity = targetSurfaceActivity; surfaceShift = targetSurfaceShift;
                surfaceActivityVelocity = surfaceShiftVelocity = 0;
            }
            applyColors();
            invalidate();
            running = !settled;
            if (running) postOnAnimation(this);
        }
    };
    public void finishInteraction() {
        removeCallbacks(spring);
        running = dragging = false;
        position = target = selectedIndex;
        scale = targetScale = 1;
        velocity = scaleVelocity = 0;
        surfaceActivity = targetSurfaceActivity = surfaceActivityVelocity = 0;
        surfaceShift = targetSurfaceShift = surfaceShiftVelocity = 0;
        applyColors();
        invalidate();
    }
    @Override protected void onDetachedFromWindow() {
        finishInteraction();
        backdrop = null;
        super.onDetachedFromWindow();
    }
}
