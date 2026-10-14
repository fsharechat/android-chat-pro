package cn.wildfirechat.proto.handler;

import org.junit.Test;

import cn.wildfirechat.proto.WFCMessage;

import static org.junit.Assert.assertEquals;

public class SpeechToTextHandlerTest {

    @Test
    public void parseTextReadsSpeechToTextResult() throws Exception {
        byte[] body = WFCMessage.SpeechToTextResult.newBuilder().setText("你好").build().toByteArray();

        assertEquals("你好", SpeechToTextHandler.parseText(body));
    }

    @Test
    public void parseTextAllowsEmptyText() throws Exception {
        byte[] body = WFCMessage.SpeechToTextResult.newBuilder().setText("").build().toByteArray();

        assertEquals("", SpeechToTextHandler.parseText(body));
    }
}
