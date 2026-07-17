package cn.wildfire.chat.kit.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;

/**
 * 媒体预览页根容器：未缩放时支持下滑拖动关闭（参考 iOS 图片/视频预览交互）。
 * 拖动跟手平移并轻微缩放，通过 {@link Callback#onSwipeProgress(float)} 通知外部调整背景透明度，
 * 松手时超过阈值回调 {@link Callback#onDismiss()}，否则回弹。
 */
public class SwipeDismissLayout extends RelativeLayout {

    public interface Callback {
        /**
         * 是否允许开始下滑关闭（例如图片被放大时应返回 false，让 PhotoView 自己处理拖动）
         */
        boolean canSwipeDismiss();

        /**
         * @param progress 0~1，拖动进度
         */
        void onSwipeProgress(float progress);

        void onDismiss();

        void onSwipeCancel();
    }

    private static final float MIN_SCALE = 0.75f;

    private Callback callback;
    private final int touchSlop;
    private float downRawX;
    private float downRawY;
    private boolean dragging;

    public SwipeDismissLayout(Context context) {
        this(context, null);
    }

    public SwipeDismissLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (shouldStartDrag(ev)) {
                    startDrag();
                    return true;
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                dragging = false;
                // 子 view 不消费事件时（如视频播放中），由本容器接管后续 MOVE
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && shouldStartDrag(event)) {
                    startDrag();
                }
                if (dragging) {
                    float dy = Math.max(0, event.getRawY() - downRawY);
                    float dx = event.getRawX() - downRawX;
                    float progress = Math.min(1f, dy / (getHeight() * 0.5f));
                    float scale = 1 - (1 - MIN_SCALE) * progress;
                    setTranslationX(dx);
                    setTranslationY(dy);
                    setScaleX(scale);
                    setScaleY(scale);
                    if (callback != null) {
                        callback.onSwipeProgress(progress);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    float dy = event.getRawY() - downRawY;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && dy > getHeight() / 4f) {
                        if (callback != null) {
                            callback.onDismiss();
                        }
                    } else {
                        animate().translationX(0).translationY(0).scaleX(1).scaleY(1).setDuration(200).start();
                        if (callback != null) {
                            callback.onSwipeCancel();
                        }
                    }
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean shouldStartDrag(MotionEvent ev) {
        if (callback == null || !callback.canSwipeDismiss()) {
            return false;
        }
        float dx = ev.getRawX() - downRawX;
        float dy = ev.getRawY() - downRawY;
        return dy > touchSlop && dy > Math.abs(dx) * 1.5f;
    }

    private void startDrag() {
        dragging = true;
        // 防止 ViewPager 中途把事件抢走
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    /**
     * 恢复拖动前的位置与缩放（页面复用时调用）
     */
    public void reset() {
        dragging = false;
        setTranslationX(0);
        setTranslationY(0);
        setScaleX(1);
        setScaleY(1);
    }
}
