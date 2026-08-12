package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;

import cn.wildfirechat.proto.ProtoService;

/**
 * UN：服务端告知「你的设置变了，最新 version 是 X」，body 为 8 字节 version。
 * 本端据此发 UG 拉增量。发起修改的那台设备也会收到自己的 UN，多拉一次是幂等的。
 */
public class NotifyUserSettingHandler extends AbstractMessageHandler {
    private Log log = LoggerFactory.getLogger(NotifyUserSettingHandler.class);

    public NotifyUserSettingHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUBLISH == header.getSignal() && SubSignal.UN == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        long version = byteBufferList.getLong();
        long localVersion = protoService.getImMemoryStore().getUserSettingVersion();
        log.i("receive user setting notify version " + version + " local " + localVersion);
        if (version <= localVersion) {
            return;
        }
        workExecutor.execute(new Runnable() {
            @Override
            public void run() {
                protoService.pullUserSettings(localVersion);
            }
        });
    }
}
