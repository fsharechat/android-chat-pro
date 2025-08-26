package com.comsince.github.client;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.push.Header;

public interface PushMessageCallback {
    void receiveMessage(Header header, ByteBufferList bodyBuffer);
    void receiveException(Exception e);
    void onConnected();
}
