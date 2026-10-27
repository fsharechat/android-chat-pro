package cn.wildfirechat.proto.store;


import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.wildfirechat.model.ProtoConversationInfo;
import cn.wildfirechat.model.ProtoConversationSearchresult;
import cn.wildfirechat.model.ProtoFriendRequest;
import cn.wildfirechat.model.ProtoGroupInfo;
import cn.wildfirechat.model.ProtoGroupMember;
import cn.wildfirechat.model.ProtoGroupSearchResult;
import cn.wildfirechat.model.ProtoMessage;
import cn.wildfirechat.model.ProtoUserInfo;

public interface ImMemoryStore {
    /**当前登录用户id，用于判断群消息中@的是不是自己*/
    void setUserName(String userName);
    List<String> getFriendList();
    String[] getFriendListArr();
    void setFriendArr(String[] friendArr,boolean refresh);
    void setFriendArr(String[] friendArr);
    boolean hasFriend();
    boolean isMyFriend(String userId);
    ProtoUserInfo[] searchFriends(String keyword);
    long getFriendRequestHead();
    void setFriendRequestHead(long friendRequestHead);
    ProtoFriendRequest[] getIncomingFriendRequest();
    void clearProtoFriendRequest();
    void addProtoFriendRequest(ProtoFriendRequest protoFriendRequest);
    void addProtoMessageByTarget(String target, ProtoMessage protoMessage, boolean isPush);
    void addProtoMessagesByTarget(String target, List<ProtoMessage> protoMessages, boolean isPush);
    ProtoMessage[] searchMessage(int conversationType, String target, int line, String keyword);
    ProtoMessage[] getMessages(int conversationType, String target);
    ProtoMessage[] getMessages(int conversationType, String target, int line, long fromIndex, boolean before, int count, String withUser);
    List<ProtoMessage> getSendFailedMessages(String target);
    ProtoMessage getMessage(long messageId);
    ProtoMessage getMessageByUid(long messageUid);
    boolean deleteMessage(long messageId);
    ProtoMessage[] filterProMessage(ProtoMessage[] protoMessages);
    boolean updateMessageContent(ProtoMessage msg);
    boolean updateMessageStatus(long protoMessageId,int status);
    boolean updateMessageUid(long protoMessageId,long messageUid);
    boolean canPersistent(int contentType);
    ProtoMessage getLastMessage(String target);
    long getTargetLastMessageId(String targetId);
    long getLastMessageSeq();
    void updateMessageSeq(long messageSeq);
    long increaseMessageSeq();
    void clearUnreadStatus(int conversationType, String target, int line);
    int getUnreadCount(String target);
    ProtoConversationSearchresult[] searchConversation(String keyword, int[] conversationTypes, int[] lines);
    void createPrivateConversation(String target);
    void removeConversation(int conversationType, String target, int line, boolean clearMsg);
    void setConversationDraft(int conversationType, String target, int line, String draft);
    List<ProtoConversationInfo> getPrivateConversations();
    void createGroupConversation(String groupId);
    List<ProtoConversationInfo> getGroupConversations();
    ProtoConversationInfo getConversation(int conversationType, String target, int line);
    List<ProtoConversationInfo> getConversations(int[] conversationTypes, int[] lines);
    ProtoGroupInfo getGroupInfo(String groupId);
    void addGroupInfo(String groupId,ProtoGroupInfo protoGroupInfo,boolean refresh);
    ProtoGroupMember[] getGroupMembers(String groupId);
    void addGroupMember(String groupId,ProtoGroupMember protoGroupMember);
    void addGroupMember(String groupId,ProtoGroupMember[] protoGroupMembers);
    ProtoGroupSearchResult[] searchGroups(String keyword);
    ProtoGroupMember getGroupMember(String groupId, String memberId);
    ProtoUserInfo getUserInfo(String userId);
    ProtoUserInfo[] getUserInfos(String[] userIds);
    void addUserInfo(ProtoUserInfo protoUserInfos);
    void setUserSetting(int scope, String key, String value);
    String getUserSetting(int scope, String key);
    Map<String, String> getUserSettings(int scope);
    long getUserSettingVersion();
    void setUserSettingVersion(long version);
    void saveRequestProtoMessageIds(Set<String> protoMessageIds);
    Set<String> getRequestProtoMessageIds();
    void stop();
    /** 本地该会话是否有 0 < uid < beforeUid 的消息 */
    boolean hasOlderMessage(String target, long beforeUid);
    void insertConversationGap(String target, long beforeUid);
    void deleteConversationGap(String target, long beforeUid);
    /** 该会话最高的普通断层（不含尾部断层），本地分页不返回低于它的消息；0 表示没有 */
    long getConversationGapFloor(String target);
    /** 断层下方本地最新一条的 uid，没有返回 0 */
    long getLocalFloorUid(String target, long belowUid);
    /** 补拉断层得到的消息落库，updateConversation 为 false 时不改会话的最后一条消息、不计未读 */
    void addHistoryMessages(String target, List<ProtoMessage> protoMessages, boolean updateConversation);
    void setGroupReportUsers(String target,String user);
    List<String> getGroupReportUsers(String target);
}
