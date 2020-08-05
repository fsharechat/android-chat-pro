package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.MessagePayload;
import cn.wildfirechat.message.core.PersistFlag;

@ContentTag(
        type = 401,
        flag = PersistFlag.No_Persist
)
public class AnswerMessage extends MessageContent {
    private String callId;
    private boolean audioOnly;
    public static final Creator<AnswerMessage> CREATOR = new Creator<AnswerMessage>() {
        public AnswerMessage createFromParcel(Parcel source) {
            return new AnswerMessage(source);
        }

        public AnswerMessage[] newArray(int size) {
            return new AnswerMessage[size];
        }
    };

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public boolean isAudioOnly() {
        return this.audioOnly;
    }

    public void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    public MessagePayload encode() {
        MessagePayload payload = new MessagePayload();
        payload.content = this.callId;
        payload.binaryContent = ("" + (this.audioOnly ? 1 : 0)).getBytes();
        return payload;
    }

    public void decode(MessagePayload payload) {
        this.callId = payload.content;

        try {
            this.audioOnly = Integer.parseInt(new String(payload.binaryContent)) > 0;
        } catch (Exception var3) {
            var3.printStackTrace();
            this.audioOnly = false;
        }

    }

    public AnswerMessage(String callId, boolean audioOnly) {
        this.callId = callId;
        this.audioOnly = audioOnly;
    }

    public AnswerMessage() {
    }

    public String getCallId() {
        return this.callId;
    }

    public String digest(Message message) {
        return "Answer Call";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.callId);
        dest.writeInt(this.audioOnly ? 1 : 0);
    }

    protected AnswerMessage(Parcel in) {
        this.callId = in.readString();
        this.audioOnly = in.readInt() > 0;
    }
}
