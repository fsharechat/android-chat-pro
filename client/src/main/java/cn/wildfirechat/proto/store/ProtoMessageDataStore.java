package cn.wildfirechat.proto.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import cn.wildfirechat.message.core.MessageContentType;
import cn.wildfirechat.message.core.MessageStatus;
import cn.wildfirechat.model.Conversation;
import cn.wildfirechat.model.ProtoConversationInfo;
import cn.wildfirechat.model.ProtoMessage;
import cn.wildfirechat.model.ProtoUnreadCount;
import cn.wildfirechat.proto.UserSettingKeys;

import static cn.wildfirechat.message.core.MessageContentType.ContentType_Call_Accept;
import static cn.wildfirechat.message.core.MessageContentType.ContentType_Call_Accept_T;
import static cn.wildfirechat.message.core.MessageContentType.ContentType_Call_Taken;
import static cn.wildfirechat.message.core.MessageContentType.ContentType_Call_End;
import static cn.wildfirechat.message.core.MessageContentType.ContentType_Call_Modify;
import static cn.wildfirechat.message.core.MessageContentType.ContentType_Call_Signal;
import static cn.wildfirechat.remote.UserSettingScope.ConversationSilent;
import static cn.wildfirechat.remote.UserSettingScope.ConversationTop;

class ProtoMessageDataStore extends SqliteDatabaseStore{

    private long lastInsertedMessageRowId = -1;
    private long lastInsertedConversationRowId = -1;
    private Map<String,Integer> unReadCountMap = new ConcurrentHashMap<>();
    private Map<String,Integer> unReadMentionCountMap = new ConcurrentHashMap<>();
    private Map<String,Integer> unReadMentionAllCountMap = new ConcurrentHashMap<>();
    private Map<Integer,Map<String,String>> userSettingMap = new ConcurrentHashMap<>();
    // 与 userSettingMap 同为纯内存，必须一起生一起死：
    // 若把 version 持久化而设置没持久化，重启后客户端会误判「无需拉取」而永久丢失设置
    private volatile long userSettingVersion = 0;
    public static final String COLUMN_ID            = "id";
    public static final String COLUMN_MESSAGE_TARGET = "message_target";
    public static final String COLUMN_MESSAGE_DATA    = "message_data";
    public static final String COLUMN_MESSAGE_SEARCHABLE_DATA = "message_searchable_data";
    public static final String COLUMN_MESSAGE_ID   = "message_id";
    public static final String COLUMN_MESSAGE_UID    = "message_uid";
    public static final String COLUMN_DATE_CREATED  = "date_created";


    public static final String COLUMN_CONVERSATION_TYPE = "conversation_type";
    public static final String COLUMN_CONVERSATION_TARGET = "conversation_target";
    public static final String COLUMN_CONVERSATION_LINE = "conversation_line";
    public static final String COLUMN_CONVERSATION_INFO = "conversation_info";

    private String[] allMessageColumns = {
            COLUMN_ID,
            COLUMN_MESSAGE_TARGET,
            COLUMN_MESSAGE_ID,
            COLUMN_MESSAGE_UID,
            COLUMN_MESSAGE_DATA,
            COLUMN_DATE_CREATED
    };


    private String[] allConversationsColumns = {
            COLUMN_ID,
            COLUMN_CONVERSATION_TYPE,
            COLUMN_CONVERSATION_TARGET,
            COLUMN_CONVERSATION_LINE,
            COLUMN_CONVERSATION_INFO,
            COLUMN_DATE_CREATED
    };

    public ProtoMessageDataStore(Context context){
        super(context);
    }

    // 登出时清空内存态的用户设置及其 version，避免下一次登录的用户读到上一个账号残留的设置
    // （DataStoreFactory 是进程级单例，:marsservice 进程登出后不会被杀，userSettingMap/userSettingVersion 会跨账号存活）。
    @Override
    public void stop() {
        userSettingMap.clear();
        userSettingVersion = 0;
        super.stop();
    }

    public List<Map<String, Object>> queryMessageDatabase(String query, String orderBy) {
        List<Map<String, Object>> res = new ArrayList<>();
        if (isDatabaseOpen()) {
            Cursor cursor = database.query(ChatStoreHelper.TABLE_MESSAGES, allMessageColumns, query,
                    null, null, null, orderBy);

            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                Map<String, Object> eventMetadata = new HashMap<>();
                eventMetadata.put(COLUMN_ID, cursor.getLong(0));
                eventMetadata.put(COLUMN_MESSAGE_TARGET,cursor.getString(1));
                eventMetadata.put(COLUMN_MESSAGE_ID, cursor.getLong(2));
                eventMetadata.put(COLUMN_MESSAGE_UID, cursor.getLong(3));
                eventMetadata.put(COLUMN_MESSAGE_DATA, deserializer(cursor.getBlob(4)));
                eventMetadata.put(COLUMN_DATE_CREATED, cursor.getString(5));
                cursor.moveToNext();
                res.add(eventMetadata);
            }
            cursor.close();
        }
        return res;
    }

    public List<Map<String,Object>> queryConversation(String query,String orderBy){
        List<Map<String, Object>> res = new ArrayList<>();
        if (isDatabaseOpen()) {
            Cursor cursor = database.query(ChatStoreHelper.TABLE_CONVERSATIONS, allConversationsColumns, query,
                    null, null, null, orderBy);

            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                Map<String, Object> eventMetadata = new HashMap<>();
                eventMetadata.put(COLUMN_ID, cursor.getLong(0));
                eventMetadata.put(COLUMN_CONVERSATION_TYPE,cursor.getInt(1));
                eventMetadata.put(COLUMN_CONVERSATION_TARGET, cursor.getString(2));
                eventMetadata.put(COLUMN_CONVERSATION_LINE,cursor.getInt(3));
                eventMetadata.put(COLUMN_CONVERSATION_INFO, deserializer(cursor.getBlob(4)));
                eventMetadata.put(COLUMN_DATE_CREATED, cursor.getString(5));
                cursor.moveToNext();
                res.add(eventMetadata);
            }
            cursor.close();
        }
        return res;
    }


    public void insertProtoMessage(String target ,ProtoMessage protoMessage){
        if (isDatabaseOpen()) {
            byte[] bytes = serialize(protoMessage);
            ContentValues values = new ContentValues(4);
            values.put(COLUMN_MESSAGE_DATA, bytes);
            values.put(COLUMN_MESSAGE_TARGET,target);
            values.put(COLUMN_MESSAGE_UID,protoMessage.getMessageUid());
            values.put(COLUMN_MESSAGE_ID,protoMessage.getMessageId());
            values.put(COLUMN_MESSAGE_SEARCHABLE_DATA,protoMessage.getContent().getSearchableContent());
            logger.i("insert messageId  "+protoMessage.getMessageId()+" target "+protoMessage.getTarget()+" uid "+protoMessage.getMessageUid()+" content "+protoMessage.getContent().getSearchableContent());
            lastInsertedMessageRowId = database.insert(ChatStoreHelper.TABLE_MESSAGES, null, values);
        }
        logger.i("Added event to database: "+lastInsertedMessageRowId);
    }


    private void insertConversations(String target,int type, int line, ProtoConversationInfo protoConversationInfo){
        if (isDatabaseOpen()) {
            byte[] bytes = serialize(protoConversationInfo);
            ContentValues values = new ContentValues(4);
            values.put(COLUMN_CONVERSATION_INFO, bytes);
            values.put(COLUMN_CONVERSATION_TYPE,type);
            values.put(COLUMN_CONVERSATION_TARGET,target);
            values.put(COLUMN_CONVERSATION_LINE,line);
            lastInsertedConversationRowId = database.insert(ChatStoreHelper.TABLE_CONVERSATIONS, null, values);
        }
        logger.i("Added event to database: "+lastInsertedConversationRowId);
    }

    @Override
    public ProtoMessage getMessageByUid(long messageUid) {
        List<Map<String, Object>> res = queryMessageDatabase(COLUMN_MESSAGE_UID + "=" + messageUid, null);
        if (!res.isEmpty()) {
            Map<String,Object> protoMessageMap = res.get(0);
            ProtoMessage protoMessage = (ProtoMessage) protoMessageMap.get(COLUMN_MESSAGE_DATA);
            return protoMessage;
        }
        else {
            return null;
        }
    }

    @Override
    public boolean canPersistent(int contentType) {
        return contentType != MessageContentType.ContentType_Typing &&
                contentType != ContentType_Call_End &&
                contentType != ContentType_Call_Accept &&
                contentType != ContentType_Call_Signal &&
                contentType != ContentType_Call_Modify &&
                contentType != ContentType_Call_Accept_T &&
                contentType != ContentType_Call_Taken
                ;
    }

    /**
     * 发送消息时也会调用这里，所以这里也需要过滤消息
     * */
    @Override
    public void addProtoMessageByTarget(String target, ProtoMessage protoMessage, boolean isPush) {
        logger.i("add target "+target+" contentType "+protoMessage.getContent().getType());
        if(canPersistent(protoMessage.getContent().getType())){
            insertProtoMessage(target,protoMessage);
            if(isPush){
                //设置未读消息
                int unReadCount = 0;
                if(unReadCountMap.get(protoMessage.getTarget()) != null){
                    unReadCount = unReadCountMap.get(protoMessage.getTarget());
                }
                unReadCountMap.put(protoMessage.getTarget(),++unReadCount);
                updateMentionCount(protoMessage);
            }

            createOrUpdateConversation(target,protoMessage.getConversationType(),protoMessage);
        }
    }

    /**
     * 群聊收到消息时统计@我的未读数，会话列表据此显示[有人@我]。
     * mentionedType: 1-@指定成员 2-@全体成员
     * */
    private void updateMentionCount(ProtoMessage protoMessage){
        if(protoMessage.getDirection() != 1
                || protoMessage.getConversationType() != Conversation.ConversationType.Group.ordinal()){
            return;
        }
        int mentionedType = protoMessage.getContent().getMentionedType();
        if(mentionedType == 2){
            int mentionAllCount = unReadMentionAllCountMap.get(protoMessage.getTarget()) != null ? unReadMentionAllCountMap.get(protoMessage.getTarget()) : 0;
            unReadMentionAllCountMap.put(protoMessage.getTarget(),++mentionAllCount);
        } else if(mentionedType == 1 && userName != null){
            String[] mentionedTargets = protoMessage.getContent().getMentionedTargets();
            if(mentionedTargets != null){
                for(String mentionedTarget : mentionedTargets){
                    if(userName.equals(mentionedTarget)){
                        int mentionCount = unReadMentionCountMap.get(protoMessage.getTarget()) != null ? unReadMentionCountMap.get(protoMessage.getTarget()) : 0;
                        unReadMentionCountMap.put(protoMessage.getTarget(),++mentionCount);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void addProtoMessagesByTarget(String target, List<ProtoMessage> protoMessages, boolean isPush) {
        for(ProtoMessage protoMessage : protoMessages){
            logger.i("add protomessage target "+protoMessage.getTarget()+" messageId "+protoMessage.getMessageId());
            insertProtoMessage(target,protoMessage);
            if(isPush){
                //设置未读消息
                int unReadCount = 0;
                if(unReadCountMap.get(protoMessage.getTarget()) != null){
                    unReadCount = unReadCountMap.get(protoMessage.getTarget());
                }
                unReadCountMap.put(protoMessage.getTarget(),++unReadCount);
                updateMentionCount(protoMessage);
            }
        }
        ProtoMessage lastProtoMessage = protoMessages.get(protoMessages.size() -1);
        createOrUpdateConversation(target,lastProtoMessage.getConversationType(),lastProtoMessage);
    }

    @Override
    public ProtoMessage[] getMessages(int conversationType, String target) {
        return getMessages(conversationType,target,0,0,false,20,null);
    }

    // message_id 是各端本地时钟生成的雪花ID（发送方设备各不相同），跨设备不可比，
    // 直接按它排序会因设备时钟偏差导致乱序。message_uid 是服务端雪花ID（单一时钟、单调递增），
    // 排序优先用它；仅未收到ack的消息（uid=0）退回本地 message_id，两者同为 时间戳<<21 的雪花格式，数值可比。
    private static final String SORT_KEY = "(CASE WHEN " + COLUMN_MESSAGE_UID + " > 0 THEN " + COLUMN_MESSAGE_UID + " ELSE " + COLUMN_MESSAGE_ID + " END)";

    /**
     * 分页游标从UI层传来的是本地messageId，比较前需换算成与SORT_KEY同源的排序键
     */
    private long getSortKey(long messageId) {
        List<Map<String, Object>> res = queryMessageDatabase(COLUMN_MESSAGE_ID + "=" + messageId, null);
        if (!res.isEmpty()) {
            long messageUid = (long) res.get(0).get(COLUMN_MESSAGE_UID);
            if (messageUid > 0) {
                return messageUid;
            }
        }
        return messageId;
    }

    @Override
    public ProtoMessage[] getMessages(int conversationType, String target, int line, long fromIndex, boolean before, int count, String withUser) {
        String query = COLUMN_MESSAGE_TARGET + "=" + "'"+target+"'";
        // 断层补完前不返回断层以下的消息，避免空洞两侧拼在一起；本地读完后界面会走远程补拉
        long gapFloor = getConversationGapFloor(target);
        if(gapFloor > 0){
            query = query + " and ("+COLUMN_MESSAGE_UID+" = 0 or "+COLUMN_MESSAGE_UID+" >= "+gapFloor+")";
        }
        String orderBy = SORT_KEY + " DESC LIMIT " + count;
        if(fromIndex != 0){
            long fromKey = getSortKey(fromIndex);
            if(before){
                query = query +" and "+SORT_KEY+" < "+fromKey;
            } else {
                orderBy = SORT_KEY + " ASC LIMIT " + count;
                query = query +" and "+SORT_KEY+" > "+fromKey;
            }
        }
        List<Map<String, Object>> messageList = queryMessageDatabase(query,orderBy);
        if(messageList != null){
            int num = messageList.size();
            List<ProtoMessage> protoMessages = new ArrayList<>();
            if(before){
                for(int i = num -1 ;i >= 0; i--){
                    Map<String,Object> objectMap = messageList.get(i);
                    long messageId = (long) objectMap.get(COLUMN_MESSAGE_ID);
                    ProtoMessage protoMessage = (ProtoMessage) objectMap.get(COLUMN_MESSAGE_DATA);
//                    logger.i("proto messageUId "+protoMessage.getMessageUid()+" db messageId->"+messageId+" message status "+protoMessage.getStatus()+" contentType "+protoMessage.getContent().getType());
                    protoMessages.add(protoMessage);
                }
            } else {
                //如果时加载messageId之后的消息，不需要调转顺序
                for(Map<String,Object> messagesMap : messageList){
                    ProtoMessage protoMessage = (ProtoMessage) messagesMap.get(COLUMN_MESSAGE_DATA);
                    protoMessages.add(protoMessage);
                }
            }

            ProtoMessage[] protoMessagesArr = new ProtoMessage[protoMessages.size()];
            logger.i("target "+target+" from messageId "+fromIndex+" size "+protoMessages.size());
            return protoMessages.toArray(protoMessagesArr);
        } else {
            return null;
        }

    }

    @Override
    public boolean hasOlderMessage(String target, long beforeUid) {
        return getLocalFloorUid(target, beforeUid) > 0;
    }

    @Override
    public void insertConversationGap(String target, long beforeUid) {
        if (isDatabaseOpen()) {
            ContentValues values = new ContentValues(2);
            values.put("gap_target", target);
            values.put("gap_before_uid", beforeUid);
            database.insertWithOnConflict(ChatStoreHelper.TABLE_CONVERSATION_GAPS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    @Override
    public void deleteConversationGap(String target, long beforeUid) {
        if (isDatabaseOpen()) {
            database.delete(ChatStoreHelper.TABLE_CONVERSATION_GAPS, "gap_target = ? and gap_before_uid = ?",
                    new String[]{target, String.valueOf(beforeUid)});
        }
    }

    @Override
    public long getConversationGapFloor(String target) {
        return queryLong("select max(gap_before_uid) from " + ChatStoreHelper.TABLE_CONVERSATION_GAPS
                + " where gap_target = ? and gap_before_uid < ?", new String[]{target, String.valueOf(Long.MAX_VALUE)});
    }

    @Override
    public long getLocalFloorUid(String target, long belowUid) {
        return queryLong("select max(" + COLUMN_MESSAGE_UID + ") from " + ChatStoreHelper.TABLE_MESSAGES
                + " where " + COLUMN_MESSAGE_TARGET + " = ? and " + COLUMN_MESSAGE_UID + " > 0 and " + COLUMN_MESSAGE_UID + " < ?",
                new String[]{target, String.valueOf(belowUid)});
    }

    private long queryLong(String sql, String[] args) {
        long value = 0;
        if (isDatabaseOpen()) {
            Cursor cursor = database.rawQuery(sql, args);
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                value = cursor.getLong(0);
            }
            cursor.close();
        }
        return value;
    }

    @Override
    public void addHistoryMessages(String target, List<ProtoMessage> protoMessages, boolean updateConversation) {
        if (protoMessages.isEmpty()) {
            return;
        }
        for (ProtoMessage protoMessage : protoMessages) {
            insertProtoMessage(target, protoMessage);
        }
        if (updateConversation) {
            ProtoMessage lastProtoMessage = protoMessages.get(protoMessages.size() - 1);
            createOrUpdateConversation(target, lastProtoMessage.getConversationType(), lastProtoMessage);
        }
    }

    @Override
    public List<ProtoMessage> getSendFailedMessages(String target) {
        String query = COLUMN_MESSAGE_TARGET + "=" + "'"+target+"'" + " and "+COLUMN_MESSAGE_UID +"= 0";
        String orderBy = "message_id DESC, message_uid DESC LIMIT 20";
        List<Map<String, Object>> messageList = queryMessageDatabase(query,orderBy);

        List<ProtoMessage> protoMessages = new ArrayList<>();
        logger.i("getSendFailedMessages target "+target+" size "+messageList.size());
        if(!messageList.isEmpty()){
            for(Map<String,Object> protoMessageMap : messageList){
                ProtoMessage protoMessage = (ProtoMessage) protoMessageMap.get(COLUMN_MESSAGE_DATA);
                if(protoMessage.getStatus() != MessageStatus.Sent.ordinal()){
                    logger.i("send failed message id="+protoMessage.getMessageId()+" messageUid="+protoMessage.getMessageUid());
                    protoMessages.add(protoMessage);
                }
            }
        }
        return protoMessages;
    }

    @Override
    public ProtoMessage getMessage(long messageId) {
        List<Map<String, Object>> res = queryMessageDatabase(COLUMN_MESSAGE_ID + "=" + messageId, null);
        logger.i("getMessage messageId "+messageId+" res "+res);
        if (!res.isEmpty()) {
            Map<String,Object> protoMessageMap = res.get(0);
            ProtoMessage protoMessage = (ProtoMessage) protoMessageMap.get(COLUMN_MESSAGE_DATA);
            return protoMessage;
        }
        else {
            return null;
        }
    }

    @Override
    public boolean deleteMessage(long messageId) {
        int retval = -1;
        if (isDatabaseOpen()) {
            retval = database.delete(ChatStoreHelper.TABLE_MESSAGES,
                    COLUMN_MESSAGE_ID + "=" + messageId, null);
        }
        logger.i("Removed event from database: "+ messageId);
        return retval == 1;
    }

    @Override
    public ProtoMessage[] filterProMessage(ProtoMessage[] protoMessages) {
        return protoMessages;
    }

    @Override
    public boolean updateMessageContent(ProtoMessage msg) {
        int retval = -1;
        if(isDatabaseOpen()){
            ProtoMessage updateProtoMessage = getMessage(msg.getMessageId());
            if(updateProtoMessage != null){
                updateProtoMessage.setContent(msg.getContent());
                ContentValues contentValues = new ContentValues(1);
                contentValues.put(COLUMN_MESSAGE_DATA,serialize(updateProtoMessage));
                retval = database.update(ChatStoreHelper.TABLE_MESSAGES,contentValues,COLUMN_MESSAGE_ID + "=" + msg.getMessageId(),null);
                logger.i("update protomessage Content id "+msg.getMessageId());
                createOrUpdateConversation(updateProtoMessage.getTarget(),updateProtoMessage.getConversationType(),updateProtoMessage);
            }

            return retval == 1;
        }
        return false;
    }

    @Override
    public boolean updateMessageStatus(long protoMessageId, int status) {
        int retval = -1;
        if(isDatabaseOpen()){
            ProtoMessage updateProtoMessage = getMessage(protoMessageId);
            if(updateProtoMessage != null){
                updateProtoMessage.setStatus(status);
                ContentValues contentValues = new ContentValues(1);
                contentValues.put(COLUMN_MESSAGE_DATA,serialize(updateProtoMessage));
                retval = database.update(ChatStoreHelper.TABLE_MESSAGES,contentValues,COLUMN_MESSAGE_ID + "=" + protoMessageId,null);
                logger.i("update protomessage status "+status);
                createOrUpdateConversation(updateProtoMessage.getTarget(),updateProtoMessage.getConversationType(),updateProtoMessage);
                return retval == 1;
            }
        }
        return false;
    }

    @Override
    public boolean updateMessageUid(long protoMessageId, long messageUid) {
        logger.i("updateMessageUid protoMessageId "+protoMessageId+" uid "+messageUid);
        int retval = -1;
        if(isDatabaseOpen()){
            ProtoMessage updateProtoMessage = getMessage(protoMessageId);
            if(updateProtoMessage != null){
                updateProtoMessage.setMessageUid(messageUid);
                ContentValues contentValues = new ContentValues(2);
                contentValues.put(COLUMN_MESSAGE_UID,messageUid);
                contentValues.put(COLUMN_MESSAGE_DATA,serialize(updateProtoMessage));
                retval = database.update(ChatStoreHelper.TABLE_MESSAGES,contentValues,COLUMN_MESSAGE_ID + "=" + protoMessageId,null);
                logger.i("update protomessage uid "+messageUid);
                createOrUpdateConversation(updateProtoMessage.getTarget(),updateProtoMessage.getConversationType(),updateProtoMessage);
                return retval == 1;
            }
        }
        return false;
    }

    @Override
    public ProtoMessage getLastMessage(String target) {
        List<Map<String, Object>> res = queryMessageDatabase(COLUMN_MESSAGE_TARGET + "=" + "'"+target+"'", SORT_KEY + " DESC LIMIT 1");
        if (!res.isEmpty()) {
            Map<String,Object> protoMessageMap = res.get(0);
            ProtoMessage protoMessage = (ProtoMessage) protoMessageMap.get(COLUMN_MESSAGE_DATA);
            return protoMessage;
        }
        else {
            return null;
        }
    }

    @Override
    public void clearUnreadStatus(int conversationType, String target, int line) {
        unReadCountMap.put(target,0);
        unReadMentionCountMap.put(target,0);
        unReadMentionAllCountMap.put(target,0);
        ProtoConversationInfo protoConversationInfo  = getConversation(conversationType,target,line);
        if(protoConversationInfo != null){
            ProtoUnreadCount protoUnreadCount = new ProtoUnreadCount();
            protoUnreadCount.setUnread(0);
            protoConversationInfo.setUnreadCount(protoUnreadCount);
            updateConversation(conversationType,target,line,protoConversationInfo);
        }
    }

    @Override
    public int getUnreadCount(String target) {
        return unReadCountMap.get(target) == null ? 0 : unReadCountMap.get(target);
    }

    @Override
    public void removeConversation(int conversationType, String target, int line, boolean clearMsg) {
        if(isDatabaseOpen()){
            int delnum = database.delete(ChatStoreHelper.TABLE_CONVERSATIONS,COLUMN_CONVERSATION_TARGET +"="+"'"+target+"'"+" and "+COLUMN_CONVERSATION_TYPE+"="+conversationType,null);
            logger.i("removeConversation target "+target+" conversation type "+conversationType+" deleted "+(delnum == 1));
        }
    }

    @Override
    public void setConversationDraft(int conversationType, String target, int line, String draft) {
        ProtoConversationInfo protoConversationInfo = getConversation(conversationType,target,line);
        if(protoConversationInfo != null){
            protoConversationInfo.setDraft(draft);
            updateConversation(conversationType,target,line,protoConversationInfo);
        }
    }

    private ProtoConversationInfo createOrUpdateConversation(String target, int type, ProtoMessage lastProtoMessage){
        ProtoConversationInfo protoConversationInfo = newProtoConversation(target,type,lastProtoMessage);
        List<Map<String,Object>> conversation = queryConversation(COLUMN_CONVERSATION_TARGET +"="+"'"+target+"'"+" and "+COLUMN_CONVERSATION_TYPE+"="+type,null);
        if(conversation != null && conversation.size() == 1){
            protoConversationInfo = (ProtoConversationInfo) conversation.get(0).get(COLUMN_CONVERSATION_INFO);
            protoConversationInfo.setLastMessage(lastProtoMessage);
            protoConversationInfo.setTimestamp(lastProtoMessage.getTimestamp());
            setUnReadCount(protoConversationInfo);
            updateConversation(type,target,0,protoConversationInfo);
        } else {
            insertConversations(target,type,0,protoConversationInfo);
        }
        return protoConversationInfo;
    }

    private boolean updateConversation(int conversationType, String target, int line, ProtoConversationInfo protoConversationInfo){
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(COLUMN_CONVERSATION_INFO,serialize(protoConversationInfo));
        int updateNum = database.update(ChatStoreHelper.TABLE_CONVERSATIONS,contentValues,COLUMN_CONVERSATION_TARGET +"="+"'"+target+"'"+" and "+COLUMN_CONVERSATION_TYPE+"="+conversationType,null);
        logger.i("update conversation "+target+" type "+conversationType+" line "+line+" updated "+(updateNum == 1));
        return updateNum == 1;
    }

    private ProtoConversationInfo newProtoConversation(String target, int type, ProtoMessage lastProtoMessage){
        ProtoConversationInfo protoConversationInfo = new ProtoConversationInfo();
        protoConversationInfo.setConversationType(type);
        protoConversationInfo.setLine(0);
        protoConversationInfo.setTarget(target);
        protoConversationInfo.setTop(conversationSetting(ConversationTop,type,target));
        protoConversationInfo.setSilent(conversationSetting(ConversationSilent,type,target));
        protoConversationInfo.setLastMessage(lastProtoMessage);
        setUnReadCount(protoConversationInfo);
        protoConversationInfo.setTimestamp(lastProtoMessage != null ? lastProtoMessage.getTimestamp() : System.currentTimeMillis());
        return protoConversationInfo;
    }

    private void setUnReadCount(ProtoConversationInfo protoConversationInfo){
        String target = protoConversationInfo.getTarget();
        ProtoUnreadCount protoUnreadCount = new ProtoUnreadCount();
        protoUnreadCount.setUnread(getUnreadCount(target));
        protoUnreadCount.setUnreadMention(unReadMentionCountMap.get(target) != null ? unReadMentionCountMap.get(target) : 0);
        protoUnreadCount.setUnreadMentionAll(unReadMentionAllCountMap.get(target) != null ? unReadMentionAllCountMap.get(target) : 0);
        protoConversationInfo.setUnreadCount(protoUnreadCount);
    }

    private boolean conversationSetting(int scope,int conversationType,String target){
        boolean flag = false;
        String top = getUserSetting(scope,UserSettingKeys.conversationKey(conversationType, 0, target));
        if(!TextUtils.isEmpty(top)){
            flag = top.equals("1");
        }
        logger.i(scope+"-"+conversationType + "-" + 0 + "-" + target+" flag->"+flag);
        return flag;
    }

    @Override
    public List<ProtoConversationInfo> getPrivateConversations() {
        logger.i("getPrivateConversations");
        List<Map<String,Object>> privateConversations = queryConversation(COLUMN_CONVERSATION_TYPE +"="+ Conversation.ConversationType.Single.ordinal(),null);
        List<ProtoConversationInfo> protoConversationInfoList = new ArrayList<>();
        for(Map<String,Object> objectMap : privateConversations){
            ProtoConversationInfo protoConversationInfo = (ProtoConversationInfo) objectMap.get(COLUMN_CONVERSATION_INFO);
            protoConversationInfoList.add(protoConversationInfo);
        }
        return protoConversationInfoList;
    }

    @Override
    public List<ProtoConversationInfo> getGroupConversations() {
        logger.i("getGroupConversations");
        List<Map<String,Object>> groupConversations = queryConversation(COLUMN_CONVERSATION_TYPE +"="+ Conversation.ConversationType.Group.ordinal(),null);
        List<ProtoConversationInfo> protoConversationInfoList = new ArrayList<>();
        for(Map<String,Object> objectMap : groupConversations){
            ProtoConversationInfo protoConversationInfo = (ProtoConversationInfo) objectMap.get(COLUMN_CONVERSATION_INFO);
            protoConversationInfoList.add(protoConversationInfo);
        }
        return protoConversationInfoList;
    }

    @Override
    public ProtoConversationInfo getConversation(int conversationType, String target, int line) {
        logger.i("getConversation type "+conversationType+" target "+target);
        ProtoConversationInfo protoConversationInfo = new ProtoConversationInfo();
        if(!TextUtils.isEmpty(target)){
            List<Map<String,Object>> conversation = queryConversation(COLUMN_CONVERSATION_TARGET +"="+"'"+target+"'"+" and "+COLUMN_CONVERSATION_TYPE+"="+conversationType,null);
            if(conversation != null && conversation.size() == 1){
                protoConversationInfo = (ProtoConversationInfo) conversation.get(0).get(COLUMN_CONVERSATION_INFO);
                updateConverSationWhenNeed(target,conversationType,line,protoConversationInfo);
            } else {
                protoConversationInfo = newProtoConversation(target,conversationType,null);
                insertConversations(target,conversationType,0,protoConversationInfo);
            }
        }
        return protoConversationInfo;
    }

    @Override
    public List<ProtoConversationInfo> getConversations(int[] conversationTypes, int[] lines) {
        String queryString = "";
        for(int i=0; i<conversationTypes.length; i++){
            if(i > 0){
                queryString += " OR ";
            }
            queryString += COLUMN_CONVERSATION_TYPE +"="+conversationTypes[i];
        }
        logger.i("getConversations query :"+queryString);
        List<Map<String,Object>> conversations = queryConversation(queryString,null);
        List<ProtoConversationInfo> protoConversationInfoList = new ArrayList<>();
        for(Map<String,Object> objectMap : conversations){
            ProtoConversationInfo protoConversationInfo = (ProtoConversationInfo) objectMap.get(COLUMN_CONVERSATION_INFO);
            updateConverSationWhenNeed(protoConversationInfo.getTarget(),protoConversationInfo.getConversationType(),protoConversationInfo.getLine(),protoConversationInfo);
            protoConversationInfoList.add(protoConversationInfo);
        }
        return protoConversationInfoList;
    }

    private void updateConverSationWhenNeed(String target,int conversationType,int line,ProtoConversationInfo lastProtoConversation){
        ProtoMessage lastMessage = getLastMessage(target);
        if(lastMessage != null &&
                lastProtoConversation.getLastMessage() != null &&
                lastMessage.getMessageId() != lastProtoConversation.getLastMessage().getMessageId()){
            lastProtoConversation.setLastMessage(lastMessage);
            updateConversation(conversationType,target,line,lastProtoConversation);
        }
    }

    @Override
    public void setUserSetting(int scope, String key, String value) {
        Map<String,String> scopeMap = userSettingMap.computeIfAbsent(scope, k -> new ConcurrentHashMap<>());
        scopeMap.put(key,value);

        // 只有置顶/免打扰的 key 是会话三元组，才需要回写会话对象。
        // scope 6 的 key 是群 id、scope 2 的 key 是空串，旧代码在这里 parseInt 会抛 NumberFormatException
        if(UserSettingKeys.isConversationScope(scope)){
            String[] keys = UserSettingKeys.parseConversationKey(key);
            if(keys == null){
                logger.e("illegal conversation setting key scope="+scope+" key="+key);
                return;
            }
            int conversationType = Integer.parseInt(keys[0]);
            int line = Integer.parseInt(keys[1]);
            String target = keys[2];
            // 只回写已存在的会话，不能用 getConversation：它在查不到行时会插入一条空会话
            // （lastMessage=null，timestamp=now），reinstall 后 UG 先于消息拉取执行，会给每条
            // scope-3/1 设置（含服务端永久保留的 "0" tombstone）都插出一条排在最前面的空白会话。
            // 跳过不会丢设置：会话稍后由 newProtoConversation 创建时会从 userSettingMap 读到本次写入的值。
            ProtoConversationInfo updateProtoConversation = queryExistingConversation(conversationType, target);
            if(updateProtoConversation != null){
                if(scope == ConversationTop){
                    updateProtoConversation.setTop("1".equals(value));
                } else if(scope == ConversationSilent){
                    updateProtoConversation.setSilent("1".equals(value));
                }
                updateConversation(conversationType,target,line,updateProtoConversation);
            }
        }
    }

    // 与 getConversation 用同一条查询语句判断会话是否已存在，但查不到时不插入新行
    private ProtoConversationInfo queryExistingConversation(int conversationType, String target){
        List<Map<String,Object>> conversation = queryConversation(COLUMN_CONVERSATION_TARGET +"="+"'"+target+"'"+" and "+COLUMN_CONVERSATION_TYPE+"="+conversationType,null);
        if(conversation != null && conversation.size() == 1){
            return (ProtoConversationInfo) conversation.get(0).get(COLUMN_CONVERSATION_INFO);
        }
        return null;
    }

    @Override
    public String getUserSetting(int scope, String key) {
        Map<String,String> scopeMap = userSettingMap.get(scope);
        if(scopeMap != null){
            return scopeMap.get(key);
        }
        return null;
    }

    @Override
    public Map<String, String> getUserSettings(int scope) {
        Map<String,String> scopeMap = userSettingMap.get(scope);
        return scopeMap == null ? null : new HashMap<>(scopeMap);
    }

    @Override
    public long getUserSettingVersion() {
        return userSettingVersion;
    }

    @Override
    public void setUserSettingVersion(long version) {
        this.userSettingVersion = version;
    }
}
