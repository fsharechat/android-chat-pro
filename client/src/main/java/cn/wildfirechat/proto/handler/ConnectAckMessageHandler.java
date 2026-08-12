package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.google.protobuf.InvalidProtocolBufferException;

import cn.wildfirechat.proto.JavaProtoLogic;
import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.WFCMessage;

public class ConnectAckMessageHandler extends AbstractMessageHandler {
    private static final String TAG = "ConnectAckMessage";

    public ConnectAckMessageHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return header.getSignal() == Signal.CONNECT_ACK;
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        try {
            WFCMessage.ConnectAckPayload connectAckPayload = WFCMessage.ConnectAckPayload.parseFrom(byteBufferList.getAllByteArray());
            long lastMessageSeq = protoService.getImMemoryStore().getLastMessageSeq();
            long settingHead = connectAckPayload.getSettingHead();
            long localSettingVersion = protoService.getImMemoryStore().getUserSettingVersion();
            logger.i(TAG,"friendHead "+connectAckPayload.getFriendHead()+" msgHead "+connectAckPayload.getMsgHead()+" settingHead "+settingHead+" localSettingVersion "+localSettingVersion+" lastMessageSeq "+lastMessageSeq+" serverTime "+connectAckPayload.getServerTime());
            workExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    protoService.getMyFriendList(true);

                    // 用户设置存在纯内存 store，进程重启后 localSettingVersion 归零，
                    // 这里就是一次全量拉取；服务端没有任何设置时 settingHead 为 0，跳过
                    if (settingHead > localSettingVersion) {
                        protoService.pullUserSettings(localSettingVersion);
                    }

                    //如果lastMessageSeq为0表示初始用户下拉消息，其他为连接获取未读消息
                    if(lastMessageSeq != 0){
                        protoService.pullMessage(lastMessageSeq,0);
                    } else {
                        protoService.pullMessage(lastMessageSeq, 0, new JavaProtoLogic.IGeneralCallback() {
                            @Override
                            public void onSuccess() {
                                logger.i("connect pull message success");
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                logger.i("connect pull message fail "+errorCode);
                            }
                        });
                    }

                    //校准消息序列号
//                    protoService.getImMemoryStore().updateMessageSeq(connectAckPayload.getMsgHead());

                    //重新发送失败的消息
//                    protoService.resendMessage();
                }
            });
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }
}
