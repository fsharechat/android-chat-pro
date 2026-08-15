package cn.wildfire.chat.kit.conversationlist;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cn.wildfire.chat.kit.IMServiceStatusViewModel;
import cn.wildfire.chat.kit.WfcUIKit;
import cn.wildfire.chat.kit.conversation.ConversationActivity;
import cn.wildfire.chat.kit.conversation.GroupConversationInfoFragment;
import cn.wildfire.chat.kit.conversationlist.notification.ConnectionStatusNotification;
import cn.wildfire.chat.kit.conversationlist.notification.StatusNotificationViewModel;
import cn.wildfire.chat.kit.user.UserViewModel;
import cn.wildfire.chat.kit.widget.dialog.FshareDialog;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.client.ConnectionStatus;
import cn.wildfirechat.model.Conversation;
import cn.wildfirechat.model.ConversationInfo;
import cn.wildfirechat.model.UserInfo;
import cn.wildfirechat.remote.ChatManager;

public class ConversationListFragment extends Fragment {
    private RecyclerView recyclerView;
    private ConversationListAdapter adapter;
    private static final List<Conversation.ConversationType> types = Arrays.asList(Conversation.ConversationType.Single,
            Conversation.ConversationType.Group,
            Conversation.ConversationType.Channel);
    private static final List<Integer> lines = Arrays.asList(0);

    public static final int REQUEST_OPEN_CONVERSATION = 100;

    private ConversationListViewModel conversationListViewModel;
    private IMServiceStatusViewModel imServiceStatusViewModel;
    private UserViewModel userViewModel;
    private Observer<ConversationInfo> conversationInfoObserver = new Observer<ConversationInfo>() {
        @Override
        public void onChanged(@Nullable ConversationInfo conversationInfo) {
            // just handle what we care about
            if (types.contains(conversationInfo.conversation.type) && lines.contains(conversationInfo.conversation.line)) {
                Log.d("ConversationList", "onChanged: "+conversationInfo.conversation.type);
                // 必须在 notify 之前取，此时 RecyclerView 还是变更前的布局
                boolean wasAtTop = isListAtTop();
                boolean movedToTop = adapter.submitConversationInfo(conversationInfo);
                if (movedToTop && wasAtTop) {
                    // 否则新的第一条会被排到视口上方，屏幕上第一个仍是原来那条
                    recyclerView.scrollToPosition(0);
                }
            }
        }
    };

    private Observer<Conversation> conversationRemovedObserver = new Observer<Conversation>() {
        @Override
        public void onChanged(@Nullable Conversation conversation) {
            if (conversation == null) {
                return;
            }
            if (types.contains(conversation.type) && lines.contains(conversation.line)) {
                adapter.removeConversation(conversation);
            }
        }
    };

    // 置顶是用户主动操作，无论当前滚到哪里都把列表拉回顶部，让置顶结果可见
    private Observer<ConversationInfo> conversationTopObserver = conversationInfo -> {
        if (recyclerView != null) {
            recyclerView.scrollToPosition(0);
        }
    };

    // 会话同步
    private Observer<Object> settingUpdateObserver = o -> reloadConversations();

    private Observer<List<UserInfo>> userInfoLiveDataObserver = (userInfos) -> {
        adapter.updateUserInfos(userInfos);
    };

    private Observer<Boolean> imStatusLiveDataObserver = (connected) -> {
        if (connected) {
            if (adapter != null && (adapter.getConversationInfos() == null || adapter.getConversationInfos().size() == 0)) {
                reloadConversations();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.conversationlist_frament, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        init();
        return view;
    }

    private void init() {
        adapter = new ConversationListAdapter(this);
        conversationListViewModel = ViewModelProviders
                .of(this, new ConversationListViewModelFactory(types, lines))
                .get(ConversationListViewModel.class);
        conversationListViewModel.getConversationListAsync(types, lines)
                .observe(this, conversationInfos -> {
                    adapter.setConversationInfos(conversationInfos);
                    adapter.notifyDataSetChanged();
                });
        recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));

        // 移除分割线
        // DividerItemDecoration itemDecor = new DividerItemDecoration(getActivity(), DividerItemDecoration.VERTICAL);
        // itemDecor.setDrawable(ContextCompat.getDrawable(getContext(), R.drawable.recyclerview_horizontal_divider));
        // recyclerView.addItemDecoration(itemDecor);
        recyclerView.setAdapter(adapter);
        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);

        conversationListViewModel.conversationInfoLiveData().observeForever(conversationInfoObserver);
        conversationListViewModel.conversationRemovedLiveData().observeForever(conversationRemovedObserver);
        conversationListViewModel.conversationTopLiveData().observeForever(conversationTopObserver);
        conversationListViewModel.settingUpdateLiveData().observeForever(settingUpdateObserver);

        imServiceStatusViewModel = WfcUIKit.getAppScopeViewModel(IMServiceStatusViewModel.class);
        imServiceStatusViewModel.imServiceStatusLiveData().observeForever(imStatusLiveDataObserver);

        userViewModel = WfcUIKit.getAppScopeViewModel(UserViewModel.class);
        userViewModel.userInfoLiveData().observeForever(userInfoLiveDataObserver);

        StatusNotificationViewModel statusNotificationViewModel = WfcUIKit.getAppScopeViewModel(StatusNotificationViewModel.class);
        statusNotificationViewModel.statusNotificationLiveData().observe(this, new Observer<Object>() {
            @Override
            public void onChanged(Object o) {
                adapter.updateStatusNotification(statusNotificationViewModel.getNotificationItems());
            }
        });
//        conversationListViewModel.connectionStatusLiveData().observe(this, status -> {
//            ConnectionStatusNotification connectionStatusNotification = new ConnectionStatusNotification();
//            switch (status) {
//                case ConnectionStatus.ConnectionStatusConnecting:
//                    connectionStatusNotification.setValue("正在连接...");
//                    statusNotificationViewModel.showStatusNotification(connectionStatusNotification);
//                    break;
//                case ConnectionStatus.ConnectionStatusConnected:
//                    statusNotificationViewModel.removeStatusNotification(connectionStatusNotification);
//                    break;
//                case ConnectionStatus.ConnectionStatusUnconnected:
//                    connectionStatusNotification.setValue("连接失败");
//                    statusNotificationViewModel.showStatusNotification(connectionStatusNotification);
//                    break;
//                default:
//                    break;
//            }
//        });
    }

    /**
     * 列表当前是否停在最顶部（第一条完整贴着顶边）。只有这种情况下才需要在
     * 有会话插到头部时把列表滚回去；用户已经往下翻了就不该打断。
     */
    private boolean isListAtTop() {
        if (recyclerView == null || !(recyclerView.getLayoutManager() instanceof LinearLayoutManager)) {
            return false;
        }
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        int firstVisible = layoutManager.findFirstVisibleItemPosition();
        if (firstVisible == RecyclerView.NO_POSITION) {
            // 还没布局或者列表为空，等价于在顶部
            return true;
        }
        if (firstVisible != 0) {
            return false;
        }
        View firstChild = layoutManager.findViewByPosition(0);
        return firstChild != null && firstChild.getTop() >= recyclerView.getPaddingTop();
    }

    public void reloadConversations() {
        if(conversationListViewModel != null){
            conversationListViewModel.getConversationListAsync(types, lines)
                    .observe(this, conversationInfos -> {
                        adapter.setConversationInfos(conversationInfos);
                        adapter.notifyDataSetChanged();
                    });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        conversationListViewModel.conversationInfoLiveData().removeObserver(conversationInfoObserver);
        conversationListViewModel.conversationRemovedLiveData().removeObserver(conversationRemovedObserver);
        conversationListViewModel.conversationTopLiveData().removeObserver(conversationTopObserver);
        conversationListViewModel.settingUpdateLiveData().removeObserver(settingUpdateObserver);
        imServiceStatusViewModel.imServiceStatusLiveData().removeObserver(imStatusLiveDataObserver);
        userViewModel.userInfoLiveData().removeObserver(userInfoLiveDataObserver);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_OPEN_CONVERSATION:

                if(resultCode == GroupConversationInfoFragment.RESULT_USER_CHECK_REMOVE_GROUP_CONVERSATION_SUCCESS
//                        || resultCode == GroupConversationInfoFragment.RESULT_QUIT_GROUP_CONVERSATION_SUCCESS
//                        || resultCode == GroupConversationInfoFragment.RESULT_REMOVE_GROUP_CONVERSATION_SUCCESS
                ){

                    String groupName = data.getStringExtra("groupName");
                    String title = TextUtils.isEmpty(groupName) ? "删除会话" : groupName;
                    String content = "你已经不在群组或者当前群组已经解散，确认是否删除会话";
                    switch (resultCode){
//                        case GroupConversationInfoFragment.RESULT_QUIT_GROUP_CONVERSATION_SUCCESS:
//                            content = "你已经不在此群组，确认是否删除会话";
//                            break;
//                        case GroupConversationInfoFragment.RESULT_REMOVE_GROUP_CONVERSATION_SUCCESS:
//                            content = "当前群组已经解散，确认是否删除会话";
//                            break;
                        default:
                            break;
                    }
                    FshareDialog dialog = new FshareDialog.Builder(getActivity())
                            .title(title)
                            .content(content)
                            .secondary("取消", null)
                            .primary("确认", d -> {
                                ConversationInfo conversationInfo = data.getParcelableExtra("conversationInfo");
                                if (conversationInfo != null) {
                                    //重新获取conversation 不然当前退出群组时，未读消息为0，导致删除会话仍然又未读消息
                                    conversationInfo = ChatManager.Instance().getConversation(conversationInfo.conversation);
                                    conversationListViewModel.removeConversation(conversationInfo);
                                }
                            })
                            .build();
                    dialog.show();
                }

                break;

            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }
}
