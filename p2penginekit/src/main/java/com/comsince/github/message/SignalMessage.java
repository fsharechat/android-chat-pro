package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.MessagePayload;
import cn.wildfirechat.message.core.PersistFlag;

@ContentTag(
        type = 403,
        flag = PersistFlag.Transparent
)
public class SignalMessage extends MessageContent {
    private String callId;
    private byte[] payload;
    public static final Creator<SignalMessage> CREATOR = new Creator<SignalMessage>() {
        public SignalMessage createFromParcel(Parcel source) {
            return new SignalMessage(source);
        }

        public SignalMessage[] newArray(int size) {
            return new SignalMessage[size];
        }
    };

    public MessagePayload encode() {
        MessagePayload p = new MessagePayload();
        p.content = this.callId;
        p.binaryContent = this.payload;
        ContentTag tag = (ContentTag)this.getClass().getAnnotation(ContentTag.class);
        p.contentType = tag.type();
        return p;
    }

    public void decode(MessagePayload p) {
        this.callId = p.content;
        this.payload = p.binaryContent;
    }

    public SignalMessage(String callId) {
        this.callId = callId;
    }

    public SignalMessage() {
    }

    public byte[] getPayload() {
        return this.payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getCallId() {
        return this.callId;
    }

    public String digest(Message message) {
        return "Signal";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.callId);
        dest.writeByteArray(this.payload);
    }

    protected SignalMessage(Parcel in) {
        this.callId = in.readString();
        this.payload = in.createByteArray();
    }
}