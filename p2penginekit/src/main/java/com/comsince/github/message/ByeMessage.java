package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.MessagePayload;
import cn.wildfirechat.message.core.PersistFlag;

@ContentTag(
        type = 402,
        flag = PersistFlag.No_Persist
)
public class ByeMessage extends MessageContent {
    private String callId;
    public static final Creator<ByeMessage> CREATOR = new Creator<ByeMessage>() {
        public ByeMessage createFromParcel(Parcel source) {
            return new ByeMessage(source);
        }

        public ByeMessage[] newArray(int size) {
            return new ByeMessage[size];
        }
    };

    public MessagePayload encode() {
        MessagePayload payload = new MessagePayload();
        payload.content = this.callId;
        return payload;
    }

    public void decode(MessagePayload payload) {
        this.callId = payload.content;
    }

    public ByeMessage(String callId) {
        this.callId = callId;
    }

    public ByeMessage() {
    }

    public String getCallId() {
        return this.callId;
    }

    public String digest(Message message) {
        return "Bye";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.callId);
    }

    protected ByeMessage(Parcel in) {
        this.callId = in.readString();
    }
}
