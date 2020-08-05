package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.PersistFlag;

@ContentTag(
        type = 405,
        flag = PersistFlag.Transparent
)
public class AnswerTMessage extends AnswerMessage {
    public static final Creator<AnswerTMessage> CREATOR = new Creator<AnswerTMessage>() {
        public AnswerTMessage createFromParcel(Parcel source) {
            return new AnswerTMessage(source);
        }

        public AnswerTMessage[] newArray(int size) {
            return new AnswerTMessage[size];
        }
    };

    public AnswerTMessage(String callId, boolean audioOnly) {
        super(callId, audioOnly);
    }

    public AnswerTMessage() {
    }

    public String digest(Message message) {
        return "Answer Call T";
    }

    protected AnswerTMessage(Parcel in) {
        super(in);
    }
}
