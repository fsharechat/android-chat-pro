package cn.wildfire.chat.kit.widget.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.wildfirechat.chat.R;

/**
 * iOS 风格的锚定弹出菜单：贴着被长按/被点击的控件弹出，圆角卡片 + 左图标右文案，
 * 危险项标红，没有「取消」按钮（点外部或返回键关闭）。
 *
 * <p>用 {@link Dialog} 而不是 PopupWindow，是为了免费拿到背景变暗、返回键关闭和
 * 点击外部关闭；位置靠 {@link WindowManager.LayoutParams} 的 x/y 手动摆放。
 *
 * <p>三个使用场景：会话列表长按（锚点是会话行）、消息长按（锚点是消息气泡）、
 * 首页顶栏「+」（锚点是工具栏按钮）。
 */
public class FshareContextMenu extends Dialog {

    /** 菜单卡片距屏幕左右边缘的最小间距。 */
    private static final int SCREEN_MARGIN_DP = 12;
    /** 卡片与锚点之间的间距。 */
    private static final int ANCHOR_GAP_DP = 4;
    /** 卡片最大宽度，避免长菜单项把卡片撑得过宽。 */
    private static final int MAX_WIDTH_DP = 240;

    public interface Callback {
        void onSelect(int position, @NonNull Item item);
    }

    /** 一个菜单项。{@code iconRes} 传 0 表示不显示图标。 */
    public static class Item {
        public final CharSequence title;
        @DrawableRes
        public final int iconRes;
        /** 危险项：文案与图标标红（删除、清空这类不可逆操作）。 */
        public final boolean destructive;

        public Item(CharSequence title, @DrawableRes int iconRes, boolean destructive) {
            this.title = title;
            this.iconRes = iconRes;
            this.destructive = destructive;
        }

        public Item(CharSequence title, @DrawableRes int iconRes) {
            this(title, iconRes, false);
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final View anchor;
    private Callback callback;

    /**
     * @param anchor 菜单要贴着弹出的控件。菜单默认出现在它下方，
     *               下方空间不够时翻到上方。
     */
    public FshareContextMenu(@NonNull Context context, @NonNull View anchor) {
        super(context, R.style.FshareContextMenu);
        this.anchor = anchor;
    }

    public FshareContextMenu items(@NonNull List<Item> items) {
        this.items.clear();
        this.items.addAll(items);
        return this;
    }

    public FshareContextMenu callback(@Nullable Callback callback) {
        this.callback = callback;
        return this;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_context_menu);

        LinearLayout container = findViewById(R.id.menuContainer);
        populate(container, items, (position, item) -> {
            // 先关菜单再回调，避免回调里弹二次确认时两个弹窗叠在一起
            dismiss();
            if (callback != null) {
                callback.onSelect(position, item);
            }
        });

        position();
    }

    /**
     * 往 {@code R.layout.fshare_context_menu} 的 menuContainer 里填菜单行。
     * 选字浮层（{@link cn.wildfire.chat.kit.conversation.message.TextSelectionOverlay}）复用同一套卡片样式。
     */
    public static void populate(@NonNull LinearLayout container, @NonNull List<Item> items, @NonNull Callback onClick) {
        // 菜单行的背景是铺满整行的矩形 ripple，会画在卡片圆角之上，把首尾两行的角填成直角。
        // 让容器按自身 outline（bg_context_menu 的 14dp 圆角）裁切子 View，四角才真正是圆的。
        container.setClipToOutline(true);

        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                container.addView(createDivider(container.getContext()));
            }
            container.addView(createItemView(inflater, container, i, items.get(i), onClick));
        }
    }

    /**
     * 行宽在布局里是 wrap_content（否则卡片宽度不会由最长的一项决定），
     * 卡片宽度确定后把每行改回 match_parent 撑满，背景和水波纹才不会只占半行。
     */
    public static void stretchRows(@NonNull LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            ViewGroup.LayoutParams clp = child.getLayoutParams();
            clp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            child.setLayoutParams(clp);
        }
    }

    private static View createItemView(LayoutInflater inflater, ViewGroup parent,
                                       final int position, final Item item, final Callback onClick) {
        View row = inflater.inflate(R.layout.fshare_context_menu_item, parent, false);
        int color = parent.getContext().getResources().getColor(item.destructive ? R.color.coral : R.color.ink);

        TextView title = row.findViewById(R.id.menuTitle);
        title.setText(item.title);
        title.setTextColor(color);

        ImageView icon = row.findViewById(R.id.menuIcon);
        if (item.iconRes == 0) {
            icon.setVisibility(View.GONE);
        } else {
            icon.setImageResource(item.iconRes);
            // 图标一律跟随文字色，所以矢量里的 fillColor 只是占位
            icon.setColorFilter(color);
        }

        row.setOnClickListener(v -> onClick.onSelect(position, item));
        return row;
    }

    private static View createDivider(Context context) {
        View divider = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        // 分割线从图标左侧起就缩进，跟 iOS 的分组观感一致
        lp.setMarginStart((int) (18 * context.getResources().getDisplayMetrics().density + 0.5f));
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(context.getResources().getColor(R.color.fog));
        return divider;
    }

    /**
     * 把菜单摆到锚点旁边：优先锚点下方，下方放不下就翻到上方。
     *
     * <p>水平方向分两种情况：锚点比菜单窄（例如顶栏「+」按钮）时，把菜单靠锚点
     * 较近的那一侧对齐，视觉上才像是从这个按钮长出来的；锚点比菜单宽（会话行、
     * 消息气泡）时以锚点中心对齐。最后统一夹进屏幕。
     */
    private void position() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        // 锚点还没测量/未挂上窗口时 getLocationOnScreen() 会返回 [0,0]，
        // 菜单会被钉到左上角。这种情况退回系统默认的居中摆放。
        if (!anchor.isAttachedToWindow() || anchor.getWidth() == 0 || anchor.getHeight() == 0) {
            return;
        }

        int margin = dp(SCREEN_MARGIN_DP);
        int gap = dp(ANCHOR_GAP_DP);

        // 可见区域：top 即状态栏高度。lp.y 是从这个区域的顶部算起的，
        // 而 getLocationOnScreen() 返回的是含状态栏的绝对坐标，两者必须换算，
        // 否则菜单会整体偏下一个状态栏的高度。
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);

        View content = window.getDecorView();
        int maxWidth = Math.min(dp(MAX_WIDTH_DP), frame.width() - 2 * margin);
        // 高度也要扣掉上下边距，否则超长菜单测出来的高度会等于整个可见高度，
        // 走到下面「上下都放不下」的分支时算出的 y 会让卡片底部溢出屏幕
        int maxHeight = frame.height() - 2 * margin;
        content.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int menuWidth = content.getMeasuredWidth();
        int menuHeight = content.getMeasuredHeight();

        // 必须显式给窗口设宽度：Theme.AppCompat.Dialog 的 windowMinWidthMinor 会给
        // WRAP_CONTENT 的对话框窗口塞一个约 65% 屏宽的下限，卡片会被撑到几乎整屏，
        // 而且实际宽度和这里测出来的宽度对不上，按测量值算的水平位置也就跟着错。
        // LayoutParams 里给了确定宽度后，windowMinWidth* 不再参与，绕开这个坑。
        window.setLayout(menuWidth, menuHeight);

        stretchRows(findViewById(R.id.menuContainer));

        int[] anchorPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);
        int anchorLeft = anchorPos[0];
        int anchorRight = anchorLeft + anchor.getWidth();
        // 换算到可见区域坐标系
        int anchorTop = anchorPos[1] - frame.top;
        int anchorBottom = anchorTop + anchor.getHeight();

        int y;
        if (anchorBottom + gap + menuHeight <= frame.height() - margin) {
            y = anchorBottom + gap;
        } else if (anchorTop - gap - menuHeight >= margin) {
            y = anchorTop - gap - menuHeight;
        } else {
            // 上下都放不下（超长菜单），居中
            y = (frame.height() - menuHeight) / 2;
        }
        y = Math.max(margin, Math.min(y, frame.height() - menuHeight - margin));

        int x;
        if (anchor.getWidth() < menuWidth) {
            boolean anchorOnRight = (anchorLeft + anchorRight) / 2 > frame.width() / 2;
            x = anchorOnRight ? anchorRight - menuWidth : anchorLeft;
        } else {
            x = anchorLeft + (anchor.getWidth() - menuWidth) / 2;
        }
        x = Math.max(margin, Math.min(x, frame.width() - menuWidth - margin));

        WindowManager.LayoutParams lp = window.getAttributes();
        // 必须用绝对的 LEFT 而不是 START：应用开了 supportsRtl，START 会按布局方向解析，
        // 在 RTL 语言下变成 RIGHT，而上面算出的 x 始终是「距左边缘」的绝对像素，
        // 两者一配菜单就会被镜像到错误的位置。
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        window.setAttributes(lp);
    }

    private int dp(int value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }
}
