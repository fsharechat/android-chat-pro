package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.google.protobuf.InvalidProtocolBufferException;

import cn.wildfirechat.proto.JavaProtoLogic;
import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.WFCMessage;

/**
 * 语音转文字应答：1 字节错误码 + （成功时）SpeechToTextResult
 */
public class SpeechToTextHandler extends AbstractMessageHandler {

    public SpeechToTextHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUB_ACK == header.getSignal() && SubSignal.ASR == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        int errorCode = byteBufferList.get();
        JavaProtoLogic.IGeneralCallback2 callback = (JavaProtoLogic.IGeneralCallback2) getCallback(header.getMessageId());
        if (callback == null) {
            return;
        }
        if (errorCode != 0) {
            callback.onFailure(errorCode);
            return;
        }
        try {
            callback.onSuccess(parseText(byteBufferList.getAllByteArray()));
        } catch (InvalidProtocolBufferException e) {
            logger.e("parse SpeechToTextResult failed", e);
            callback.onFailure(-1);
        }
    }

    static String parseText(byte[] body) throws InvalidProtocolBufferException {
        return WFCMessage.SpeechToTextResult.parseFrom(body).getText();
    }
}
