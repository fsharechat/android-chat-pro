package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.google.protobuf.InvalidProtocolBufferException;

import cn.wildfirechat.proto.JavaProtoLogic;
import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.UserSettingApplier;
import cn.wildfirechat.proto.WFCMessage;
import cn.wildfirechat.proto.store.ImMemoryStore;

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

            final ImMemoryStore store = protoService.getImMemoryStore();
            UserSettingApplier.apply(new UserSettingApplier.SettingSink() {
                @Override
                public long getUserSettingVersion() {
                    return store.getUserSettingVersion();
                }

                @Override
                public void setUserSetting(int scope, String key, String value) {
                    store.setUserSetting(scope, key, value);
                }

                @Override
                public void setUserSettingVersion(long version) {
                    store.setUserSettingVersion(version);
                }
            }, result.getEntryList(), new UserSettingApplier.Notifier() {
                @Override
                public void onSettingUpdated() {
                    // 与 NotifyFriendHandler 落库后调 onFriendRequestUpdated() 对应：
                    // 不发这一下，其他设备改的置顶/免打扰/保存的群组只会静静躺在本地库里，
                    // 会话列表要等下次重进页面才更新。
                    JavaProtoLogic.onSettingUpdated();
                }
            });
        } catch (InvalidProtocolBufferException e) {
            log.e("UserSettingPullHandler parse error " + e.getMessage());
        }
    }
}
