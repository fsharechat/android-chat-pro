package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.MessagePayload;
import cn.wildfirechat.message.core.PersistFlag;

/**
 * 406 多端同步信令：告知自己账号的其他设备「这通电话我处理了」。
 * 只投递给发送者自己的其他设备，对端永远收不到（服务端 SendMessageHandler 按 contentType 路由）。
 */
@ContentTag(
        type = 406,
        flag = PersistFlag.Transparent
)
public class CallTakenMessage extends MessageContent {
    public static final String REASON_ANSWERED = "answered";
    public static final String REASON_REJECTED = "rejected";

    private String callId;
    private String reason;
    private String deviceId;

    public static final Creator<CallTakenMessage> CREATOR = new Creator<CallTakenMessage>() {
        public CallTakenMessage createFromParcel(Parcel source) {
            return new CallTakenMessage(source);
        }

        public CallTakenMessage[] newArray(int size) {
            return new CallTakenMessage[size];
        }
    };

    public CallTakenMessage() {
    }

    public CallTakenMessage(String callId, String reason, String deviceId) {
        this.callId = callId;
        this.reason = reason;
        this.deviceId = deviceId;
    }

    public String getCallId() {
        return this.callId;
    }

    public String getReason() {
        return this.reason;
    }

    public String getDeviceId() {
        return this.deviceId;
    }

    public MessagePayload encode() {
        MessagePayload payload = new MessagePayload();
        payload.content = this.callId;
        JSONObject json = new JSONObject();
        try {
            json.put("r", this.reason);
            json.put("d", this.deviceId == null ? "" : this.deviceId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        payload.binaryContent = json.toString().getBytes(StandardCharsets.UTF_8);
        return payload;
    }

    public void decode(MessagePayload payload) {
        this.callId = payload.content;
        // r 缺失/无法解析一律回退 answered：406 只在接听与拒接时发送，两者都要求
        // 本端关闭来电界面，默认 answered 能保证核心行为不因 payload 异常失效。
        this.reason = REASON_ANSWERED;
        this.deviceId = "";
        if (payload.binaryContent == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject(new String(payload.binaryContent, StandardCharsets.UTF_8));
            String parsedReason = json.optString("r", REASON_ANSWERED);
            if (REASON_REJECTED.equals(parsedReason)) {
                this.reason = REASON_REJECTED;
            }
            this.deviceId = json.optString("d", "");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String digest(Message message) {
        return "Call Taken";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.callId);
        dest.writeString(this.reason);
        dest.writeString(this.deviceId);
    }

    protected CallTakenMessage(Parcel in) {
        this.callId = in.readString();
        this.reason = in.readString();
        this.deviceId = in.readString();
    }
}
