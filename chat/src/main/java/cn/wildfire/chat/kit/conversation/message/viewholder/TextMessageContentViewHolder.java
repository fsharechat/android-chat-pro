package cn.wildfire.chat.kit.conversation.message.viewholder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.text.Spanned;
import io.noties.markwon.Markwon;
import io.noties.markwon.SoftBreakAddsNewLinePlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableRowSpan;

import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import butterknife.Bind;
import butterknife.OnClick;
import cn.wildfire.chat.kit.annotation.EnableContextMenu;
import cn.wildfire.chat.kit.annotation.MessageContentType;
import cn.wildfire.chat.kit.annotation.MessageContextMenuItem;
import cn.wildfire.chat.kit.annotation.ReceiveLayoutRes;
import cn.wildfire.chat.kit.annotation.SendLayoutRes;
import cn.wildfire.chat.kit.conversation.TextPreviewActivity;
import cn.wildfire.chat.kit.conversation.message.TextSelectionOverlay;
import cn.wildfire.chat.kit.conversation.message.model.UiMessage;
import cn.wildfire.chat.kit.third.utils.UIUtils;
import cn.wildfire.chat.kit.widget.dialog.FshareContextMenu;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.TextMessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.message.core.MessageStatus;

@MessageContentType(TextMessageContent.class)
@SendLayoutRes(resId = R.layout.conversation_item_text_send)
@ReceiveLayoutRes(resId = R.layout.conversation_item_text_receive)
@EnableContextMenu
public class TextMessageContentViewHolder extends NormalMessageContentViewHolder implements SelectableTextViewHolder {
    @Bind(R.id.contentTextView)
    TextView contentTextView;

    @Bind(R.id.expandToggleTextView)
    TextView expandToggleTextView;

    @Bind(R.id.progressBar)
    ProgressBar progressBar;

    // 超过阈值的消息默认折叠为纯文本摘要，点「查看全文」进入全文页（TextPreviewActivity）
    // 渲染完整 Markdown，与 iOS 一致
    private static final int COLLAPSE_THRESHOLD = 600;
    private static final int PREVIEW_LENGTH = 280;
    // AI 流式回复保持展开（结束后可「收起」），按消息记住，滚出屏幕、切换会话后回来仍保持展开
    private static final Set<Long> expandedMessageIds = new HashSet<>();

    // 选字高亮：接收气泡上用微信绿；发送气泡是主题色底，用正文色的半透明层才看得出来
    private static final int SELECTION_COLOR_RECEIVE = 0x5907C160;
    private static final int SELECTION_ALPHA_SEND = 0x4D;

    private ValueAnimator blinkAnimator;
    private TextSelectionOverlay selectionOverlay;
    /** 点菜单项时的部分选中文字；全选或不在选字状态时为 null，复制原文。 */
    private String selectedTextForAction;
    private final Markwon markwon;
    private final int textMaxWidth;

    public TextMessageContentViewHolder(FragmentActivity activity, RecyclerView.Adapter adapter, View itemView) {
        super(activity, adapter, itemView);
        // 布局里 maxWidth=340dp 在窄屏上会超过可用宽度，导致长消息越过对面头像；
        // 两侧各预留 51dp（行内边距 5 + 头像 40 + 头像左右边距 3+3），保证气泡止于对面头像位置
        // 气泡左右 padding 各 16dp 在外层容器上，TextView 自身的最大宽度要扣掉这 32dp
        int reservedWidth = UIUtils.dip2Px(102);
        textMaxWidth = Math.min(UIUtils.dip2Px(340), UIUtils.getDisplayWidth() - reservedWidth) - UIUtils.dip2Px(32);
        contentTextView.setMaxWidth(textMaxWidth);
        markwon = Markwon.builder(activity)
                // 聊天里粘贴的多行文本只有单个 \n，CommonMark 会当软换行渲染成空格，这里保留为换行
                .usePlugin(SoftBreakAddsNewLinePlugin.create())
                .usePlugin(TablePlugin.create(activity))
                .usePlugin(StrikethroughPlugin.create())
                .build();
    }

    @Override
    public void onBind(UiMessage message) {
        // 重新绑定意味着文字可能变了，旧选区的位置不再可信
        if (selectionOverlay != null) {
            selectionOverlay.dismiss();
        }
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
            blinkAnimator = null;
        }
        String text = ((TextMessageContent) message.message.content).getContent();
        boolean isReceived = message.message.direction == MessageDirection.Receive;
        if (message.isStreaming) {
            // 流式消息保持展开，结束后也不会突然收起
            expandedMessageIds.add(message.message.messageId);
        }
        boolean collapsible = !message.isStreaming && text.length() > COLLAPSE_THRESHOLD;
        boolean expanded = expandedMessageIds.contains(message.message.messageId);
        if (collapsible) {
            expandToggleTextView.setVisibility(View.VISIBLE);
            // 发送气泡是主题色底，按钮加下划线以区别于正文，与 web 端一致
            int flags = expandToggleTextView.getPaintFlags();
            expandToggleTextView.setPaintFlags(isReceived ? flags & ~Paint.UNDERLINE_TEXT_FLAG : flags | Paint.UNDERLINE_TEXT_FLAG);
            expandToggleTextView.setText(expanded ? "收起" : "查看全文");
        } else {
            expandToggleTextView.setVisibility(View.GONE);
        }

        if (collapsible && !expanded) {
            // 折叠态只显示纯文本摘要，不解析 Markdown，避免大表格拖慢列表
            contentTextView.setMinWidth(0);
            int end = Character.isHighSurrogate(text.charAt(PREVIEW_LENGTH - 1)) ? PREVIEW_LENGTH - 1 : PREVIEW_LENGTH;
            contentTextView.setText(text.substring(0, end) + "…");
        } else if (message.isStreaming && text.isEmpty()) {
            // 占位符：文字未到达，光标闪烁提示 AI 正在思考
            contentTextView.setMinWidth(0);
            contentTextView.setText("▌");
            final int baseColor = contentTextView.getCurrentTextColor();
            final int r = Color.red(baseColor), g = Color.green(baseColor), b = Color.blue(baseColor);
            blinkAnimator = ValueAnimator.ofFloat(1f, 0.1f);
            blinkAnimator.setDuration(600);
            blinkAnimator.setRepeatMode(ValueAnimator.REVERSE);
            blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
            blinkAnimator.addUpdateListener(anim -> {
                float alpha = (float) anim.getAnimatedValue();
                contentTextView.setTextColor(Color.argb((int) (alpha * 255), r, g, b));
            });
            blinkAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    contentTextView.setTextColor(baseColor);
                }
            });
            blinkAnimator.start();
        } else if (isReceived) {
            // 接收消息（含 AI 流式输出）：以 Markdown 格式渲染
            String displayText = message.isStreaming ? text + "▌" : text;
            // 流式输出中内容实时变化，不缓存；非流式时缓存 Spanned 防止 rebind 高度变化导致列表跳动
            setMarkdownCached(message, displayText, !message.isStreaming);
        } else {
            // 自己发出的消息：Markdown 渲染，缓存防跳动
            setMarkdownCached(message, text, true);
        }

        if (isReceived) {
            progressBar.setVisibility(View.GONE);
        } else {
            // todo
        }
    }

    /**
     * 使用缓存的 Spanned 渲染 Markdown，避免每次 rebind 重新解析导致 TextView 高度变化引起 RecyclerView 列表跳动。
     * useCache=false 时（流式输出中）跳过缓存，每次重新解析以展示最新内容。
     */
    private void setMarkdownCached(UiMessage message, String text, boolean useCache) {
        if (useCache && message.cachedMarkdown != null && text.equals(message.cachedMarkdownSource)) {
            applyTableWidth(message.cachedMarkdown);
            markwon.setParsedMarkdown(contentTextView, message.cachedMarkdown);
        } else {
            Spanned spanned = markwon.toMarkdown(text);
            if (useCache) {
                message.cachedMarkdown = spanned;
                message.cachedMarkdownSource = text;
            }
            applyTableWidth(spanned);
            markwon.setParsedMarkdown(contentTextView, spanned);
        }
    }

    /**
     * TableRowSpan 测量时返回的宽度取自上一次绘制时 TextView 的宽度，首次测量为 0；
     * 气泡是 wrap_content，于是被量成只剩 padding 的窄条，表格画在 0 宽里什么都看不到。
     * 含表格时把气泡撑到最大宽度，给表格一个确定的绘制宽度；ViewHolder 复用，普通文本要复位。
     */
    private void applyTableWidth(Spanned spanned) {
        boolean hasTable = spanned.getSpans(0, spanned.length(), TableRowSpan.class).length > 0;
        contentTextView.setMinWidth(hasTable ? textMaxWidth : 0);
    }

    /**
     * 流式占位消息被真实消息替换时 messageId 会变，把展开状态带过去，AI 回复结束后才不会突然折叠
     */
    public static void transferExpandState(long fromMessageId, long toMessageId) {
        if (expandedMessageIds.remove(fromMessageId)) {
            expandedMessageIds.add(toMessageId);
        }
    }

    @OnClick(R.id.expandToggleTextView)
    public void onToggleExpand(View view) {
        long messageId = message.message.messageId;
        if (!expandedMessageIds.contains(messageId)) {
            // 折叠态：进入全文页查看完整 Markdown，可自由选字复制
            TextPreviewActivity.start(context, ((TextMessageContent) message.message.content).getContent());
            return;
        }
        // 展开态（AI 流式回复结束后）：原地收起
        expandedMessageIds.remove(messageId);
        onBind(message);
        keepBubbleVisibleAfterCollapse();
    }

    /**
     * 「收起」按钮在长消息底部，点击时消息顶部通常已滚出屏幕；收起后气泡变矮，
     * 若不处理，整条消息会被顶到视口外。收起后把这条消息的顶部对齐到列表顶部。
     */
    private void keepBubbleVisibleAfterCollapse() {
        if (!(itemView.getParent() instanceof RecyclerView) || itemView.getTop() >= 0) {
            return;
        }
        RecyclerView.LayoutManager layoutManager = ((RecyclerView) itemView.getParent()).getLayoutManager();
        int position = getAdapterPosition();
        if (layoutManager instanceof LinearLayoutManager && position != RecyclerView.NO_POSITION) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, 0);
        }
    }

    @OnClick(R.id.contentTextView)
    public void onClickTest(View view) {
//        Toast.makeText(context, "onTextMessage click: " + ((TextMessageContent) message.message.content).getContent(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean showSelectionMenu(List<FshareContextMenu.Item> items, FshareContextMenu.Callback callback) {
        if (message == null || message.isStreaming) {
            return false;
        }
        int highlight = message.message.direction == MessageDirection.Receive
                ? SELECTION_COLOR_RECEIVE
                : ColorUtils.setAlphaComponent(contentTextView.getCurrentTextColor(), SELECTION_ALPHA_SEND);
        selectionOverlay = TextSelectionOverlay.show(context, contentTextView, highlight, items, new TextSelectionOverlay.Listener() {
            @Override
            public void onMenuItemClick(int position, FshareContextMenu.Item item) {
                TextSelectionOverlay overlay = selectionOverlay;
                if (overlay == null) {
                    return;
                }
                // 关浮层会清掉选区，先记下选中内容；先关浮层再回调，避免与二次确认弹窗叠在一起
                selectedTextForAction = overlay.getPartialSelectionText();
                overlay.dismiss();
                try {
                    callback.onSelect(position, item);
                } finally {
                    selectedTextForAction = null;
                }
            }

            @Override
            public void onDismiss() {
                selectionOverlay = null;
            }
        });
        return selectionOverlay != null;
    }

    @MessageContextMenuItem(tag = MessageContextMenuItemTags.TAG_CLIP, title = "复制", icon = R.drawable.ic_menu_copy, confirm = false, priority = 12)
    public void clip(View itemView, UiMessage message) {
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            return;
        }
        TextMessageContent content = (TextMessageContent) message.message.content;
        // 部分选中时复制选中的显示文字；全选时复制原文（保留 Markdown 源文，折叠态下也是全文）
        String text = selectedTextForAction != null ? selectedTextForAction : content.getContent();
        ClipData clipData = ClipData.newPlainText("messageContent", text);
        clipboardManager.setPrimaryClip(clipData);
    }

    @Override
    protected void setSendStatus(Message item) {
        super.setSendStatus(item);
        MessageContent msgContent = item.content;
        if (msgContent instanceof TextMessageContent) {
            //只需要设置自己发送的状态
            MessageStatus sentStatus = item.status;
            if (sentStatus == MessageStatus.Sending) {
                progressBar.setVisibility(View.VISIBLE);
            } else if (sentStatus == MessageStatus.Send_Failure) {
                progressBar.setVisibility(View.GONE);
            } else if (sentStatus == MessageStatus.Sent) {
                progressBar.setVisibility(View.GONE);
            }
        }
    }
}
