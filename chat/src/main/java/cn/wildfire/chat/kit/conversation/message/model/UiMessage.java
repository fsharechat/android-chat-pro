package cn.wildfire.chat.kit.conversation.message.model;

import android.text.Spanned;

import cn.wildfirechat.message.Message;
import cn.wildfirechat.model.Conversation;

public class UiMessage {
    public boolean isPlaying;
    public boolean isDownloading;
    public boolean isFocus;
    /**
     * media类型消息，上传或下载的进度
     */
    public int progress;
    public Message message;
    public boolean isStreaming;
    public String streamId;

    // 缓存 Markwon 渲染结果，避免 rebind 时重新解析导致高度变化引起列表跳动
    public transient Spanned cachedMarkdown;
    public transient String cachedMarkdownSource;

    public UiMessage(Message message) {
        this.message = message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UiMessage uiMessage = (UiMessage) o;
        return Conversation.equals(message, uiMessage.message);
    }

    @Override
    public int hashCode() {

        return Conversation.hashCode(message);
    }
}
