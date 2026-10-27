package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import cn.wildfirechat.message.core.MessageStatus;
import cn.wildfirechat.model.ProtoMessage;
import cn.wildfirechat.proto.ConversationGapFiller;
import cn.wildfirechat.proto.JavaProtoLogic;
import cn.wildfirechat.proto.ProtoConstants;
import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.WFCMessage;


/**
 * 用于处理pull消息的handler，消息发送流程如下：
 * --通知拉取方式
 * 1.服务端首先发送MN信令通知用户需要拉去消息
 * 2.用户发送MP信令拉去消息，拉去消息逻辑时根据消息序列号递增拉去，有可能一段时间内出现消息重复的现象
 * 为了解决消息重复拉去的问题
 * 采用直接发送推送信令，不会出现重复拉取的问题
 * --主动拉取消息拉取
 * 用户登录完成时，主动发起MP信令拉去消息，这类消息不是push类型，属于用户主动发起的消息拉取行为
 *
 * --transparent消息
 * 这部分消息大多是通话信令，当然也包括正在输入等状态指令，这些消息是服务端直接推送下来，不走notify策略
 * */
public class ReceiveMessageHandler extends AbstractMessageHandler {

    //专门处理下拉消息，保证消息处理严格有序
    public Executor workMessageExecutor = Executors.newFixedThreadPool(1);

    //与服务端单会话截断条数一致
    private static final int TAIL_GAP_FILL_COUNT = 20;

    public ReceiveMessageHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUB_ACK == header.getSignal() && SubSignal.MP == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        int errorCode = byteBufferList.get();
        logger.i("receive message code "+errorCode +" remain "+" body count "+header.getLength()+" "+byteBufferList.remaining());
        if(errorCode == 0){
            try {
                WFCMessage.PullMessageResult pullMessageResult = WFCMessage.PullMessageResult.parseFrom(byteBufferList.getAllByteArray());
                workMessageExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        long head = pullMessageResult.getHead();
                        logger.i("message count "+pullMessageResult.getMessageCount()+" head "+head);
                        //服务端截断的会话先记断层再落库消息：落库过程中会回调界面，断层必须已经生效
                        List<WFCMessage.ConversationGap> tailGaps = ConversationGapFiller.recordGaps(protoService.getGapSink(), pullMessageResult.getGapList());
                        boolean isUserPull = false;
                        RequestInfo requestInfo = protoService.requestMap.remove(header.getMessageId());
                        if(requestInfo != null && requestInfo.getCallback() != null){
                            isUserPull = true;
                        }
                        if(isUserPull){
                            processUserPullMessages(pullMessageResult.getMessageList(),requestInfo);
                        } else {
                            processNotifyPullMessage(pullMessageResult.getMessageList());
                        }
                        //忽略通话transparent信令
                        if(head > 0){
                            protoService.getImMemoryStore().updateMessageSeq(head);
                        }
                        for(WFCMessage.ConversationGap tailGap : tailGaps){
                            fillTailGap(tailGap);
                        }
                    }
                });
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 尾部断层：该会话一条都没下发，本地缺的是最新一段，立即补拉并刷新会话
     * */
    private void fillTailGap(WFCMessage.ConversationGap tailGap){
        WFCMessage.Conversation conversation = tailGap.getConversation();
        protoService.fillConversationGap(conversation.getType(), conversation.getTarget(), conversation.getLine(),
                tailGap.getBeforeUid(), TAIL_GAP_FILL_COUNT, new JavaProtoLogic.ILoadRemoteMessagesCallback() {
                    @Override
                    public void onSuccess(ProtoMessage[] messages) {
                        if(messages.length > 0){
                            JavaProtoLogic.onReceiveMessage(new ProtoMessage[]{messages[messages.length - 1]},false);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        logger.i("fill tail gap of "+conversation.getTarget()+" failed "+errorCode);
                    }
                });
    }

    /**
     * 处理用户主动拉取的消息
     * */
    private void processUserPullMessages(List<WFCMessage.Message> wfcMessageList,RequestInfo requestInfo){
        Map<String,List<ProtoMessage>> protoMessageMap = new HashMap<>();
        for(WFCMessage.Message wfcMessage : wfcMessageList){
            ProtoMessage protoMessage = protoService.convertProtoMessage(wfcMessage);
            //logger.i("current message target "+protoMessage.getTarget()+" contentType "+protoMessage.getContent().getType());

            ProtoMessage existProtoMessage = protoService.getImMemoryStore().getMessageByUid(protoMessage.getMessageUid());
            if(existProtoMessage == null){
                if(protoService.getImMemoryStore().canPersistent(protoMessage.getContent().getType())){
                    String target = protoMessage.getTarget();
                    List<ProtoMessage> protoMessages = protoMessageMap.get(target);
                    if(protoMessages == null){
                        protoMessages = new ArrayList<>();
                    }
                    protoMessages.add(protoMessage);
                    protoMessageMap.put(target,protoMessages);
                }
            }
        }
        List<ProtoMessage> conversationProtoMessage = new ArrayList<>();
        for(Map.Entry<String,List<ProtoMessage>> entry : protoMessageMap.entrySet()){
            String target = entry.getKey();
            //logger.i("current conversation target "+target);
            List<ProtoMessage> addProtoMessages = entry.getValue();
            conversationProtoMessage.add(addProtoMessages.get(addProtoMessages.size() - 1));
            protoService.getImMemoryStore().addProtoMessagesByTarget(target,addProtoMessages,false);
        }
        //通知会话显示
        ProtoMessage[] conversationProtoMessageArr = new ProtoMessage[conversationProtoMessage.size()];
        conversationProtoMessage.toArray(conversationProtoMessageArr);
        JavaProtoLogic.onReceiveMessage(conversationProtoMessageArr,false);

        JavaProtoLogic.IGeneralCallback  iGeneralCallback = (JavaProtoLogic.IGeneralCallback) requestInfo.getCallback();
        iGeneralCallback.onSuccess();
    }

    private void processNotifyPullMessage(List<WFCMessage.Message> wfcMessageList){
        List<ProtoMessage> resultProtoMessageList = new ArrayList<>();
        for(WFCMessage.Message wfcMessage : wfcMessageList){
            ProtoMessage protoMessage = protoService.convertProtoMessage(wfcMessage);
            logger.i("target-> "+protoMessage.getTarget()+" messageType-> "+wfcMessage.getContent().getType());
            computeGroupSender(protoMessage);
            //上报送达回执报告,目前只上报notify下拉消息送达回执报告
            uploadReport(protoMessage);

            //为什么要去重，因为语音通话消息信令发送频繁，容易出现消息重复，可能影响通话流程
            //这里的消息最好在一个线程里面进行，不然插入数据还没生效，可能出现幻读，否则会出现信令重复，可能出现意想不到的结果
            ProtoMessage existProtoMessage = protoService.getImMemoryStore().getMessageByUid(protoMessage.getMessageUid());
            if(existProtoMessage == null) {
                List<ProtoMessage> sendFailedMessages = protoService.getImMemoryStore().getSendFailedMessages(protoMessage.getTarget());
                boolean isSendFailMessage = false;
                for(ProtoMessage failedMessage : sendFailedMessages){
                    if(failedMessage.getMessageId() == protoMessage.getMessageId()){
                        logger.i("failed message id ="+failedMessage.getMessageId()+" has send successful");
                        boolean updateUid = protoService.getImMemoryStore().updateMessageUid(protoMessage.getMessageId(),protoMessage.getMessageUid());
                        boolean updateStatus = protoService.getImMemoryStore().updateMessageStatus(protoMessage.getMessageId(), MessageStatus.Sent.ordinal());
                        logger.i("messageUid "+protoMessage.getMessageUid()+"updateUid "+updateUid+" updateStatus "+updateStatus);
                        isSendFailMessage = true;

                        //TODO 通知上层界面消息已经发送成功
                        JavaProtoLogic.onSendMessageStatusUpdate(protoMessage.getMessageId(),MessageStatus.Sent.ordinal());
                        break;
                    }
                }

                if(!isSendFailMessage){
                    resultProtoMessageList.add(protoMessage);
                    //所有透传的消息都要发送给目标，只是存储的时候需要过滤,这里透传消息功能没有完成，还是需要存储
                    if(protoService.getImMemoryStore().canPersistent(protoMessage.getContent().getType())){
                        protoService.getImMemoryStore().addProtoMessageByTarget(protoMessage.getTarget(),protoMessage,true);
                    }
                }
            }

        }
        ProtoMessage[] protoMessagesArr = new ProtoMessage[resultProtoMessageList.size()];
        resultProtoMessageList.toArray(protoMessagesArr);
        JavaProtoLogic.onReceiveMessage(protoMessagesArr,false);
    }

    /**
     * 这里仅仅上报已送达回执
     * */
    private void uploadReport(ProtoMessage protoMessage){
        if(protoMessage.getConversationType() == ProtoConstants.ConversationType.ConversationType_Private
                && protoMessage.getDirection() == 1){
            protoService.uploadReport(ProtoConstants.MessageReportType.ReportType_Delivery, protoMessage.getTarget(), protoMessage.getConversationType(), null, new JavaProtoLogic.IGeneralCallback() {
                @Override
                public void onSuccess() {
                    logger.i("upload delivery report success");
                }

                @Override
                public void onFailure(int errorCode) {
                    logger.i("upload delivery report fail");
                }
            });
        }
    }

    /**
     * 统计当前群组聊天中有哪些用户发送了消息
     * */
    private void computeGroupSender(ProtoMessage protoMessage){
        if(protoMessage.getDirection() == 1 && protoMessage.getConversationType() == ProtoConstants.ConversationType.ConversationType_Group){
            protoService.getImMemoryStore().setGroupReportUsers(protoMessage.getTarget(),protoMessage.getFrom());
        }
    }
}
