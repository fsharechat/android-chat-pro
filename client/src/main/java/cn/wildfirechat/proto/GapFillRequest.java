package cn.wildfirechat.proto;

import java.util.List;

import cn.wildfirechat.model.ProtoMessage;

/**
 * 断层补拉请求的回调。RemoteMessageHandler 识别到它时把整页原始结果交给 onPage，
 * 由 ConversationGapFiller 落库并收尾断层——判断"服务端是否到底"要看过滤通话信令之前的条数。
 */
public class GapFillRequest implements JavaProtoLogic.ILoadRemoteMessagesCallback {
    private final ConversationGapFiller.GapSink sink;
    private final String target;
    private final long gapUid;
    private final int count;
    private final JavaProtoLogic.ILoadRemoteMessagesCallback downstream;

    public GapFillRequest(ConversationGapFiller.GapSink sink, String target, long gapUid, int count,
                          JavaProtoLogic.ILoadRemoteMessagesCallback downstream) {
        this.sink = sink;
        this.target = target;
        this.gapUid = gapUid;
        this.count = count;
        this.downstream = downstream;
    }

    public void onPage(WFCMessage.PullMessageResult page) {
        List<ProtoMessage> shown = ConversationGapFiller.applyPage(sink, target, gapUid, count, page);
        downstream.onSuccess(shown.toArray(new ProtoMessage[0]));
    }

    @Override
    public void onSuccess(ProtoMessage[] messages) {
        downstream.onSuccess(messages);
    }

    @Override
    public void onFailure(int errorCode) {
        downstream.onFailure(errorCode);
    }
}
