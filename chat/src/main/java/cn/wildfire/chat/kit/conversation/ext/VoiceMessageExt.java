package cn.wildfire.chat.kit.conversation.ext;

import android.content.Context;
import android.view.View;
import cn.wildfire.chat.kit.annotation.ExtContextMenuItem;
import cn.wildfire.chat.kit.conversation.ConversationInputPanel;
import cn.wildfire.chat.kit.conversation.ext.core.ConversationExt;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.model.Conversation;

/** Keeps voice messages reachable when a nonempty draft occupies the send slot. */
public final class VoiceMessageExt extends ConversationExt {
    @ExtContextMenuItem(title = "语音消息")
    public void voice(View container, Conversation conversation) {
        if (container instanceof ConversationInputPanel) ((ConversationInputPanel) container).showVoiceInput();
    }
    @Override public int priority() { return 98; }
    @Override public int iconResId() { return R.drawable.ic_ext_voice_message; }
    @Override public String title(Context context) { return context.getString(R.string.composer_voice); }
}
