package cn.wildfire.chat.kit.conversation.message;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.List;

import cn.wildfire.chat.kit.widget.dialog.FshareContextMenu;
import cn.wildfirechat.chat.R;

/**
 * 微信式消息选字：长按文本气泡后全选，文字铺半透明高亮，首尾两个绿色圆点手柄拖动调整选区，
 * 菜单卡片贴着选区弹出。
 *
 * <p>不用 {@link FshareContextMenu}（Dialog）是因为它是模态窗口：手柄放在 Activity 窗口里会被
 * Dialog 盖住、收不到触摸。这里把菜单卡片和手柄都放进一个铺满 Activity 内容区的浮层里，
 * 同一个窗口、同一套坐标。
 *
 * <p>列表区域的触摸放行给 RecyclerView：可以照常滑动（滑动时菜单隐藏、静止后在选区旁重新出现，
 * 选区保留），点一下则退出选字。列表外（标题栏、输入栏）点一下、按返回键、气泡被复用/重新绑定、
 * 长按别的消息，也都会关闭浮层。
 */
@SuppressLint("ViewConstructor")
public class TextSelectionOverlay extends FrameLayout {

    public interface Listener {
        /** 点了菜单项。浮层不会自动关闭，由调用方先取选中内容再 {@link #dismiss()}。 */
        void onMenuItemClick(int position, @NonNull FshareContextMenu.Item item);

        void onDismiss();
    }

    private static final int HANDLE_COLOR = 0xFF07C160;
    private static final int MENU_MAX_WIDTH_DP = 240;
    private static final int SCREEN_MARGIN_DP = 12;
    private static final int MENU_GAP_DP = 4;

    private final TextView target;
    private final Spannable text;
    private final BackgroundColorSpan highlightSpan;
    private final HandleView startHandle;
    private final HandleView endHandle;
    /** fshare_context_menu 外层 FrameLayout，带 8dp 阴影边距。 */
    private final View menuCard;
    private final Listener listener;

    /** 同一时间只有一个选字浮层，长按别的消息时先关掉旧的。 */
    private static WeakReference<TextSelectionOverlay> current;

    @Nullable
    private final RecyclerView list;
    private int selStart;
    private int selEnd;
    private boolean dragging;
    private boolean listScrolling;
    private boolean dismissed;

    private final int[] tmpLocation = new int[2];

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        updatePositions();
        return true;
    };
    private final RecyclerView.OnScrollListener listScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            listScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
            if (!dragging) {
                menuCard.setVisibility(listScrolling ? INVISIBLE : VISIBLE);
            }
            updatePositions();
        }
    };
    /**
     * 列表区域的触摸本来就交给 RecyclerView，这里只认「点一下」：在 UP 时拦截下来，
     * 子 View 收到 CANCEL，不会触发气泡自己的点击（打开链接、看图等），只退出选字。
     */
    private final RecyclerView.OnItemTouchListener listTapListener = new RecyclerView.SimpleOnItemTouchListener() {
        private float downX;
        private float downY;
        private long downTime;
        private boolean moved;

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downTime = e.getEventTime();
                    moved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                        moved = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    // 长按别的消息会走它自己的长按逻辑（新浮层会顶掉这个），这里只处理短按
                    if (!moved && e.getEventTime() - downTime < ViewConfiguration.getLongPressTimeout()) {
                        post(TextSelectionOverlay.this::dismiss);
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        }
    };
    private final OnAttachStateChangeListener targetAttachListener = new OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            dismiss();
        }
    };

    /**
     * 全选 {@code target} 的文字并弹出浮层；文字为空或还没排版时返回 null。
     */
    @Nullable
    public static TextSelectionOverlay show(@NonNull Activity activity, @NonNull TextView target, int highlightColor,
                                            @NonNull List<FshareContextMenu.Item> items, @NonNull Listener listener) {
        CharSequence content = target.getText();
        if (content.length() == 0 || target.getLayout() == null) {
            return null;
        }
        if (!(content instanceof Spannable)) {
            // 折叠态摘要是用 setText(String) 设的普通文本，加高亮 span 需要 Spannable
            target.setText(content, TextView.BufferType.SPANNABLE);
        }
        dismissCurrent();
        ViewGroup root = activity.findViewById(android.R.id.content);
        TextSelectionOverlay overlay = new TextSelectionOverlay(activity, target, highlightColor, items, listener);
        root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.requestFocus();
        current = new WeakReference<>(overlay);
        return overlay;
    }

    /** 关闭当前的选字浮层（如果有）。 */
    public static void dismissCurrent() {
        TextSelectionOverlay overlay = current != null ? current.get() : null;
        if (overlay != null) {
            overlay.dismiss();
        }
    }

    private TextSelectionOverlay(Context context, TextView target, int highlightColor,
                                 List<FshareContextMenu.Item> items, Listener listener) {
        super(context);
        this.target = target;
        this.text = (Spannable) target.getText();
        this.listener = listener;
        this.highlightSpan = new BackgroundColorSpan(highlightColor);
        this.list = findList(target);
        selStart = 0;
        selEnd = text.length();
        text.setSpan(highlightSpan, selStart, selEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        startHandle = new HandleView(context, true);
        endHandle = new HandleView(context, false);
        int handleSize = dp(44);
        addView(startHandle, new LayoutParams(handleSize, handleSize));
        addView(endHandle, new LayoutParams(handleSize, handleSize));

        menuCard = LayoutInflater.from(context).inflate(R.layout.fshare_context_menu, this, false);
        LinearLayout container = menuCard.findViewById(R.id.menuContainer);
        FshareContextMenu.populate(container, items, listener::onMenuItemClick);
        // 先按内容量出卡片宽度并定死，再把每行撑满（与 FshareContextMenu.position() 同理）
        container.measure(MeasureSpec.makeMeasureSpec(dp(MENU_MAX_WIDTH_DP), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        container.getLayoutParams().width = container.getMeasuredWidth();
        FshareContextMenu.stretchRows(container);
        addView(menuCard);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** 部分选中时返回选中的显示文字；全选时返回 null，由调用方用消息原文。 */
    @Nullable
    public String getPartialSelectionText() {
        if (selStart == 0 && selEnd == text.length()) {
            return null;
        }
        // 以图片等替换型 span 渲染的内容在文本里只是一个 U+FFFC 占位符
        return text.subSequence(selStart, selEnd).toString().replace("￼", "");
    }

    public void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        text.removeSpan(highlightSpan);
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(this);
        }
        listener.onDismiss();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        target.addOnAttachStateChangeListener(targetAttachListener);
        if (list != null) {
            list.addOnScrollListener(listScrollListener);
            list.addOnItemTouchListener(listTapListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        target.removeOnAttachStateChangeListener(targetAttachListener);
        if (list != null) {
            list.removeOnScrollListener(listScrollListener);
            list.removeOnItemTouchListener(listTapListener);
        }
        super.onDetachedFromWindow();
        // Activity 销毁等情况下被动移除时也要清掉高亮
        dismiss();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 手柄和菜单行自己消费触摸，落到这里的是浮层空白处（或卡片的阴影边距）
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Rect card = new Rect();
            menuCard.getHitRect(card); // 已含 translation
            if (menuCard.getVisibility() == VISIBLE && card.contains((int) event.getX(), (int) event.getY())) {
                return true;
            }
            // 列表区域不消费 DOWN，整串触摸落到下面的 RecyclerView：滑动照常，点一下由 listTapListener 退出选字
            if (list != null && visibleListRect().contains(event.getX(), event.getY())) {
                return false;
            }
            dismiss();
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                dismiss();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- 坐标换算 ----

    /** target 左上角在浮层坐标系里的位置。 */
    private void targetOrigin(float[] out) {
        target.getLocationInWindow(tmpLocation);
        int tx = tmpLocation[0], ty = tmpLocation[1];
        getLocationInWindow(tmpLocation);
        out[0] = tx - tmpLocation[0];
        out[1] = ty - tmpLocation[1];
    }

    /** 浮层坐标 → 最近的字符插入位置。 */
    private int offsetAt(float x, float y) {
        Layout layout = target.getLayout();
        float[] origin = new float[2];
        targetOrigin(origin);
        float lx = x - origin[0] - target.getTotalPaddingLeft() + target.getScrollX();
        float ly = y - origin[1] - target.getTotalPaddingTop() + target.getScrollY();
        int line = layout.getLineForVertical((int) ly);
        int offset = layout.getOffsetForHorizontal(line, lx);
        // 不要停在代理对中间，否则 emoji 会被切成两半
        if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset))) {
            offset--;
        }
        return offset;
    }

    /**
     * 插入位置处的光标（浮层坐标）：[x, lineTop, lineBottom]。选区终点取前一个字符所在行，
     * 这样折行处的终点停在上一行行尾，而不是下一行行首。
     */
    private float[] caretAt(int offset, boolean atRangeEnd) {
        Layout layout = target.getLayout();
        float[] origin = new float[2];
        targetOrigin(origin);
        int line;
        float x;
        if (atRangeEnd && offset > 0) {
            line = layout.getLineForOffset(offset - 1);
            x = offset >= layout.getLineEnd(line) ? layout.getLineRight(line) : layout.getPrimaryHorizontal(offset);
        } else {
            line = layout.getLineForOffset(offset);
            x = layout.getPrimaryHorizontal(offset);
        }
        float left = origin[0] + target.getTotalPaddingLeft() - target.getScrollX();
        float top = origin[1] + target.getTotalPaddingTop() - target.getScrollY();
        return new float[]{left + x, top + layout.getLineTop(line), top + layout.getLineBottom(line)};
    }

    private void setSelection(int start, int end) {
        if (start == selStart && end == selEnd) {
            return;
        }
        selStart = start;
        selEnd = end;
        text.removeSpan(highlightSpan);
        text.setSpan(highlightSpan, selStart, selEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        updatePositions();
    }

    private void updatePositions() {
        if (dismissed || target.getLayout() == null) {
            return;
        }
        float[] startCaret = caretAt(selStart, false);
        float[] endCaret = caretAt(selEnd, true);
        startHandle.placeAt(startCaret[0], startCaret[2]);
        endHandle.placeAt(endCaret[0], endCaret[2]);
        // 手柄画在浮层里，不受 RecyclerView 裁剪；所在行滑出列表可见区域就藏起来，免得盖在标题栏/输入栏上
        RectF visible = visibleListRect();
        startHandle.setVisibility(startCaret[2] > visible.top && startCaret[1] < visible.bottom ? VISIBLE : INVISIBLE);
        endHandle.setVisibility(endCaret[2] > visible.top && endCaret[1] < visible.bottom ? VISIBLE : INVISIBLE);
        if (!dragging && !listScrolling) {
            placeMenu(startCaret[1], endCaret[2]);
        }
    }

    /**
     * 菜单优先放在选区上方，放不下就放下方，都放不下（长消息全选）就放在可见区域中间。
     * 选区先裁到列表可见范围内，否则超出屏幕的部分会把菜单算到屏幕外。
     */
    private void placeMenu(float selectionTop, float selectionBottom) {
        int cardWidth = menuCard.getWidth();
        int cardHeight = menuCard.getHeight();
        if (cardWidth == 0 || cardHeight == 0) {
            return;
        }
        RectF visible = visibleListRect();
        float top = Math.max(selectionTop, visible.top);
        float bottom = Math.min(selectionBottom, visible.bottom);
        // 选区已滑出可见区域：菜单先藏着，滑回来静止时再出现
        if (top >= bottom) {
            menuCard.setVisibility(INVISIBLE);
            return;
        }
        menuCard.setVisibility(VISIBLE);
        int margin = dp(SCREEN_MARGIN_DP);
        int gap = dp(MENU_GAP_DP);
        // 卡片外层有 8dp 透明阴影边距，贴选区时把它算进间距里
        float y;
        if (top - gap - cardHeight >= visible.top) {
            y = top - gap - cardHeight;
        } else if (bottom + gap + cardHeight <= visible.bottom) {
            y = bottom + gap;
        } else {
            y = visible.centerY() - cardHeight / 2f;
        }
        y = Math.max(margin, Math.min(y, getHeight() - cardHeight - margin));

        float[] origin = new float[2];
        targetOrigin(origin);
        float x = origin[0] + (target.getWidth() - cardWidth) / 2f;
        x = Math.max(margin, Math.min(x, getWidth() - cardWidth - margin));
        menuCard.setTranslationX(x);
        menuCard.setTranslationY(y);
    }

    @Nullable
    private static RecyclerView findList(View view) {
        View v = view;
        while (v.getParent() instanceof View && !(v instanceof RecyclerView)) {
            v = (View) v.getParent();
        }
        return v instanceof RecyclerView ? (RecyclerView) v : null;
    }

    private RectF visibleListRect() {
        if (list == null) {
            return new RectF(0, 0, getWidth(), getHeight());
        }
        list.getLocationInWindow(tmpLocation);
        int lx = tmpLocation[0], ly = tmpLocation[1];
        getLocationInWindow(tmpLocation);
        float left = lx - tmpLocation[0], top = ly - tmpLocation[1];
        return new RectF(left, top, left + list.getWidth(), top + list.getHeight());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 选区手柄：一个绿色圆点挂在光标所在行底部；View 本身是 44dp 的触摸热区。 */
    private class HandleView extends View {
        private final boolean isStart;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius = dp(8);
        private float touchOffsetX;
        private float touchOffsetY;

        HandleView(Context context, boolean isStart) {
            super(context);
            this.isStart = isStart;
            paint.setColor(HANDLE_COLOR);
        }

        /** 圆点圆心放在光标底部附近：起点手柄偏左、终点手柄偏右，各自朝选区外侧。 */
        void placeAt(float caretX, float lineBottom) {
            float cx = isStart ? caretX - radius + dp(3) : caretX + radius - dp(3);
            float cy = lineBottom + radius - dp(4);
            setTranslationX(cx - getWidth() / 2f);
            setTranslationY(cy - getHeight() / 2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // 换算到浮层坐标（手柄自身有 translation，用 raw 坐标最稳）
            TextSelectionOverlay.this.getLocationOnScreen(tmpLocation);
            float x = event.getRawX() - tmpLocation[0];
            float y = event.getRawY() - tmpLocation[1];
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // 手指按在圆点上（行的下方），换算字符时改用手柄所指那一行的中线，免得一碰就跳到下一行
                    float[] caret = isStart ? caretAt(selStart, false) : caretAt(selEnd, true);
                    touchOffsetX = caret[0] - x;
                    touchOffsetY = (caret[1] + caret[2]) / 2f - y;
                    dragging = true;
                    menuCard.setVisibility(INVISIBLE);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int offset = offsetAt(x + touchOffsetX, y + touchOffsetY);
                    // 两个手柄不交叉，选区至少保留一个字符
                    if (isStart) {
                        int start = Math.min(offset, selEnd - 1);
                        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
                            start--;
                        }
                        setSelection(Math.max(start, 0), selEnd);
                    } else {
                        int end = Math.max(offset, selStart + 1);
                        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
                            end++;
                        }
                        setSelection(selStart, Math.min(end, text.length()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    updatePositions();
                    return true;
                default:
                    return true;
            }
        }
    }
}
