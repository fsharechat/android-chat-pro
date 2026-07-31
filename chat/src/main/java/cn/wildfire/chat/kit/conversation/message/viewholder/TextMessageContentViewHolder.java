package cn.wildfire.chat.kit.conversation.message.viewholder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.text.Spanned;
import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import butterknife.Bind;
import butterknife.OnClick;
import cn.wildfire.chat.kit.annotation.EnableContextMenu;
import cn.wildfire.chat.kit.annotation.MessageContentType;
import cn.wildfire.chat.kit.annotation.MessageContextMenuItem;
import cn.wildfire.chat.kit.annotation.ReceiveLayoutRes;
import cn.wildfire.chat.kit.annotation.SendLayoutRes;
import cn.wildfire.chat.kit.conversation.message.model.UiMessage;
import cn.wildfire.chat.kit.third.utils.UIUtils;
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
public class TextMessageContentViewHolder extends NormalMessageContentViewHolder {
    @Bind(R.id.contentTextView)
    TextView contentTextView;

    @Bind(R.id.progressBar)
    ProgressBar progressBar;

    private ValueAnimator blinkAnimator;
    private final Markwon markwon;

    public TextMessageContentViewHolder(FragmentActivity activity, RecyclerView.Adapter adapter, View itemView) {
        super(activity, adapter, itemView);
        // 布局里 maxWidth=340dp 在窄屏上会超过可用宽度，导致长消息越过对面头像；
        // 两侧各预留 51dp（行内边距 5 + 头像 40 + 头像左右边距 3+3），保证气泡止于对面头像位置
        int reservedWidth = UIUtils.dip2Px(102);
        contentTextView.setMaxWidth(Math.min(UIUtils.dip2Px(340), UIUtils.getDisplayWidth() - reservedWidth));
        markwon = Markwon.builder(activity)
                .usePlugin(TablePlugin.create(activity))
                .usePlugin(StrikethroughPlugin.create())
                .build();
    }

    @Override
    public void onBind(UiMessage message) {
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
            blinkAnimator = null;
        }
        String text = ((TextMessageContent) message.message.content).getContent();
        boolean isReceived = message.message.direction == MessageDirection.Receive;

        if (message.isStreaming && text.isEmpty()) {
            // 占位符：文字未到达，光标闪烁提示 AI 正在思考
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
            markwon.setParsedMarkdown(contentTextView, message.cachedMarkdown);
        } else {
            Spanned spanned = markwon.toMarkdown(text);
            if (useCache) {
                message.cachedMarkdown = spanned;
                message.cachedMarkdownSource = text;
            }
            markwon.setParsedMarkdown(contentTextView, spanned);
        }
    }

    @OnClick(R.id.contentTextView)
    public void onClickTest(View view) {
//        Toast.makeText(context, "onTextMessage click: " + ((TextMessageContent) message.message.content).getContent(), Toast.LENGTH_SHORT).show();
    }

    @MessageContextMenuItem(tag = MessageContextMenuItemTags.TAG_CLIP, title = "复制", icon = R.drawable.ic_menu_copy, confirm = false, priority = 12)
    public void clip(View itemView, UiMessage message) {
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            return;
        }
        TextMessageContent content = (TextMessageContent) message.message.content;
        ClipData clipData = ClipData.newPlainText("messageContent", content.getContent());
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
