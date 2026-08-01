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

public class VideoVoipExt extends ConversationExt {
    private static final int REQUEST_CODE_GROUP_VIDEO_CHAT = 0;

    @ExtContextMenuItem(title = "视频通话")
    public void voip(View containerView, Conversation conversation) {
        switch (conversation.type) {
            case Single:
                videoChat(conversation.target);
                break;
            case Group:
                pickGroupMemberToVideoChat();
                break;
            default:
                break;
        }
    }

    private void pickGroupMemberToVideoChat() {
        Intent intent = new Intent(context, PickGroupMemberActivity.class);
        GroupViewModel groupViewModel = ViewModelProviders.of(context).get(GroupViewModel.class);
        GroupInfo groupInfo = groupViewModel.getGroupInfo(conversation.target, false);
        intent.putExtra("groupInfo", groupInfo);
        intent.putExtra("maxCount", 9);
        startActivityForResult(intent, REQUEST_CODE_GROUP_VIDEO_CHAT);
    }

    private void videoChat(String targetId) {
        WfcUIKit.onCall(context, targetId, true, false);
    }

    private void videoChat(ArrayList<String> memberIds) {
        WfcUIKit.onCall(context, this.conversation.target, memberIds, true, false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_CODE_GROUP_VIDEO_CHAT) {
            ArrayList<String> memberIds = data.getStringArrayListExtra(PickGroupMemberActivity.EXTRA_RESULT);
            videoChat(memberIds);
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
        return R.drawable.ic_ext_video_call;
    }

    @Override
    public String title(Context context) {
        return "视频通话";
    }
}
