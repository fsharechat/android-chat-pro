package cn.wildfirechat.proto;
import android.content.Context;
import android.text.TextUtils;
import com.comsince.github.core.future.SimpleFuture;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UploadManager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import cn.wildfirechat.ErrorCode;
import cn.wildfirechat.alarm.AlarmWrapper;
import cn.wildfirechat.message.core.MessageStatus;
import cn.wildfirechat.model.ProtoFriendRequest;
import cn.wildfirechat.model.ProtoGroupInfo;
import cn.wildfirechat.model.ProtoGroupMember;
import cn.wildfirechat.model.ProtoMessage;
import cn.wildfirechat.model.ProtoMessageContent;
import cn.wildfirechat.model.ProtoUserInfo;
import cn.wildfirechat.proto.handler.StreamingAiHandler;
import cn.wildfirechat.proto.handler.SpeechToTextHandler;
import cn.wildfirechat.proto.handler.AddFriendRequestHandler;
import cn.wildfirechat.proto.handler.AddGroupMemberHandler;
import cn.wildfirechat.proto.handler.ConnectAckMessageHandler;
import cn.wildfirechat.proto.handler.CreateGroupHandler;
import cn.wildfirechat.proto.handler.DeliveryReportHandler;
import cn.wildfirechat.proto.handler.DismissGroupHandler;
import cn.wildfirechat.proto.handler.FriendPullHandler;
import cn.wildfirechat.proto.handler.FriendRequestHandler;
import cn.wildfirechat.proto.handler.GetMinioUploadUrlHandler;
import cn.wildfirechat.proto.handler.GetUserInfoMessageHanlder;
import cn.wildfirechat.proto.handler.GroupInfoHandler;
import cn.wildfirechat.proto.handler.GroupMemberHandler;
import cn.wildfirechat.proto.handler.HandlerFriendRequestHandler;
import cn.wildfirechat.proto.handler.HeartbeatHandler;
import cn.wildfirechat.proto.handler.KickoffMembersHandler;
import cn.wildfirechat.proto.handler.ModifyGroupInfoHandler;
import cn.wildfirechat.proto.handler.ModifyMyInfoHandler;
import cn.wildfirechat.proto.handler.NotifyFriendHandler;
import cn.wildfirechat.proto.handler.NotifyFriendRequestHandler;
import cn.wildfirechat.proto.handler.NotifyMessageHandler;
import cn.wildfirechat.proto.handler.NotifyUserSettingHandler;
import cn.wildfirechat.proto.handler.QiniuTokenHandler;
import cn.wildfirechat.proto.handler.QuitGroupHandler;
import cn.wildfirechat.proto.handler.ReadReportHandler;
import cn.wildfirechat.proto.handler.RecallMessageHandler;
import cn.wildfirechat.proto.handler.RecallNotifyMessageHandler;
import cn.wildfirechat.proto.handler.ReceiveMessageHandler;
import cn.wildfirechat.proto.handler.RemoteMessageHandler;
import cn.wildfirechat.proto.handler.SearchUserResultMessageHandler;
import cn.wildfirechat.proto.handler.SendMessageHandler;
import cn.wildfirechat.proto.handler.UserSettingPullHandler;
import cn.wildfirechat.proto.store.DataStoreFactory;
import cn.wildfirechat.proto.store.ImMemoryStore;
import cn.wildfirechat.proto.util.MessageShardingUtil;

public class ProtoService extends AbstractProtoService {

    public ProtoService(Context context,AlarmWrapper alarmWrapper){
        super(context,alarmWrapper);
        imMemoryStore = DataStoreFactory.getDataStore(context);
        uploadManager = new UploadManager();
        initHandlers();
    }

    public ImMemoryStore getImMemoryStore(){
        return imMemoryStore;
    }

    private void initHandlers(){
        messageHandlers.add(new HeartbeatHandler(this));
        messageHandlers.add(new ConnectAckMessageHandler(this));
        messageHandlers.add(new SearchUserResultMessageHandler(this));
        messageHandlers.add(new GetUserInfoMessageHanlder(this));
        messageHandlers.add(new AddFriendRequestHandler(this));
        messageHandlers.add(new NotifyFriendHandler(this));
        messageHandlers.add(new FriendRequestHandler(this));
        messageHandlers.add(new HandlerFriendRequestHandler(this));
        messageHandlers.add(new FriendPullHandler(this));
        messageHandlers.add(new UserSettingPullHandler(this));
        messageHandlers.add(new NotifyUserSettingHandler(this));
        messageHandlers.add(new SendMessageHandler(this));
        messageHandlers.add(new ReceiveMessageHandler(this));
        messageHandlers.add(new NotifyMessageHandler(this));
        messageHandlers.add(new NotifyFriendRequestHandler(this));
        messageHandlers.add(new CreateGroupHandler(this));
        messageHandlers.add(new GroupInfoHandler(this));
        messageHandlers.add(new GroupMemberHandler(this));
        messageHandlers.add(new AddGroupMemberHandler(this));
        messageHandlers.add(new KickoffMembersHandler(this));
        messageHandlers.add(new QuitGroupHandler(this));
        messageHandlers.add(new DismissGroupHandler(this));
        messageHandlers.add(new ModifyGroupInfoHandler(this));
        messageHandlers.add(new ModifyMyInfoHandler(this));
        messageHandlers.add(new QiniuTokenHandler(this));
        messageHandlers.add(new RecallMessageHandler(this));
        messageHandlers.add(new SpeechToTextHandler(this));
        messageHandlers.add(new RecallNotifyMessageHandler(this));
        messageHandlers.add(new RemoteMessageHandler(this));
        messageHandlers.add(new GetMinioUploadUrlHandler(this));
        messageHandlers.add(new DeliveryReportHandler(this));
        messageHandlers.add(new ReadReportHandler(this));
        messageHandlers.add(new StreamingAiHandler(this));
    }

    // ---- 流式 AI 回调 ----

    public interface SaiTokenCallback {
        /** @param streamId  本次流会话 ID
         *  @param delta     本次 token 内容（首尾两次为空字符串，分别对应开始/结束）
         *  @param done      true 表示流结束
         *  @param target    AI 机器人 IM 用户 ID（用于定位会话） */
        void onToken(String streamId, String delta, boolean done, String target);
    }

    private SaiTokenCallback saiTokenCallback;

    public void setSaiTokenCallback(SaiTokenCallback callback) {
        this.saiTokenCallback = callback;
    }

    public void onSaiToken(String streamId, String delta, boolean done, String target) {
        if (saiTokenCallback != null) {
            saiTokenCallback.onToken(streamId, delta, done, target);
        }
        JavaProtoLogic.onSaiToken(streamId, delta, done, target);
    }

    public void searchUser(String keyword, JavaProtoLogic.ISearchUserCallback callback){
        WFCMessage.SearchUserRequest request = WFCMessage.SearchUserRequest.newBuilder()
                .setKeyword(keyword)
                .setFuzzy(1)
                .setPage(0)
                .build();
        sendMessage(Signal.PUBLISH,SubSignal.US,request.toByteArray(),callback);
    }


    public ProtoUserInfo getUserInfo(String userId, String groupId, boolean refresh){
//        log.i("getUserInfo userId "+userId+" groupId "+groupId+" refresh "+refresh);
        if(imMemoryStore.getUserInfo(userId) == null || refresh){
            WFCMessage.PullUserRequest request = WFCMessage.PullUserRequest.newBuilder()
                    .addRequest(WFCMessage.UserRequest.newBuilder().setUid(userId).build())
                    .build();
            SimpleFuture<ProtoUserInfo[]> simpleFuture = sendMessageSync(Signal.PUBLISH,SubSignal.UPUI,request.toByteArray());
            try {
                simpleFuture.get(200,TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
       return imMemoryStore.getUserInfo(userId);
    }

    public void modifyMyInfo(Map<Integer, String> values, JavaProtoLogic.IGeneralCallback callback){
        WFCMessage.ModifyMyInfoRequest.Builder modifyMyInfoBuilder = WFCMessage.ModifyMyInfoRequest.newBuilder();
        for(Map.Entry<Integer,String> entry: values.entrySet()){
            WFCMessage.InfoEntry infoEntry = WFCMessage.InfoEntry.newBuilder().setType(entry.getKey()).setValue(entry.getValue()).build();
            modifyMyInfoBuilder.addEntry(infoEntry);
        }
        sendMessage(Signal.PUBLISH,SubSignal.MMI,modifyMyInfoBuilder.build().toByteArray(),callback);
    }

    public ProtoUserInfo[] getUserInfos(String[] userIds, String groupId){
        log.i("getUserInfos "+Arrays.toString(userIds)+" groupId "+groupId);

        List<ProtoUserInfo> resultProtoUserInfoList = new ArrayList<>();
        WFCMessage.PullUserRequest.Builder userRequestBuilder = WFCMessage.PullUserRequest.newBuilder();
        for(String user : userIds){
            ProtoUserInfo protoUserInfo = imMemoryStore.getUserInfo(user);
            if(protoUserInfo != null){
                resultProtoUserInfoList.add(protoUserInfo);
                continue;
            }
            WFCMessage.UserRequest userRequest = WFCMessage.UserRequest.newBuilder().setUid(user).build();
            userRequestBuilder.addRequest(userRequest);
        }
        log.i("getUserInfos from backendService size:"+userRequestBuilder.getRequestList());
        if(!userRequestBuilder.getRequestList().isEmpty()){
            SimpleFuture<ProtoUserInfo[]> simpleFuture = sendMessageSync(Signal.PUBLISH,SubSignal.UPUI,userRequestBuilder.build().toByteArray());
            try {
                ProtoUserInfo[]  protoUserInfos;
                if(!TextUtils.isEmpty(groupId)){
                    protoUserInfos = simpleFuture.get();
                } else {
                    protoUserInfos = simpleFuture.get(200,TimeUnit.MILLISECONDS);
                }
                if(protoUserInfos != null && protoUserInfos.length > 0){
                    resultProtoUserInfoList.addAll(Arrays.asList(protoUserInfos));
                }
            } catch (Exception e) {
                log.e("getuserinfo error ",e);
            }
        }
        return resultProtoUserInfoList.toArray(new ProtoUserInfo[resultProtoUserInfoList.size()]);
    }

    public void sendFriendRequest(String userId, String reason, JavaProtoLogic.IGeneralCallback callback){
        WFCMessage.AddFriendRequest friendRequest = WFCMessage.AddFriendRequest.newBuilder()
                .setReason(reason)
                .setTargetUid(userId)
                .build();
        sendMessage(Signal.PUBLISH, SubSignal.FAR,friendRequest.toByteArray(),callback);
    }

    public int getUnreadFriendRequestStatus(){
        ProtoFriendRequest[] protoFriendRequests = getFriendRequest(true);
        int unReadStatus = 0;
        if(protoFriendRequests != null){
            for(ProtoFriendRequest protoFriendRequest : protoFriendRequests){
                if(protoFriendRequest.getStatus() == 0){
                    unReadStatus++;
                }
            }
        }
        return unReadStatus;
    }

    public ProtoFriendRequest[] getFriendRequest(boolean incomming) {
        WFCMessage.Version version = WFCMessage.Version.newBuilder().setVersion(imMemoryStore.getFriendRequestHead() - 1000).build();
        SimpleFuture<ProtoFriendRequest[]> friendRequestFuture = sendMessageSync(Signal.PUBLISH,SubSignal.FRP,version.toByteArray());
        try {
            friendRequestFuture.get(200,TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return imMemoryStore.getIncomingFriendRequest();
    }

    public void handleFriendRequest(String userId, boolean accept, JavaProtoLogic.IGeneralCallback callback){
        WFCMessage.HandleFriendRequest handleFriendRequest = WFCMessage.HandleFriendRequest.newBuilder()
                .setTargetUid(userId)
                .setStatus(0)
                .build();
        sendMessage(Signal.PUBLISH,SubSignal.FHR,handleFriendRequest.toByteArray(),callback);
    }

    /**
     * 获取用户朋友列表
     * @param refresh 是否强制刷新
     * */
    public String[] getMyFriendList(boolean refresh){
        if(!refresh && imMemoryStore.hasFriend()){
            return imMemoryStore.getFriendListArr();
        }

        WFCMessage.Version request = WFCMessage.Version.newBuilder().setVersion(0).build();
        SimpleFuture<String[]> friendListFuture = sendMessageSync(Signal.PUBLISH,SubSignal.FP,request.toByteArray());
        try {
            return friendListFuture.get(500,TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isMyFriend(String userId){
        return imMemoryStore.isMyFriend(userId);
    }

    /**
     * 拉取用户设置增量。version 传本地已同步到的最大 updateDt，新设备传 0 即全量。
     * 结果由 UserSettingPullHandler 落到本地 store，这里不等待。
     */
    public void pullUserSettings(long version){
        log.i("pull user settings from version "+version);
        WFCMessage.Version request = WFCMessage.Version.newBuilder().setVersion(version).build();
        sendMessage(Signal.PUBLISH, SubSignal.UG, request.toByteArray(), null);
    }

    /**
     * 上报一条用户设置。取消操作传 value "0"（tombstone），不要用删除表达。
     */
    public void updateUserSetting(int scope, String key, String value, JavaProtoLogic.IGeneralCallback callback){
        log.i("update user setting scope "+scope+" key "+key+" value "+value);
        WFCMessage.ModifyUserSettingReq request = WFCMessage.ModifyUserSettingReq.newBuilder()
                .setScope(scope)
                .setKey(key == null ? "" : key)
                .setValue(value == null ? "" : value)
                .build();
        sendMessage(Signal.PUBLISH, SubSignal.UP, request.toByteArray(), callback);
    }

    public ProtoMessage[] getMessages(int conversationType, String target, int line, long fromIndex, boolean before, int count, String withUser){
        log.i("conversationType "+conversationType+" target "+target+" line "+line +" fromIndex "+fromIndex+" before "+before+" count "+count+" withuser "+withUser);
        ProtoMessage[] protoMessages = null;
        if(!TextUtils.isEmpty(target)){
            protoMessages = imMemoryStore.getMessages(conversationType,target,line,fromIndex,before,count,withUser);
        }
        return protoMessages;
    }

    public ProtoMessage getMessage(long messageId){
        return imMemoryStore.getMessage(messageId);
    }

    public ProtoMessage getMessageByUid(long messageUid){
        return imMemoryStore.getMessageByUid(messageUid);
    }

    public boolean updateMessageContent(ProtoMessage msg){
        return imMemoryStore.updateMessageContent(msg);
    }

    public void getRemoteMessages(int conversationType, String target, int line, long beforeMessageUid, int count, JavaProtoLogic.ILoadRemoteMessagesCallback callback){
        //本地分页停在断层处后，界面拉远程即是在补断层
        long gapFloor = imMemoryStore.getConversationGapFloor(target);
        if(ConversationGapFiller.isFillingGap(gapFloor, beforeMessageUid)){
            fillConversationGap(conversationType, target, line, gapFloor, count, callback);
            return;
        }
        sendLoadRemoteMessages(conversationType, target, line, beforeMessageUid, count, callback);
    }

    /**
     * 补拉 gapUid 处的会话断层，结果落库并删除或下移断层，见 ConversationGapFiller
     * */
    public void fillConversationGap(int conversationType, String target, int line, long gapUid, int count, JavaProtoLogic.ILoadRemoteMessagesCallback callback){
        log.i("fill conversation gap target "+target+" gapUid "+gapUid);
        sendLoadRemoteMessages(conversationType, target, line, gapUid, count, new GapFillRequest(gapSink, target, gapUid, count, callback));
    }

    public ConversationGapFiller.GapSink getGapSink(){
        return gapSink;
    }

    private final ConversationGapFiller.GapSink gapSink = new ConversationGapFiller.GapSink() {
        @Override
        public boolean hasOlderMessage(String target, long beforeUid) {
            return imMemoryStore.hasOlderMessage(target, beforeUid);
        }

        @Override
        public void insertGap(String target, long beforeUid) {
            imMemoryStore.insertConversationGap(target, beforeUid);
        }

        @Override
        public void deleteGap(String target, long beforeUid) {
            imMemoryStore.deleteConversationGap(target, beforeUid);
        }

        @Override
        public long getLocalFloorUid(String target, long belowUid) {
            return imMemoryStore.getLocalFloorUid(target, belowUid);
        }

        @Override
        public boolean hasMessage(long messageUid) {
            return imMemoryStore.getMessageByUid(messageUid) != null;
        }

        @Override
        public boolean canPersistent(int contentType) {
            return imMemoryStore.canPersistent(contentType);
        }

        @Override
        public ProtoMessage convert(WFCMessage.Message message) {
            return convertProtoMessage(message);
        }

        @Override
        public void saveHistory(String target, List<ProtoMessage> messages, boolean isTail) {
            imMemoryStore.addHistoryMessages(target, messages, isTail);
        }
    };

    private void sendLoadRemoteMessages(int conversationType, String target, int line, long beforeMessageUid, int count, JavaProtoLogic.ILoadRemoteMessagesCallback callback){
        WFCMessage.LoadRemoteMessages.Builder loadRemoteMessagesBuilder = WFCMessage.LoadRemoteMessages.newBuilder();
        loadRemoteMessagesBuilder.setBeforeUid(beforeMessageUid);
        loadRemoteMessagesBuilder.setCount(count);
        WFCMessage.Conversation.Builder conversationBuilder = WFCMessage.Conversation.newBuilder();
        conversationBuilder.setLine(line);
        conversationBuilder.setTarget(target);
        conversationBuilder.setType(conversationType);
        loadRemoteMessagesBuilder.setConversation(conversationBuilder.build());
        sendMessage(Signal.PUBLISH,SubSignal.LRM,loadRemoteMessagesBuilder.build().toByteArray(),callback);
    }

    public void sendMessage(ProtoMessage msg, int expireDuration, JavaProtoLogic.ISendMessageCallback callback){

        //文件类型先上传到云
        String localMediaPath = msg.getContent().getLocalMediaPath();
        String remoteMediaPath = msg.getContent().getRemoteMediaUrl();
        log.i("messageId "+msg.getMessageId()+" local media path "+localMediaPath+" mediaType "+msg.getContent().getMediaType()+" remoteUrl "+remoteMediaPath);
        //注意这里的位置，不要随意调换有可能影响语音通话
        long protoMessageId = MessageShardingUtil.generateId();
        msg.setMessageId(protoMessageId);
        msg.setStatus(MessageStatus.Sending.value());
        callback.onPrepared(protoMessageId,System.currentTimeMillis());
        if(!TextUtils.isEmpty(localMediaPath) && TextUtils.isEmpty(remoteMediaPath)){
            uploadMedia(localMediaPath, msg.getContent().getMediaType(), new JavaProtoLogic.IUploadMediaCallback() {
                @Override
                public void onSuccess(String remoteUrl) {
                    msg.getContent().setRemoteMediaUrl(remoteUrl);
                    imMemoryStore.addProtoMessageByTarget(msg.getTarget(),msg,false);
                    WFCMessage.Message sendMessage = convertWFCMessage(msg);
                    sendMessage(Signal.PUBLISH,SubSignal.MS,protoMessageId,sendMessage.toByteArray(),callback);
                }

                @Override
                public void onProgress(long uploaded, long total) {
                     callback.onProgress(uploaded,total);
                }

                @Override
                public void onFailure(int errorCode) {
                      callback.onFailure(errorCode);
                      imMemoryStore.updateMessageStatus(protoMessageId,MessageStatus.Send_Failure.ordinal());
                }
            });
        } else {
            if(TextUtils.isEmpty(msg.getTarget())){
                log.i("target not null abort send message");
                return;
            }
            WFCMessage.Message sendMessage = convertWFCMessage(msg);
            imMemoryStore.addProtoMessageByTarget(msg.getTarget(),msg,false);
            sendMessage(Signal.PUBLISH,SubSignal.MS,protoMessageId,sendMessage.toByteArray(),callback);
        }
    }

    public boolean deleteMessage(long messageId){
        return imMemoryStore.deleteMessage(messageId);
    }


    /**
     * 主动拉取有回调
     * */
    public void pullMessage(long messageId,int type,Object callback){
        log.i("pullMessageId "+messageId);
        WFCMessage.PullMessageRequest pullMessageRequest = WFCMessage.PullMessageRequest.newBuilder()
                .setId(messageId)
                .setType(type)
                .build();
        sendMessage(Signal.PUBLISH,SubSignal.MP,pullMessageRequest.toByteArray(),callback);
    }

    /**
     * 通知拉取，无回调
     * */
    public void pullMessage(long messageId,int type){
        pullMessage(messageId,type,null);
    }



    /**
     * 创建群组
     * */
    public void createGroup(String groupId, String groupName, String groupPortrait, String[] memberIds, int[] notifyLines, ProtoMessageContent notifyMsg, JavaProtoLogic.IGeneralCallback2 callback){
        WFCMessage.GroupInfo groupInfo = WFCMessage.GroupInfo.newBuilder()
                .setName(groupName)
                .setTargetId(TextUtils.isEmpty(groupId)? "":groupId)
                .setPortrait(TextUtils.isEmpty(groupPortrait)? "":groupPortrait)
                .setType(ProtoConstants.GroupType.GroupType_Normal)
                .build();

        WFCMessage.Group.Builder groupBuilder = WFCMessage.Group.newBuilder();
        groupBuilder.setGroupInfo(groupInfo);
        if(memberIds != null){
            for(String memberId : memberIds){
                WFCMessage.GroupMember member = WFCMessage.GroupMember.newBuilder()
                        .setMemberId(memberId)
                        .setType(memberId.equals(getUserName())? ProtoConstants.GroupMemberType.GroupMemberType_Owner: ProtoConstants.GroupMemberType.GroupMemberType_Normal)
                        .build();
                groupBuilder.addMembers(member);
            }
        }
        List<Integer> lines = new ArrayList<>();
        if(notifyLines != null){
            for(int line : notifyLines){
                lines.add(line);
            }
        }
        WFCMessage.CreateGroupRequest.Builder createGroupRequestBuilder = WFCMessage.CreateGroupRequest.newBuilder();
        createGroupRequestBuilder.setGroup(groupBuilder.build());
        WFCMessage.MessageContent messageContent = convert2WfcMessageContent(notifyMsg);
        if(messageContent != null){
            createGroupRequestBuilder.setNotifyContent(messageContent);
        }
        createGroupRequestBuilder.addAllToLine(lines);
        sendMessage(Signal.PUBLISH,SubSignal.GC, createGroupRequestBuilder.build().toByteArray(),callback);
    }

    public ProtoGroupInfo getGroupInfo(String groupId, boolean refresh){
        log.i("getGroupInfo "+groupId+" refresh "+refresh);
        if(refresh || imMemoryStore.getGroupInfo(groupId) == null){
            WFCMessage.PullUserRequest.Builder pullUserRequestBuilder = WFCMessage.PullUserRequest.newBuilder();
            WFCMessage.UserRequest userRequest = WFCMessage.UserRequest.newBuilder().setUid(groupId).build();
            pullUserRequestBuilder.addRequest(userRequest);
            SimpleFuture<ProtoGroupInfo> getGroupInfofuture = sendMessageSync(Signal.PUBLISH,SubSignal.GPGI,pullUserRequestBuilder.build().toByteArray());
            try {
                getGroupInfofuture.get(500,TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return imMemoryStore.getGroupInfo(groupId);
    }


    public ProtoGroupMember[] getGroupMembers(String groupId, boolean forceUpdate){
        log.i("getGroupMembers "+groupId+" forceUpdate "+forceUpdate);
        //内存缓存未命中时返回的是空数组而不是null，空数组同样意味着本地没有数据，需要向服务端拉取
        ProtoGroupMember[] cachedGroupMembers = imMemoryStore.getGroupMembers(groupId);
        if(forceUpdate || cachedGroupMembers == null || cachedGroupMembers.length == 0){
            WFCMessage.PullGroupMemberRequest.Builder groupMemberBuilder = WFCMessage.PullGroupMemberRequest.newBuilder();
            groupMemberBuilder.setTarget(groupId);
            groupMemberBuilder.setHead(0);
            SimpleFuture<ProtoGroupMember[]> simpleFuture = sendMessageSync(Signal.PUBLISH,SubSignal.GPGM,groupMemberBuilder.build().toByteArray());
            try {
                ProtoGroupMember[] protoGroupMembers = simpleFuture.get(500,TimeUnit.MILLISECONDS);
                imMemoryStore.addGroupMember(groupId,protoGroupMembers);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return imMemoryStore.getGroupMembers(groupId);
    }


    public ProtoGroupMember getGroupMember(String groupId, String memberId){
        return imMemoryStore.getGroupMember(groupId,memberId);
    }

    public void addMembers(String groupId, String[] memberIds, int[] notifyLines, ProtoMessageContent notifyMsg, JavaProtoLogic.IGeneralCallback callback){
        log.i("groupId "+groupId +" memberIds "+memberIds);
        WFCMessage.AddGroupMemberRequest.Builder memberRequestBuilder = WFCMessage.AddGroupMemberRequest.newBuilder();
        memberRequestBuilder.setGroupId(groupId);
        for(String memberId : memberIds){
            WFCMessage.GroupMember groupMember = WFCMessage.GroupMember.newBuilder()
                    .setMemberId(memberId)
                    .setType(ProtoConstants.GroupMemberType.GroupMemberType_Normal)
                    .build();
            memberRequestBuilder.addAddedMember(groupMember);
        }
        sendMessage(Signal.PUBLISH,SubSignal.GAM,memberRequestBuilder.build().toByteArray(),callback);
    }

    public void kickoffMembers(String groupId, String[] memberIds, int[] notifyLines, ProtoMessageContent notifyMsg, JavaProtoLogic.IGeneralCallback callback){
        WFCMessage.RemoveGroupMemberRequest.Builder removeGroupMemberRequest = WFCMessage.RemoveGroupMemberRequest.newBuilder();
        removeGroupMemberRequest.setGroupId(groupId);
        for(String memberId : memberIds){
            removeGroupMemberRequest.addRemovedMember(memberId);
        }
        sendMessage(Signal.PUBLISH,SubSignal.GKM,removeGroupMemberRequest.build().toByteArray(),callback);
    }


    public void quitGroup(String groupId, int[] notifyLines, ProtoMessageContent notifyMsg, JavaProtoLogic.IGeneralCallback callback){
        log.i("quitGroup "+groupId+" hasnotifyMsg "+(notifyMsg != null));
        WFCMessage.QuitGroupRequest.Builder builder = WFCMessage.QuitGroupRequest.newBuilder();
        builder.setGroupId(groupId);
        sendMessage(Signal.PUBLISH,SubSignal.GQ,builder.build().toByteArray(),callback);
    }

    public void dismissGroup(String groupId, int[] notifyLines, ProtoMessageContent notifyMsg, JavaProtoLogic.IGeneralCallback callback){
        log.i("dismissGroup "+groupId+" hasnotifyMsg "+(notifyMsg != null));
        WFCMessage.DismissGroupRequest.Builder builder = WFCMessage.DismissGroupRequest.newBuilder();
        builder.setGroupId(groupId);
        sendMessage(Signal.PUBLISH, SubSignal.GD,builder.build().toByteArray(),callback);
    }

    public void modifyGroupInfo(String groupId, int modifyType, String newValue, int[] notifyLines, ProtoMessageContent notifyMsg, JavaProtoLogic.IGeneralCallback callback){
        log.i("groupId "+groupId+" modifyType "+modifyType+" new value "+newValue);
        WFCMessage.ModifyGroupInfoRequest.Builder modifyGroupInfoBuilder = WFCMessage.ModifyGroupInfoRequest.newBuilder();
        modifyGroupInfoBuilder.setGroupId(groupId);
        modifyGroupInfoBuilder.setType(modifyType);
        modifyGroupInfoBuilder.setValue(newValue);
        sendMessage(Signal.PUBLISH,SubSignal.GMI,modifyGroupInfoBuilder.build().toByteArray(),callback);
    }

    public void recallMessage(long messageUid, JavaProtoLogic.IGeneralCallback callback){
       log.i("recall message Uid "+messageUid);
        WFCMessage.INT64Buf int64Buf = WFCMessage.INT64Buf.newBuilder()
                .setId(messageUid)
                .build();
       sendMessage(Signal.PUBLISH,SubSignal.MR,int64Buf.toByteArray(),callback);
    }

    public void speechToText(long messageUid, JavaProtoLogic.IGeneralCallback2 callback){
        log.i("speechToText message Uid "+messageUid);
        WFCMessage.SpeechToTextRequest request = WFCMessage.SpeechToTextRequest.newBuilder()
                .setMessageUid(messageUid)
                .build();
        sendMessage(Signal.PUBLISH, SubSignal.ASR, request.toByteArray(), callback);
    }

    /**
     * 上报送达与已读回执
     * 送达回执,只上报单聊会话
     * @param target 会话target
     * @param tos 如果会话为群组,则发送改中接收消息的来源用户uid
     * */
    public void uploadReport(int reportType,String target, int conversationType,List<String> tos,JavaProtoLogic.IGeneralCallback callback){
        if(reportType == ProtoConstants.MessageReportType.ReportType_Delivery){
            if(conversationType == ProtoConstants.ConversationType.ConversationType_Private){
                WFCMessage.DeliveryReport deliveryReport = WFCMessage.DeliveryReport.newBuilder()
                        .setDate(System.currentTimeMillis())
                        .setTarget(target)
                        .build();
                sendMessage(Signal.PUBLISH, SubSignal.MDR,deliveryReport.toByteArray(),callback);
            }
        } else if(reportType == ProtoConstants.MessageReportType.ReportType_Read){
            WFCMessage.ReadReport.Builder readReport = WFCMessage.ReadReport.newBuilder()
                    .setTarget(target)
                    .setDate(System.currentTimeMillis())
                    .setType(conversationType);
            if(conversationType == ProtoConstants.ConversationType.ConversationType_Group){
                readReport.addAllTos(getImMemoryStore().getGroupReportUsers(target));
            }
            sendMessage(Signal.PUBLISH,SubSignal.MRR,readReport.build().toByteArray(),callback);
        }

    }

}
