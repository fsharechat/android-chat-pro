package cn.wildfirechat.proto.handler;

import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;

import cn.wildfirechat.proto.ProtoService;

public class SetFriendAliasRequestHandler extends QuitGroupHandler{

    public SetFriendAliasRequestHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUB_ACK == header.getSignal() && SubSignal.FALS == header.getSubSignal();
    }
}
