package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.google.protobuf.InvalidProtocolBufferException;

import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.WFCMessage;

/**
 * UG 的应答：服务端只返回 updateDt 大于请求 version 的条目。
 * value 为 "0" 是 tombstone，表示该设置已被其他设备取消；本地按普通值存下（不删除该条目），
 * 因为所有读取方都只把 "1" 当作「已设置」、其余一律视为未设置，保留该条目既等价于删除，
 * 又保留了「曾被取消」这件事和它的 updateDt，供下次比较用。
 */
public class UserSettingPullHandler extends AbstractMessageHandler {
    private Log log = LoggerFactory.getLogger(UserSettingPullHandler.class);

    public UserSettingPullHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUB_ACK == header.getSignal() && SubSignal.UG == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        int errorCode = byteBufferList.get();
        if (errorCode != 0) {
            log.e("UserSettingPullHandler error code " + errorCode);
            return;
        }
        try {
            WFCMessage.GetUserSettingResult result =
                    WFCMessage.GetUserSettingResult.parseFrom(byteBufferList.getAllByteArray());
            log.i("pull user setting count " + result.getEntryCount());

            long maxUpdateDt = protoService.getImMemoryStore().getUserSettingVersion();
            for (WFCMessage.UserSettingEntry entry : result.getEntryList()) {
                protoService.getImMemoryStore().setUserSetting(entry.getScope(), entry.getKey(), entry.getValue());
                if (entry.getUpdateDt() > maxUpdateDt) {
                    maxUpdateDt = entry.getUpdateDt();
                }
            }
            protoService.getImMemoryStore().setUserSettingVersion(maxUpdateDt);
        } catch (InvalidProtocolBufferException e) {
            log.e("UserSettingPullHandler parse error " + e.getMessage());
        }
    }
}
