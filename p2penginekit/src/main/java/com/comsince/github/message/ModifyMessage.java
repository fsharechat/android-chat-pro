package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.MessagePayload;
import cn.wildfirechat.message.core.PersistFlag;

@ContentTag(
        type = 404,
        flag = PersistFlag.No_Persist
)
public class ModifyMessage extends MessageContent {
    private String callId;
    private boolean audioOnly;
    public static final Creator<ModifyMessage> CREATOR = new Creator<ModifyMessage>() {
        public ModifyMessage createFromParcel(Parcel source) {
            return new ModifyMessage(source);
        }

        public ModifyMessage[] newArray(int size) {
            return new ModifyMessage[size];
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

    public ModifyMessage(String callId, boolean audioOnly) {
        this.callId = callId;
        this.audioOnly = audioOnly;
    }

    public ModifyMessage() {
    }

    public String getCallId() {
        return this.callId;
    }

    public String digest(Message message) {
        return "Modify Call";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.callId);
        dest.writeInt(this.audioOnly ? 1 : 0);
    }

    protected ModifyMessage(Parcel in) {
        this.callId = in.readString();
        this.audioOnly = in.readInt() > 0;
    }
}
