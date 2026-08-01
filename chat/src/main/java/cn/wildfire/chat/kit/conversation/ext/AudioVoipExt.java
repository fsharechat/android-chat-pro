package cn.wildfire.chat.kit.conversation.ext;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import java.util.ArrayList;

import androidx.lifecycle.ViewModelProviders;
import cn.wildfire.chat.kit.WfcUIKit;
import cn.wildfire.chat.kit.annotation.ExtContextMenuItem;
import cn.wildfire.chat.kit.conversation.ext.core.ConversationExt;
import cn.wildfire.chat.kit.group.GroupViewModel;
import cn.wildfire.chat.kit.group.PickGroupMemberActivity;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.model.Conversation;
import cn.wildfirechat.model.GroupInfo;

public class AudioVoipExt extends ConversationExt {
    private static final int REQUEST_CODE_GROUP_AUDIO_CHAT = 1;

    @ExtContextMenuItem(title = "语音通话")
    public void audio(View containerView, Conversation conversation) {
        switch (conversation.type) {
            case Single:
                audioChat(conversation.target);
                break;
            case Group:
                pickGroupMemberToAudioChat();
                break;
            default:
                break;
        }
    }

    private void pickGroupMemberToAudioChat() {
        Intent intent = new Intent(context, PickGroupMemberActivity.class);
        GroupViewModel groupViewModel = ViewModelProviders.of(context).get(GroupViewModel.class);
        GroupInfo groupInfo = groupViewModel.getGroupInfo(conversation.target, false);
        intent.putExtra("groupInfo", groupInfo);
        intent.putExtra("maxCount", 1);
        startActivityForResult(intent, REQUEST_CODE_GROUP_AUDIO_CHAT);
    }

    private void audioChat(String targetId) {
        WfcUIKit.onCall(context, targetId, true, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_CODE_GROUP_AUDIO_CHAT) {
            ArrayList<String> memberIds = data.getStringArrayListExtra(PickGroupMemberActivity.EXTRA_RESULT);
            if (memberIds != null && memberIds.size() > 0) {
                audioChat(memberIds.get(0));
            }
        }
    }

    @Override
    public boolean filter(Conversation conversation) {
        if (conversation.type == Conversation.ConversationType.Single
                || conversation.type == Conversation.ConversationType.Group) {
            return false;
        }
        return true;
    }

    @Override
    public int priority() {
        return 99;
    }

    @Override
    public int iconResId() {
        return R.drawable.ic_ext_audio_call;
    }

    @Override
    public String title(Context context) {
        return "语音通话";
    }
}
