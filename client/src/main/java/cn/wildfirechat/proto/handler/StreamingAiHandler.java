package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;

import org.json.JSONObject;

import cn.wildfirechat.proto.ProtoService;

/**
 * 处理服务端推送的流式 AI 增量事件（PUBLISH/SAI）。
 * SAI payload: {"i":"streamId","d":"delta","f":true/false,"t":"agentUserId"}
 * 解析后回调 ProtoService.onSaiToken()，由上层 UI 消费。
 */
public class StreamingAiHandler extends AbstractMessageHandler {

    public StreamingAiHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUBLISH == header.getSignal() && SubSignal.SAI == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        try {
            String json = new String(byteBufferList.getAllByteArray(), "UTF-8");
            JSONObject obj = new JSONObject(json);
            String streamId = obj.getString("i");
            String delta    = obj.getString("d");
            boolean done    = obj.getBoolean("f");
            String target   = obj.getString("t");
            protoService.onSaiToken(streamId, delta, done, target);
        } catch (Exception e) {
            ProtoService.log.e("SAI parse error: " + e.getMessage());
        }
    }
}
