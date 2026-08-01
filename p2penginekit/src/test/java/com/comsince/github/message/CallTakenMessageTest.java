package com.comsince.github.message;

import org.junit.Test;

import cn.wildfirechat.message.core.MessagePayload;

import static org.junit.Assert.assertEquals;

public class CallTakenMessageTest {

    @Test
    public void encodeThenDecodeRoundTripsAllFields() {
        CallTakenMessage origin = new CallTakenMessage(
                "call-abc", CallTakenMessage.REASON_REJECTED, "device-1");

        MessagePayload payload = origin.encode();
        CallTakenMessage decoded = new CallTakenMessage();
        decoded.decode(payload);

        assertEquals("call-abc", decoded.getCallId());
        assertEquals(CallTakenMessage.REASON_REJECTED, decoded.getReason());
        assertEquals("device-1", decoded.getDeviceId());
    }

    @Test
    public void encodePutsCallIdInContentField() {
        MessagePayload payload = new CallTakenMessage(
                "call-abc", CallTakenMessage.REASON_ANSWERED, "device-1").encode();

        assertEquals("call-abc", payload.content);
    }

    @Test
    public void decodeMissingReasonFallsBackToAnswered() {
        MessagePayload payload = new MessagePayload();
        payload.content = "call-abc";
        payload.binaryContent = "{\"d\":\"device-1\"}".getBytes();

        CallTakenMessage decoded = new CallTakenMessage();
        decoded.decode(payload);

        assertEquals(CallTakenMessage.REASON_ANSWERED, decoded.getReason());
        assertEquals("device-1", decoded.getDeviceId());
    }

    @Test
    public void decodeUnknownReasonFallsBackToAnswered() {
        MessagePayload payload = new MessagePayload();
        payload.content = "call-abc";
        payload.binaryContent = "{\"r\":\"whatever\",\"d\":\"device-1\"}".getBytes();

        CallTakenMessage decoded = new CallTakenMessage();
        decoded.decode(payload);

        assertEquals(CallTakenMessage.REASON_ANSWERED, decoded.getReason());
    }

    @Test
    public void decodeBrokenJsonFallsBackToAnsweredAndEmptyDevice() {
        MessagePayload payload = new MessagePayload();
        payload.content = "call-abc";
        payload.binaryContent = "not json".getBytes();

        CallTakenMessage decoded = new CallTakenMessage();
        decoded.decode(payload);

        assertEquals("call-abc", decoded.getCallId());
        assertEquals(CallTakenMessage.REASON_ANSWERED, decoded.getReason());
        assertEquals("", decoded.getDeviceId());
    }

    @Test
    public void decodeNullBinaryContentFallsBackToAnswered() {
        MessagePayload payload = new MessagePayload();
        payload.content = "call-abc";
        payload.binaryContent = null;

        CallTakenMessage decoded = new CallTakenMessage();
        decoded.decode(payload);

        assertEquals(CallTakenMessage.REASON_ANSWERED, decoded.getReason());
        assertEquals("", decoded.getDeviceId());
    }
}
