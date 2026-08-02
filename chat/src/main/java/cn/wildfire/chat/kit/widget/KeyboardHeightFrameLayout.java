package cn.wildfire.chat.kit.widget;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class KeyboardHeightFrameLayout extends FrameLayout implements InputAwareLayout.InputView {
    public KeyboardHeightFrameLayout(@NonNull Context context) {
        super(context);
    }

    public KeyboardHeightFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public KeyboardHeightFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public KeyboardHeightFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void show(int height, boolean immediate) {
        applyPanelHeight(height);
        getChildAt(0).setVisibility(VISIBLE);
        setVisibility(VISIBLE);
    }

    /**
     * 面板高度与键盘高度保持一致（不小于自身 minHeight），面板隐藏时也可以提前设置，
     * 这样再次显示时不会因为高度变化引起整个会话界面重新布局。
     */
    public void applyPanelHeight(int height) {
        int target = Math.max(height, getMinimumHeight());
        if (target <= 0) {
            // 根布局还没测量出来时拿不到有效的键盘高度，保留布局里的默认高度
            return;
        }
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (layoutParams != null && layoutParams.height != target) {
            layoutParams.height = target;
            setLayoutParams(layoutParams);
        }
    }

    @Override
    public void hide(boolean immediate) {
        setVisibility(GONE);
    }

    @Override
    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }
}
