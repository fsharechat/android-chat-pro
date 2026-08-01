# 多端通话状态同步 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 406 信令与服务端定向投递，使同账号任一端接听/拒接 1v1 通话后，其他端自动关闭接听界面，且不影响正在通话的那一端。

**Architecture:** 客户端接听/拒接时，在现有 401/402/405 之外补发一条 contentType 406 的 `Transparent` 消息（conversation target 仍填对端）；服务端 `SendMessageHandler` 按 contentType 拦截 406，不落库、不投递给对端，只用 MP 直推给发送者自己的其他 clientId；三端收到 `direction == Send` 的 406 后静默结束本端会话，不回发 Bye。400/401/402/403/404/405 的现有行为完全不动。

**Tech Stack:** 服务端 Java 8 + Maven + tio + protobuf；Android Java 8 + Gradle + JUnit 4；iOS Swift + SwiftPM + XCTest；Electron TypeScript + Vue 3 + Vitest。

**设计文档:** `docs/superpowers/specs/2026-08-01-multi-device-call-sync-design.md`（android-chat-pro 仓库）

## Global Constraints

- **不要自动 commit。** 每个任务末尾只做「跑测试 + `git add`」，commit 由仓库所有者自己执行。计划中所有 `git add` 步骤都不得跟 `git commit`。
- **四个仓库路径**（都在 `/Users/liaojinlong/Share/GitWorkplace/github/fshare/` 下）：`chat-server-pro`、`android-chat-pro`、`ios-chat-pro`、`electron-vue-chat-pro`。它们是独立 git 工作区，跨仓库改动不能放在同一次 `git add`。
- **服务端必须用 JDK 8 构建**（Proguard 依赖 `rt.jar`，JDK 9+ 会在 package 阶段失败）。
- **Android 必须用 JDK 11 构建**：所有 gradlew 命令加前缀 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home`。
- **adb 完整路径**：`/Users/liaojinlong/Library/Android/sdk/platform-tools/adb`（不在 PATH 里）。真机 MEIZU 20 序列号 `381QYEC3222BP`，包名 `cn.comsince.fschat`。该机调试期间会间歇掉线，install 卡住先 `adb devices` 确认。
- **新信令常量值固定为 406**，名称固定为 `VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC`（服务端）/ `CallTakenMessage`（Android）/ `callTaken`（iOS）/ `CallTakenMessageContent`（Electron）。
- **406 payload 线上格式固定**：`content` = callId，`binaryContent` = JSON `{"r":"answered"|"rejected","d":"<发送端 clientId>"}`。Android 写原始字节；iOS 写 `Data`；**Electron 必须走 `StringUtils.utf8_to_b64()` 做 base64**（与该仓库其他 content 类一致，WebSocket JSON 传输要求）。
- **`r` 缺失或无法解析时一律回退为 `answered`。** 理由：406 只在接听/拒接两种场景发送，两者都要求本端关闭界面；默认 `answered` 保证核心行为（关界面）不因 payload 异常失效，代价只是通话记录文案可能偏差。
- **`d` 字段一期只写不读**，为二期设备锁定预留。

---

## 阶段一：chat-server-pro（服务端）

### Task 1: 406 常量与会话过滤纯函数

**Files:**
- Modify: `chat-server-pro/push-stub/src/main/java/com/comsince/github/proto/ProtoConstants.java:152`
- Modify: `chat-server-pro/spring-boot-dubbo-push-connector/src/main/java/com/comsince/github/handler/im/MessagesPublisher.java`
- Test: `chat-server-pro/spring-boot-dubbo-push-connector/src/test/java/com/comsince/github/MessagesPublisherSelfClientsTest.java`

**Interfaces:**
- Produces: `ProtoConstants.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC`（int 常量 406）；`MessagesPublisher.selectSelfOtherClientIds(Collection<SessionResponse>, String) -> List<String>`（public static，Task 2 使用）

- [ ] **Step 1: 写失败的测试**

创建 `chat-server-pro/spring-boot-dubbo-push-connector/src/test/java/com/comsince/github/MessagesPublisherSelfClientsTest.java`：

```java
package com.comsince.github;

import com.comsince.github.handler.im.MessagesPublisher;
import com.comsince.github.model.SessionResponse;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessagesPublisherSelfClientsTest {

    private SessionResponse session(String clientId) {
        SessionResponse response = new SessionResponse();
        response.clientID = clientId;
        return response;
    }

    @Test
    public void excludesOriginatingClient() {
        List<SessionResponse> sessions = Arrays.asList(
                session("android-1"), session("ios-2"), session("electron-3"));

        List<String> targets = MessagesPublisher.selectSelfOtherClientIds(sessions, "ios-2");

        assertEquals(Arrays.asList("android-1", "electron-3"), targets);
    }

    @Test
    public void keepsAllSessionsWhenExceptClientIdIsNotPresent() {
        List<SessionResponse> sessions = Arrays.asList(session("android-1"), session("ios-2"));

        List<String> targets = MessagesPublisher.selectSelfOtherClientIds(sessions, "gone-4");

        assertEquals(Arrays.asList("android-1", "ios-2"), targets);
    }

    @Test
    public void skipsNullAndEmptyClientIds() {
        List<SessionResponse> sessions = Arrays.asList(
                session(null), session(""), session("android-1"));

        List<String> targets = MessagesPublisher.selectSelfOtherClientIds(sessions, "ios-2");

        assertEquals(Arrays.asList("android-1"), targets);
    }

    @Test
    public void returnsEmptyListForNullSessions() {
        assertTrue(MessagesPublisher.selectSelfOtherClientIds(null, "ios-2").isEmpty());
    }

    @Test
    public void returnsEmptyListWhenOnlyOriginatingClientOnline() {
        List<SessionResponse> sessions = new ArrayList<>();
        sessions.add(session("ios-2"));

        assertTrue(MessagesPublisher.selectSelfOtherClientIds(sessions, "ios-2").isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/chat-server-pro
mvn test -pl spring-boot-dubbo-push-connector -Dtest=MessagesPublisherSelfClientsTest
```

Expected: 编译失败，`cannot find symbol: method selectSelfOtherClientIds`

- [ ] **Step 3: 加常量**

在 `push-stub/src/main/java/com/comsince/github/proto/ProtoConstants.java` 的 `VOIP_CONTENT_TYPE_ACCEPT_T = 405;` 那行之后加：

```java
    /** 多端同步信令：接听/拒接后只投递给发送者自己的其他设备，不投给对端。 */
    public static final int VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC = 406;
```

- [ ] **Step 4: 实现纯函数**

在 `MessagesPublisher.java` 的类体内（放在 `publishMessageToClientId` 方法之后）加：

```java
    /**
     * 从某用户的全部在线会话里挑出「除发起设备外」的目标 clientId。
     * 独立成 static 纯函数是为了能脱离 Hazelcast/tio 基础设施做单元测试。
     */
    public static List<String> selectSelfOtherClientIds(Collection<SessionResponse> sessions, String exceptClientId) {
        List<String> targets = new ArrayList<>();
        if (sessions == null) {
            return targets;
        }
        for (SessionResponse session : sessions) {
            String clientId = session.getClientID();
            if (clientId == null || clientId.isEmpty()) {
                continue;
            }
            if (exceptClientId != null && exceptClientId.equals(clientId)) {
                continue;
            }
            targets.add(clientId);
        }
        return targets;
    }
```

同时在文件头部 import 区补上（`java.util.List` 已有，`Collection` 已有）：

```java
import java.util.ArrayList;
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/chat-server-pro
mvn test -pl spring-boot-dubbo-push-connector -Dtest=MessagesPublisherSelfClientsTest
```

Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 6: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/chat-server-pro
git add push-stub/src/main/java/com/comsince/github/proto/ProtoConstants.java \
        spring-boot-dubbo-push-connector/src/main/java/com/comsince/github/handler/im/MessagesPublisher.java \
        spring-boot-dubbo-push-connector/src/test/java/com/comsince/github/MessagesPublisherSelfClientsTest.java
```

---

### Task 2: 定向推送方法与 406 路由拦截

**Files:**
- Modify: `chat-server-pro/spring-boot-dubbo-push-connector/src/main/java/com/comsince/github/handler/im/MessagesPublisher.java:74-97`
- Modify: `chat-server-pro/spring-boot-dubbo-push-connector/src/main/java/com/comsince/github/handler/im/SendMessageHandler.java:52-58`

**Interfaces:**
- Consumes: `MessagesPublisher.selectSelfOtherClientIds`（Task 1）、`ProtoConstants.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC`（Task 1）
- Produces: `MessagesPublisher.publishMessage2SelfOtherClients(String userId, String exceptClientId, FSCMessage.Message message)`（void）

本任务没有可脱离基础设施的测试接缝（`PushUtil.pushMessageByBsId` 是 tio 静态推送、`sessionService` 是 Dubbo 远程代理，仓库无 mockito 依赖）。验收靠编译 + Task 7 的真机端到端验证。**不要为此引入 mock 框架。**

- [ ] **Step 1: 抽出组包逻辑**

`MessagesPublisher.java` 中现有 `publishMessageToClientId`（`:74-97`）内联了一段「`PullMessageResult` → 首字节 0 → `PublishAckMessagePacket(MP)`」的组包。把它抽成私有方法，放在 `publishMessageToClientId` 之前：

```java
    /**
     * 把一条 message 组装成客户端 ReceiveMessageHandler 认得的 MP 直推包。
     * 首字节 0 是错误码，与 PullMessageResult 的 ack 格式一致。
     */
    private PublishAckMessagePacket buildMessagePushPacket(FSCMessage.Message message) {
        FSCMessage.PullMessageResult pullMessageResult = FSCMessage.PullMessageResult.newBuilder()
                .addMessage(message)
                .setCurrent(0)
                .setHead(0)
                .build();
        byte[] pullMessageByteArr = pullMessageResult.toByteArray();
        ByteBuf byteBuf = Unpooled.buffer();
        byteBuf.ensureWritable(1).writeByte(0);
        byteBuf.ensureWritable(pullMessageByteArr.length).writeBytes(pullMessageByteArr);
        byte[] bodyMessage = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(bodyMessage);
        PublishAckMessagePacket publishAckMessagePacket = new PublishAckMessagePacket();
        publishAckMessagePacket.setSubSignal(SubSignal.MP);
        publishAckMessagePacket.setBody(bodyMessage);
        return publishAckMessagePacket;
    }
```

然后把 `publishMessageToClientId` 的方法体替换为：

```java
    public void publishMessageToClientId(String userId, String clientId, FSCMessage.Message message){
        if(message != null){
            Collection<SessionResponse> sessions = sessionService.sessionForUser(userId);
            for (SessionResponse targetSession : sessions) {
                if (targetSession.getClientID().equals(clientId)) {
                    LOG.info("send transparent message contentType {} to receiver {} clientId {}",message.getContent().getType(),userId,targetSession.clientID);
                    PushUtil.pushMessageByBsId(targetSession.clientID, buildMessagePushPacket(message));
                }
            }
        }
    }
```

- [ ] **Step 2: 新增定向推送方法**

紧接在 `publishMessageToClientId` 之后加：

```java
    /**
     * 把一条消息只投递给 userId 自己的其他设备（排除发起设备），不投给对端。
     * 用于 406 多端同步信令。
     */
    public void publishMessage2SelfOtherClients(String userId, String exceptClientId, FSCMessage.Message message) {
        if (message == null) {
            return;
        }
        Collection<SessionResponse> sessions = sessionService.sessionForUser(userId);
        List<String> targets = selectSelfOtherClientIds(sessions, exceptClientId);
        LOG.info("multi client sync contentType {} user {} exceptClientId {} targets {}",
                message.getContent().getType(), userId, exceptClientId, targets);
        for (String clientId : targets) {
            PushUtil.pushMessageByBsId(clientId, buildMessagePushPacket(message));
        }
    }
```

- [ ] **Step 3: 在 SendMessageHandler 里拦截 406**

`SendMessageHandler.java` 的 `action()` 方法中，找到这两行：

```java
            message = message.toBuilder().setFromUser(fromUser).setMessageId(messageId).setServerTimestamp(timestamp).build();


            boolean ignoreMsg = false;
```

在它们之间插入：

```java
            // 406 多端同步信令：不落库、不投递给对端，只推给发送者自己的其他设备。
            if (message.getContent().getType() == ProtoConstants.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC) {
                publisher.publishMessage2SelfOtherClients(fromUser, clientID, message);
                ackPayload = ackPayload.capacity(20);
                ackPayload.writeLong(messageId);
                ackPayload.writeLong(timestamp);
                return errorCode;
            }
```

放在好友/黑名单校验**之后**是刻意的：406 的 conversation target 填的是对端，本来就是好友，校验能通过；填自己会因 `MessageServiceImpl.isFriend(self, self)` 返回 false 被 `ERROR_CODE_NOT_FRIEND` 拒掉。

- [ ] **Step 4: 编译 + 跑全量单测**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/chat-server-pro
mvn clean package -Dmaven.test.skip=true -pl spring-boot-dubbo-push-connector
mvn test -pl spring-boot-dubbo-push-connector
```

Expected: BUILD SUCCESS，已有测试全部通过（注意必须用 JDK 8）

- [ ] **Step 5: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/chat-server-pro
git add spring-boot-dubbo-push-connector/src/main/java/com/comsince/github/handler/im/MessagesPublisher.java \
        spring-boot-dubbo-push-connector/src/main/java/com/comsince/github/handler/im/SendMessageHandler.java
```

- [ ] **Step 6: 部署到生产**

服务端改动必须先上线，后续三端才能验证。部署后确认 connector 启动日志无异常。

---

## 阶段二：android-chat-pro

### Task 3: CallTakenMessage 消息体与 payload 编解码

**Files:**
- Create: `android-chat-pro/p2penginekit/src/main/java/com/comsince/github/message/CallTakenMessage.java`
- Modify: `android-chat-pro/p2penginekit/build.gradle:24-31`
- Test: `android-chat-pro/p2penginekit/src/test/java/com/comsince/github/message/CallTakenMessageTest.java`

**Interfaces:**
- Produces: `CallTakenMessage(String callId, String reason, String deviceId)` 构造器、`getCallId()`、`getReason()`、`getDeviceId()`、常量 `CallTakenMessage.REASON_ANSWERED = "answered"`、`CallTakenMessage.REASON_REJECTED = "rejected"`（Task 4/5/6 使用）

- [ ] **Step 1: 给 p2penginekit 加测试依赖**

`p2penginekit/build.gradle` 的 `dependencies` 块末尾加两行。`org.json:json` 是必需的：Android 单元测试默认链接 android.jar 的 `org.json` 桩实现，所有方法会抛 `RuntimeException("Stub!")`。

```gradle
    testImplementation 'junit:junit:4.12'
    testImplementation 'org.json:json:20180813'
```

- [ ] **Step 2: 写失败的测试**

创建 `p2penginekit/src/test/java/com/comsince/github/message/CallTakenMessageTest.java`：

```java
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
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :p2penginekit:testDebugUnitTest --tests "com.comsince.github.message.CallTakenMessageTest"
```

Expected: 编译失败，`cannot find symbol: class CallTakenMessage`

- [ ] **Step 4: 实现消息体**

创建 `p2penginekit/src/main/java/com/comsince/github/message/CallTakenMessage.java`：

```java
package com.comsince.github.message;

import android.os.Parcel;
import android.os.Parcelable.Creator;

import org.json.JSONObject;

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
        payload.binaryContent = json.toString().getBytes();
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
            JSONObject json = new JSONObject(new String(payload.binaryContent));
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
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :p2penginekit:testDebugUnitTest --tests "com.comsince.github.message.CallTakenMessageTest"
```

Expected: 6 tests, 0 failures

- [ ] **Step 6: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
git add p2penginekit/build.gradle \
        p2penginekit/src/main/java/com/comsince/github/message/CallTakenMessage.java \
        p2penginekit/src/test/java/com/comsince/github/message/CallTakenMessageTest.java
```

---

### Task 4: Android 接收侧 —— 注册、分发与结束原因

**Files:**
- Modify: `android-chat-pro/p2penginekit/src/main/java/com/comsince/github/p2penginekit/AVEngineKit.java`（`:246-250` 注册、`:371` 白名单、`:374` 时间窗门、`:404-414` 分发链、`:1014-1022` endCall、`:1111-1120` CallEndReason）

**Interfaces:**
- Consumes: `CallTakenMessage`（Task 3）
- Produces: `AVEngineKit.CallEndReason.RejectByOtherClient`（Task 6 的通话记录逻辑与 UI 使用）

本任务改的是与 WebRTC 强耦合的分发链，无法脱离媒体栈做单测（spec 已说明）。验收靠编译 + Task 7 真机验证。

- [ ] **Step 1: 加结束原因枚举**

`AVEngineKit.java:1111-1120` 的枚举末项 `AcceptByOtherClient;` 改为：

```java
        AcceptByOtherClient,
        /** 自己账号的其他设备拒接了这通来电 —— 本端静默收场，不发 Bye。 */
        RejectByOtherClient;
```

- [ ] **Step 2: 注册消息类型**

`AVEngineKit.init()` 中 `ChatManager.Instance().registerMessageContent(ModifyMessage.class);` 那行之后加：

```java
                ChatManager.Instance().registerMessageContent(CallTakenMessage.class);
```

同时在文件 import 区加：

```java
import com.comsince.github.message.CallTakenMessage;
```

- [ ] **Step 3: 放行 406 进入分发**

`AVEngineKit.java:371` 的类型白名单，在 `&& !(message.content instanceof ModifyMessage)` 之后追加：

```java
 && !(message.content instanceof CallTakenMessage)
```

`AVEngineKit.java:374` 的时间窗与 direction 门，把 `(message.direction == MessageDirection.Receive || message.content instanceof AnswerMessage)` 改为：

```java
(message.direction == MessageDirection.Receive
        || message.content instanceof AnswerMessage
        || message.content instanceof CallTakenMessage)
```

- [ ] **Step 4: 加分发分支**

在 `CallStartMessageContent` 分支（`:404-414`，以 `this.engineCallback.onReceiveCall(newSession);` 与其后的 `}` 结尾）之后、`} else {` 之前，插入一个新的 `else if` 分支：

```java
                    } else if (message.content instanceof CallTakenMessage) {
                        // 只处理自己其他设备发来的（direction == Send），且必须还在响铃。
                        CallTakenMessage taken = (CallTakenMessage) message.content;
                        if (message.direction == MessageDirection.Send
                                && this.currentSession != null
                                && this.currentSession.state == AVEngineKit.CallState.Incoming
                                && taken.getCallId() != null
                                && taken.getCallId().equals(this.currentSession.callId)) {
                            Log.d("CallRTCClient", "call taken elsewhere reason=" + taken.getReason()
                                    + " device=" + taken.getDeviceId());
                            this.currentSession.endCall(
                                    CallTakenMessage.REASON_REJECTED.equals(taken.getReason())
                                            ? AVEngineKit.CallEndReason.RejectByOtherClient
                                            : AVEngineKit.CallEndReason.AcceptByOtherClient);
                        }
```

- [ ] **Step 5: 让新原因也豁免发 Bye**

`AVEngineKit.java:1018` 的判断从：

```java
                    if (reason != AVEngineKit.CallEndReason.AcceptByOtherClient) {
```

改为：

```java
                    if (reason != AVEngineKit.CallEndReason.AcceptByOtherClient
                            && reason != AVEngineKit.CallEndReason.RejectByOtherClient) {
```

这是整个方案里最关键的一行：其他端结束时若回发 Bye，会把已接通那一端的通话直接挂断。

- [ ] **Step 6: 编译**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :p2penginekit:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
git add p2penginekit/src/main/java/com/comsince/github/p2penginekit/AVEngineKit.java
```

---

### Task 5: Android 发送侧 —— 接听与拒接补发 406

**Files:**
- Modify: `android-chat-pro/p2penginekit/src/main/java/com/comsince/github/p2penginekit/AVEngineKit.java`（`:993-1007` answerCall、`:1014-1022` endCall、新增私有方法）

**Interfaces:**
- Consumes: `CallTakenMessage`（Task 3）、`ChatManager.getClientId()`（`client/src/main/java/cn/wildfirechat/remote/ChatManager.java:655`，`throws Exception`）
- Produces: `AVEngineKit.currentDeviceId()`（private，本任务内部使用）

- [ ] **Step 1: 加 deviceId 取值方法**

在 `AVEngineKit` 类体内、`onOfferMessage` 方法（`:296`）之前加：

```java
    /**
     * 本端 IM clientId，作为 406 的 d 字段。取不到时返回空串 —— 一期不消费该字段，
     * 不能因为取不到就阻断多端同步。
     */
    private String currentDeviceId() {
        try {
            return ChatManager.Instance().getClientId();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
```

- [ ] **Step 2: 接听时补发 406**

`answerCall`（`:993`）中，`AVEngineKit.this.onOfferMessage(answerx, this.clientId, true);` 之后、`AVEngineKit.this.startMedia(true);` 之前插入：

```java
                    CallTakenMessage taken = new CallTakenMessage(
                            this.callId, CallTakenMessage.REASON_ANSWERED, AVEngineKit.this.currentDeviceId());
                    AVEngineKit.this.onOfferMessage(taken, this.clientId, false);
```

`isKeyMsg` 传 `false`：406 发送失败不应该让已经接听成功的通话报 SignalError 中断，失败时走 `onOfferMessage` 的重试分支即可。

- [ ] **Step 3: 拒接时补发 406**

`endCall(reason)`（`:1014`）中，`this.endTime = System.currentTimeMillis();` 之后、原有的 Bye 发送判断之前插入：

```java
                    // 只有「本端用户主动拒接」才广播 rejected：reason == Hangup 表示
                    // 用户主动触发，state == Incoming 把它与通话中挂断区分开。
                    // 通话中挂断时其他端并不在响铃，广播 406 只是无意义噪音。
                    if (reason == AVEngineKit.CallEndReason.Hangup
                            && this.state == AVEngineKit.CallState.Incoming) {
                        CallTakenMessage taken = new CallTakenMessage(
                                this.callId, CallTakenMessage.REASON_REJECTED, AVEngineKit.this.currentDeviceId());
                        AVEngineKit.this.onOfferMessage(taken, this.clientId, false);
                    }
```

必须插在 `this.clientId = null; this.callId = null;` 之前 —— 那两行之后 callId 和目标都没了。

- [ ] **Step 4: 编译**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :p2penginekit:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
git add p2penginekit/src/main/java/com/comsince/github/p2penginekit/AVEngineKit.java
```

---

### Task 6: Android 通话记录 —— 新增 status 3「已在其他设备接听」

**Files:**
- Modify: `android-chat-pro/p2penginekit/src/main/java/com/comsince/github/p2penginekit/AVEngineKit.java`（`:750` 附近字段区、`:867-881` 记录更新、`:1014-1022` endCall）
- Modify: `android-chat-pro/chat/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/VoipMessageViewHolder.java:35-54`

**Interfaces:**
- Consumes: `AVEngineKit.CallEndReason.AcceptByOtherClient`（既有）、`RejectByOtherClient`（Task 4）

不改这一段的话，其他端每被同步关闭一次，聊天记录里就多一条假的「对方未接听」：`setState(Idle)` 时 `previousState` 是 `Incoming` 而非 `Connected`，会走 `setStatus(2)` 且 `connectTime` 为 0，`VoipMessageViewHolder` 据此渲染成「对方未接听」。

- [ ] **Step 1: 加会话字段**

`AVEngineKit.CallSession` 类体内，`private long startMsgId;`（`:750`）之后加：

```java
        /**
         * 结束时要写入通话记录的 status 覆盖值，0 表示不覆盖、沿用原有逻辑。
         * setState 拿不到结束原因，由 endCall 在切 Idle 之前置位。
         */
        private int endStatusOverride;
```

- [ ] **Step 2: endCall 置位**

`endCall(reason)`（`:1014`）中，Step 3（Task 5）插入的 406 发送块之后、`this.clientId = null;` 之前插入：

```java
                    // 在别的设备上接听了 —— 本地记录写 3（已在其他设备接听）而不是 2（对方未接听）。
                    // 其他端拒接（RejectByOtherClient）沿用 2，语义上确实没人接。
                    if (reason == AVEngineKit.CallEndReason.AcceptByOtherClient) {
                        this.endStatusOverride = 3;
                    }
```

- [ ] **Step 3: 记录更新逻辑读取覆盖值**

`AVEngineKit.java:867-881` 的记录更新块，把：

```java
                                    if (state == AVEngineKit.CallState.Connected) {
                                        startMessageContent.setConnectTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(1);
                                    } else {
                                        startMessageContent.setEndTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(2);
                                    }
```

改为：

```java
                                    if (state == AVEngineKit.CallState.Connected) {
                                        startMessageContent.setConnectTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(1);
                                    } else if (this.endStatusOverride > 0) {
                                        startMessageContent.setEndTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(this.endStatusOverride);
                                    } else {
                                        startMessageContent.setEndTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(2);
                                    }
```

- [ ] **Step 4: UI 渲染新状态**

`VoipMessageViewHolder.java` 的 `onBind`，把：

```java
        if (content.getStatus() == 0) {
            textView.setText("对方未接听");
        } else if (content.getStatus() == 1) {
            textView.setText("通话中");
        } else {
```

改为：

```java
        if (content.getStatus() == 0) {
            textView.setText("对方未接听");
        } else if (content.getStatus() == 1) {
            textView.setText("通话中");
        } else if (content.getStatus() == 3) {
            textView.setText("已在其他设备接听");
        } else {
```

`:59` 的点击回拨守卫（`getStatus() == 1` 时不回拨）保持不变 —— status 3 的记录允许点击回拨，与已结束的通话一致。

- [ ] **Step 5: 编译整个 APK**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :chat:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 跑 p2penginekit 单测确认无回归**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :p2penginekit:testDebugUnitTest
```

Expected: 全部通过

- [ ] **Step 7: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro
git add p2penginekit/src/main/java/com/comsince/github/p2penginekit/AVEngineKit.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/VoipMessageViewHolder.java
```

---

### Task 7: Android 真机端到端验证（关键验证点）

**Files:** 无代码改动。

这是整个计划的关键闸门：只有它通过，才说明服务端投递能力真的生效，再铺 iOS 与 Electron。**不通过就不要往下做**，回到 Task 2 排查服务端。

需要两台 Android 设备登录同一账号，外加第三个账号做主叫。

- [ ] **Step 1: 装包到两台设备**

```bash
ADB=/Users/liaojinlong/Library/Android/sdk/platform-tools/adb
$ADB devices -l          # 先确认两台都在线
$ADB -s 381QYEC3222BP install -r chat/build/outputs/apk/debug/chat-debug.apk
```

第二台设备同样装同一个 APK（用它的序列号替换）。

- [ ] **Step 2: 挂上 logcat**

```bash
ADB=/Users/liaojinlong/Library/Android/sdk/platform-tools/adb
$ADB -s 381QYEC3222BP logcat -c
$ADB -s 381QYEC3222BP logcat -v time > /tmp/voip-verify.log &
```

魅族当**不接听**的那一端。

- [ ] **Step 3: 复现场景 1（接听同步）**

用第三个账号呼叫这个账号 → 两台 Android 都响铃 → 在另一台上接听 → 观察魅族。

预期：魅族在 2 秒内自动关闭接听界面并停止响铃；另一台通话正常继续。

- [ ] **Step 4: 检查日志判据**

```bash
grep -E "messageType->|subSignal MDR|didCallEndWithReason" /tmp/voip-verify.log
```

三条判据必须同时满足：

1. 出现 `messageType-> 406`
2. 该条 406 后面**没有**紧跟 `subSignal MDR` —— `ReceiveMessageHandler.uploadReport()` 只对 `direction == 1` 上报回执，没有 MDR 就证明这条是 `direction == Send`，来自自己的另一台设备
3. 打出 `didCallEndWithReason=AcceptByOtherClient`

- [ ] **Step 5: 复现场景 2（拒接同步）**

再呼一次，在另一台上按拒接。

预期：魅族自动关闭；日志出现 `messageType-> 406` 且 `didCallEndWithReason=RejectByOtherClient`；主叫侧显示已拒接。

- [ ] **Step 6: 复现场景 3（不误挂通话）**

再呼一次，在另一台接听并保持通话；魅族被自动关闭后，在魅族上重新进入该会话，确认**没有**任何操作能影响另一台的通话（此时魅族已无 currentSession，界面也已关闭）。

预期：另一台通话全程不中断。

- [ ] **Step 7: 检查通话记录**

通话结束后，在魅族的聊天记录里查看这条通话气泡。

预期：显示「已在其他设备接听」，不是「对方未接听」。

- [ ] **Step 8: 停止抓取**

```bash
pkill -f "logcat -v time"
```

---

## 阶段三：ios-chat-pro

### Task 8: iOS CallSignalCodec 支持 406

**Files:**
- Modify: `ios-chat-pro/Sources/IMCall/CallSignalCodec.swift`（`:23-31` IncomingCallSignal、`:37-53` OutgoingCallSignal、`:70-85` decode、`:88-110` encode、`:117-121` 私有 struct 区）
- Test: `ios-chat-pro/Tests/IMCallTests/CallSignalCodecTests.swift`

**Interfaces:**
- Produces: `CallTakenReason`（`public enum`，`.answered` / `.rejected`，`String` raw value）；`IncomingCallSignal.callTaken(callId:reason:deviceId:)`；`OutgoingCallSignal.callTaken(callId:reason:deviceId:)`（Task 9 使用）

- [ ] **Step 1: 写失败的测试**

在 `Tests/IMCallTests/CallSignalCodecTests.swift` 末尾（类体内）追加：

```swift
    func testEncodeCallTakenProducesType406WithReasonAndDevice() {
        let (wireType, callId, data) = CallSignalCodec.encode(
            .callTaken(callId: "call-abc", reason: .rejected, deviceId: "device-1"))

        XCTAssertEqual(wireType, 406)
        XCTAssertEqual(callId, "call-abc")
        let json = try! JSONSerialization.jsonObject(with: data!) as! [String: String]
        XCTAssertEqual(json["r"], "rejected")
        XCTAssertEqual(json["d"], "device-1")
    }

    func testDecodeCallTakenReadsReasonAndDevice() {
        var content = Im_MessageContent()
        content.type = 406
        content.content = "call-abc"
        content.data = Data(#"{"r":"rejected","d":"device-1"}"#.utf8)
        var message = Im_Message()
        message.content = content

        XCTAssertEqual(CallSignalCodec.decode(message),
                       .callTaken(callId: "call-abc", reason: .rejected, deviceId: "device-1"))
    }

    func testDecodeCallTakenFallsBackToAnsweredOnMissingReason() {
        var content = Im_MessageContent()
        content.type = 406
        content.content = "call-abc"
        content.data = Data(#"{"d":"device-1"}"#.utf8)
        var message = Im_Message()
        message.content = content

        XCTAssertEqual(CallSignalCodec.decode(message),
                       .callTaken(callId: "call-abc", reason: .answered, deviceId: "device-1"))
    }

    func testDecodeCallTakenFallsBackToAnsweredOnBrokenPayload() {
        var content = Im_MessageContent()
        content.type = 406
        content.content = "call-abc"
        content.data = Data("not json".utf8)
        var message = Im_Message()
        message.content = content

        XCTAssertEqual(CallSignalCodec.decode(message),
                       .callTaken(callId: "call-abc", reason: .answered, deviceId: ""))
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/ios-chat-pro
swift test --filter IMCallTests.CallSignalCodecTests
```

Expected: 编译失败，`type 'OutgoingCallSignal' has no member 'callTaken'`

- [ ] **Step 3: 加原因枚举与 signal case**

`CallSignalCodec.swift` 中，`RemoteIceCandidate` struct 之后加：

```swift
/// 406 多端同步信令的处理方式。原始值即线上 JSON 的 `r` 字段。
public enum CallTakenReason: String, Equatable {
    case answered
    case rejected
}
```

`IncomingCallSignal` 枚举末尾加：

```swift
    /// 406 —— 自己账号的另一台设备接听/拒接了这通来电。
    case callTaken(callId: String, reason: CallTakenReason, deviceId: String)
```

`OutgoingCallSignal` 枚举末尾加：

```swift
    /// 406 —— 通知自己账号的其他设备本端已处理这通来电；对端收不到。
    case callTaken(callId: String, reason: CallTakenReason, deviceId: String)
```

- [ ] **Step 4: 实现编解码**

`decode` 的 `switch wireMessage.content.type` 中，`case 404:` 之前加：

```swift
        case 406:
            let payload = try? JSONDecoder().decode(CallTakenWirePayload.self, from: data)
            // r 缺失/不认识一律回退 answered —— 406 只在接听与拒接时发送，两者都要求
            // 本端关闭来电界面，默认 answered 保证核心行为不因 payload 异常失效。
            let reason = payload.flatMap { CallTakenReason(rawValue: $0.r ?? "") } ?? .answered
            return .callTaken(callId: callId, reason: reason, deviceId: payload?.d ?? "")
```

`encode` 的 switch 中，`case .removeCandidates` 之前加：

```swift
        case .callTaken(let callId, let reason, let deviceId):
            return (406, callId, try? JSONEncoder().encode(
                CallTakenWirePayload(r: reason.rawValue, d: deviceId)))
```

私有 struct 区（`SDPWireSignal` 那几行附近）加：

```swift
    private struct CallTakenWirePayload: Codable { let r: String?; let d: String? }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/ios-chat-pro
swift test --filter IMCallTests.CallSignalCodecTests
```

Expected: 全部通过（含原有用例）

- [ ] **Step 6: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/ios-chat-pro
git add Sources/IMCall/CallSignalCodec.swift Tests/IMCallTests/CallSignalCodecTests.swift
```

---

### Task 9: iOS CallManager 收发与通话记录

**Files:**
- Modify: `ios-chat-pro/Sources/IMCall/CallSession.swift:11-20`（CallEndReason）
- Modify: `ios-chat-pro/Sources/IMCall/CallManager.swift`（`:53-58` 依赖、`:59-82` init、`:107-125` answer、`:130-134` hangUp、`:161-170` 自发信令分支、`:316-326` endSession、`:112` 注释）
- Modify: `ios-chat-pro/Sources/AppCore/AppEnvironment.swift:181`
- Test: `ios-chat-pro/Tests/IMCallTests/CallManagerTests.swift`（含 `:48` 的构造 helper）

**Interfaces:**
- Consumes: `CallTakenReason`、`IncomingCallSignal.callTaken`、`OutgoingCallSignal.callTaken`（Task 8）
- Produces: `CallEndReason.rejectedElsewhere`

- [ ] **Step 1: 加结束原因**

`CallSession.swift` 的 `CallEndReason` 枚举，在 `case acceptedElsewhere` 之后加：

```swift
    /// 自己账号的另一台设备拒接了这通来电 —— 本端静默收场，不发 Bye
    /// （对齐 Android CallEndReason.RejectByOtherClient）。
    case rejectedElsewhere
```

- [ ] **Step 2: 注入 deviceId**

`CallManager.swift:53-58` 的依赖区，`private let myUserId: () -> String` 之后加：

```swift
    private let myDeviceId: () -> String
```

init 参数表里 `myUserId: @escaping () -> String,` 之后加：

```swift
        myDeviceId: @escaping () -> String,
```

init 方法体 `self.myUserId = myUserId` 之后加：

```swift
        self.myDeviceId = myDeviceId
```

`AppEnvironment.swift:181` 的构造调用，把 `myUserId: { client.userId },` 改为：

```swift
myUserId: { client.userId }, myDeviceId: { client.clientIdentifier },
```

`Tests/IMCallTests/CallManagerTests.swift:48` 的构造 helper 里补上：

```swift
            myDeviceId: { "test-device" },
```

- [ ] **Step 3: 写失败的测试**

`Tests/IMCallTests/CallManagerTests.swift` 类体内追加。`deliverSignal` 是该文件既有的 helper（用 `CallSignalCodec.encode` 造一条 wire message 再喂给 manager），沿用它并把 `from` 设成自己的 userId 来模拟「自己其他设备发来的 406」：

```swift
    func testAnswerAlsoSendsCallTakenAnswered() throws {
        let manager = makeManager()
        deliverIncomingCallStart(to: manager, callId: "call-abc")

        try manager.answer()

        let sentTypes = messagingService.sentCallSignals.map { CallSignalCodec.encode($0).wireType }
        XCTAssertTrue(sentTypes.contains(406))
    }

    func testCallTakenFromOwnOtherDeviceEndsIncomingSession() throws {
        let manager = makeManager()
        deliverIncomingCallStart(to: manager, callId: "call-abc")
        var endReason: CallEndReason?
        manager.onCallEnded = { endReason = $0 }

        deliverSignal(.callTaken(callId: "call-abc", reason: .answered, deviceId: "other-device"),
                      from: myUserIdValue, to: manager)

        XCTAssertEqual(manager.state, .idle)
        XCTAssertEqual(endReason, .acceptedElsewhere)
    }

    func testCallTakenRejectedFromOwnOtherDeviceEndsWithRejectedElsewhere() throws {
        let manager = makeManager()
        deliverIncomingCallStart(to: manager, callId: "call-abc")
        var endReason: CallEndReason?
        manager.onCallEnded = { endReason = $0 }

        deliverSignal(.callTaken(callId: "call-abc", reason: .rejected, deviceId: "other-device"),
                      from: myUserIdValue, to: manager)

        XCTAssertEqual(endReason, .rejectedElsewhere)
    }

    func testCallTakenDoesNotSendBye() throws {
        let manager = makeManager()
        deliverIncomingCallStart(to: manager, callId: "call-abc")

        deliverSignal(.callTaken(callId: "call-abc", reason: .answered, deviceId: "other-device"),
                      from: myUserIdValue, to: manager)

        let sentTypes = messagingService.sentCallSignals.map { CallSignalCodec.encode($0).wireType }
        XCTAssertFalse(sentTypes.contains(402))
    }
```

若 `CallManagerTests.swift` 里现有 helper 的名字与上面不同（`makeManager` / `deliverSignal` / `deliverIncomingCallStart` / `messagingService.sentCallSignals` / `myUserIdValue`），按该文件实际的命名替换，不要新造一套桩。

- [ ] **Step 4: 跑测试确认失败**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/ios-chat-pro
swift test --filter IMCallTests.CallManagerTests
```

Expected: 新增的四个用例失败

- [ ] **Step 5: 接听与拒接补发 406**

`CallManager.answer()`（`:107`）中，`try sendSignal(.answer(callId: session.callId, audioOnly: session.audioOnly), to: session.peerUid)` 之后加：

```swift
        try sendSignal(.callTaken(callId: session.callId, reason: .answered, deviceId: myDeviceId()),
                       to: session.peerUid)
```

顺手把 `:112` 那条写反的注释改掉 —— 405 是透传给**对端**，401 才是多端同步用的，而现在这两者都被 406 取代：

```swift
        // Android answerCall 的发送顺序：AnswerT(405,透传给对端)先行，
        // Answer(401,通知对端接听)随后；406 才是通知自己其他设备的多端同步信令。
```

`hangUp(reason:)`（`:130`）中，`try sendSignal(.bye(callId: session.callId), to: session.peerUid)` 之前加：

```swift
        // 只有「本端用户主动拒接」才广播 rejected：state == .incoming 把它与
        // 通话中挂断区分开，通话中挂断时其他端并不在响铃。
        if state == .incoming, reason == .localHangup {
            try sendSignal(.callTaken(callId: session.callId, reason: .rejected, deviceId: myDeviceId()),
                           to: session.peerUid)
        }
```

- [ ] **Step 6: 处理收到的 406**

`handleIncomingSignal` 的 `if sender == myUserId() {` 分支（`:164-170`），把整个分支体替换为：

```swift
        if sender == myUserId() {
            if case .answer(let callId, _) = signal, state == .incoming, callId == session.callId {
                endSession(reason: .acceptedElsewhere)
            }
            if case .callTaken(let callId, let reason, _) = signal,
               state == .incoming, callId == session.callId {
                endSession(reason: reason == .rejected ? .rejectedElsewhere : .acceptedElsewhere)
            }
            return
        }
```

- [ ] **Step 7: 通话记录写 status 3**

`endSession(reason:)`（`:316`）中，把 `updateCallBubble(status: 2, endTime: nowMillis())`（`:320`）改为：

```swift
        // 在别的设备上接听了 —— 记录写 3（已在其他设备接听）而不是 2（对方未接听）。
        // 其他端拒接沿用 2，语义上确实没人接。
        updateCallBubble(status: reason == .acceptedElsewhere ? 3 : 2, endTime: nowMillis())
```

- [ ] **Step 8: 跑测试确认通过**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/ios-chat-pro
swift test --filter IMCallTests
```

Expected: 全部通过（含原有用例）

- [ ] **Step 9: 通话记录 UI 渲染**

找到 iOS 展示通话气泡的视图（`grep -rn "对方未接听\|通话时长" Sources/`），按 Android 同款语义加 `status == 3` → 「已在其他设备接听」分支。若该视图当前不按 status 分支渲染（只显示固定文案），则本步骤无需改动，在任务记录里注明即可 —— 不要为此新造一套渲染逻辑。

- [ ] **Step 10: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/ios-chat-pro
git add Sources/IMCall/CallSession.swift Sources/IMCall/CallManager.swift \
        Sources/AppCore/AppEnvironment.swift Tests/IMCallTests/CallManagerTests.swift
```

---

## 阶段四：electron-vue-chat-pro

### Task 10: Electron 406 消息类型与注册

**Files:**
- Modify: `electron-vue-chat-pro/src/renderer/src/websocket/message/messageContentType.ts:41`
- Create: `electron-vue-chat-pro/src/renderer/src/webrtc/message/callTakenMessageContent.ts`
- Modify: `electron-vue-chat-pro/src/renderer/src/websocket/message/messageConfig.ts:217-222`
- Test: `electron-vue-chat-pro/src/renderer/src/webrtc/message/callTakenMessageContent.test.ts`

**Interfaces:**
- Produces: `CallTakenMessageContent`（默认导出，字段 `callId` / `reason` / `deviceId`，静态常量 `REASON_ANSWERED` / `REASON_REJECTED`）（Task 11 使用）

- [ ] **Step 1: 写失败的测试**

创建 `src/renderer/src/webrtc/message/callTakenMessageContent.test.ts`：

```ts
import { describe, it, expect } from 'vitest'
import CallTakenMessageContent from './callTakenMessageContent'

function decodeFrom(binaryContent: string | null, content = 'call-abc') {
  const decoded = new CallTakenMessageContent()
  decoded.decode({ content, binaryContent } as any)
  return decoded
}

describe('CallTakenMessageContent', () => {
  it('round trips all fields through encode/decode', () => {
    const origin = new CallTakenMessageContent()
    origin.callId = 'call-abc'
    origin.reason = CallTakenMessageContent.REASON_REJECTED
    origin.deviceId = 'device-1'

    const decoded = new CallTakenMessageContent()
    decoded.decode(origin.encode())

    expect(decoded.callId).toBe('call-abc')
    expect(decoded.reason).toBe(CallTakenMessageContent.REASON_REJECTED)
    expect(decoded.deviceId).toBe('device-1')
  })

  it('puts callId in the content field', () => {
    const origin = new CallTakenMessageContent()
    origin.callId = 'call-abc'
    origin.reason = CallTakenMessageContent.REASON_ANSWERED
    origin.deviceId = 'device-1'

    expect(origin.encode().content).toBe('call-abc')
  })

  it('falls back to answered when reason is missing', () => {
    const decoded = decodeFrom(btoa(JSON.stringify({ d: 'device-1' })))

    expect(decoded.reason).toBe(CallTakenMessageContent.REASON_ANSWERED)
    expect(decoded.deviceId).toBe('device-1')
  })

  it('falls back to answered on broken payload', () => {
    const decoded = decodeFrom(btoa('not json'))

    expect(decoded.callId).toBe('call-abc')
    expect(decoded.reason).toBe(CallTakenMessageContent.REASON_ANSWERED)
    expect(decoded.deviceId).toBe('')
  })

  it('falls back to answered on null payload', () => {
    const decoded = decodeFrom(null)

    expect(decoded.reason).toBe(CallTakenMessageContent.REASON_ANSWERED)
    expect(decoded.deviceId).toBe('')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/electron-vue-chat-pro
npx vitest run src/renderer/src/webrtc/message/callTakenMessageContent.test.ts
```

Expected: 失败，`Failed to resolve import "./callTakenMessageContent"`

- [ ] **Step 3: 加常量**

`src/renderer/src/websocket/message/messageContentType.ts` 的 `VOIP_CONTENT_TYPE_ACCEPT_T = 405;` 之后加：

```ts
    static VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC = 406;
```

- [ ] **Step 4: 实现消息类**

创建 `src/renderer/src/webrtc/message/callTakenMessageContent.ts`。注意 `binaryContent` 必须走 `StringUtils.utf8_to_b64`，与该目录其他 content 类一致（WebSocket JSON 传输要求 base64）：

```ts
// @ts-nocheck
import MessageContent from '../../websocket/message/messageContent';
import MessageContentType from '../../websocket/message/messageContentType';
import StringUtils from "../../websocket/utils/StringUtil"

/**
 * 406 多端同步信令：告知自己账号的其他设备「这通电话我处理了」。
 * 只投递给发送者自己的其他设备，对端永远收不到。
 */
export default class CallTakenMessageContent extends MessageContent {
  static REASON_ANSWERED = 'answered';
  static REASON_REJECTED = 'rejected';

  callId;
  reason;
  deviceId;

  constructor() {
    super(MessageContentType.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC);
  }

  digest() {
    return '';
  }

  encode() {
    let payload = super.encode();
    payload.content = this.callId;
    payload.binaryContent = StringUtils.utf8_to_b64(JSON.stringify({
      r: this.reason,
      d: this.deviceId || ''
    }));
    return payload;
  }

  decode(payload) {
    super.decode(payload);
    this.callId = payload.content;
    // r 缺失/不认识一律回退 answered —— 406 只在接听与拒接时发送，两者都要求
    // 本端关闭来电界面，默认 answered 保证核心行为不因 payload 异常失效。
    this.reason = CallTakenMessageContent.REASON_ANSWERED;
    this.deviceId = '';
    if (!payload.binaryContent) {
      return;
    }
    try {
      let obj = JSON.parse(StringUtils.b64_to_utf8(payload.binaryContent));
      if (obj.r === CallTakenMessageContent.REASON_REJECTED) {
        this.reason = CallTakenMessageContent.REASON_REJECTED;
      }
      this.deviceId = obj.d || '';
    } catch (e) {
      console.log('decode callTaken payload error', e);
    }
  }
}
```

- [ ] **Step 5: 注册到 messageConfig**

`src/renderer/src/websocket/message/messageConfig.ts` 中，`callAnswerTMessageContent` 那一项之后加：

```ts
        {
            name: 'callTakenMessageContent',
            flag: PersistFlag.Transparent,
            type: MessageContentType.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC,
            contentClazz: CallTakenMessageContent,
        },
```

文件头部 import 区加：

```ts
import CallTakenMessageContent from '../../webrtc/message/callTakenMessageContent';
```

（照抄该文件里 `CallAnswerTMessageContent` 的 import 写法与相对路径。）

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/electron-vue-chat-pro
npx vitest run src/renderer/src/webrtc/message/callTakenMessageContent.test.ts
```

Expected: 5 passed

- [ ] **Step 7: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/electron-vue-chat-pro
git add src/renderer/src/websocket/message/messageContentType.ts \
        src/renderer/src/websocket/message/messageConfig.ts \
        src/renderer/src/webrtc/message/callTakenMessageContent.ts \
        src/renderer/src/webrtc/message/callTakenMessageContent.test.ts
```

---

### Task 11: Electron 收发与挂断路径拆分

**Files:**
- Modify: `electron-vue-chat-pro/src/renderer/src/webrtc/callEndReason.ts:11`
- Modify: `electron-vue-chat-pro/src/renderer/src/webrtc/voipclient.ts`（`:65-72` cancelCall、`:74-83` answerCall、`:132` direction 过滤、`:179-184` Bye 分支、新增 406 分支）

**Interfaces:**
- Consumes: `CallTakenMessageContent`（Task 10）
- Produces: `CallEndReason.REASON_RejectByOtherClient`

**这个任务有个必须先拆的陷阱**：`voipclient.ts:183` 的 Bye 接收分支直接调用了 `cancelCall()`，而 `cancelCall()` 自身会回发一条 Bye（`:68`）。若照字面在 `cancelCall()` 里加 406，对端挂断时 Electron 会向自己其他设备误播一条 `rejected`。必须先把「本端主动挂断」与「因收到对端 Bye 而结束」拆成两条路径。

- [ ] **Step 1: 加结束原因**

`src/renderer/src/webrtc/callEndReason.ts` 的 `REASON_AcceptByOtherClient` 那行之后加：

```ts
    static REASON_RejectByOtherClient = 'rejectByOtherClient';
```

- [ ] **Step 2: 拆分挂断路径**

`voipclient.ts` 的 `cancelCall()`（`:65-72`）替换为两个方法：

```ts
    /** 本端用户主动挂断/拒接。只有这条路径才广播 406。 */
    cancelCall(){
      var isRinging = this.currentSession.callState === CallState.STATUS_INCOMING;
      var byeMessage = new CallByeMessageContent();
      byeMessage.callId = this.currentSession.callId;
      this.offerMessage(this.currentSession.clientId,byeMessage);
      console.log("send bye message");
      // 只有「响铃中被本端拒接」才通知自己其他设备；通话中挂断时其他端并不在响铃。
      if(isRinging){
        this.sendCallTaken(CallTakenMessageContent.REASON_REJECTED);
      }
      this.currentSession.endCall(CallEndReason.REASON_RemoteHangup);
      this.currentSession = null;
    }

    /** 因收到对端 Bye 而结束。不回发 Bye，也不广播 406。 */
    endByRemoteBye(){
      this.currentSession.endCall(CallEndReason.REASON_RemoteHangup);
      this.currentSession = null;
    }

    /** 发一条 406 给自己账号的其他设备。conversation target 仍填对端，路由由服务端按 contentType 决定。 */
    sendCallTaken(reason){
      var takenMessage = new CallTakenMessageContent();
      takenMessage.callId = this.currentSession.callId;
      takenMessage.reason = reason;
      takenMessage.deviceId = localStorage.getItem(KEY_VUE_DEVICE_ID) || '';
      this.offerMessage(this.currentSession.clientId,takenMessage);
    }
```

`voipclient.ts` 头部 import 区加：

```ts
import CallTakenMessageContent from "./message/callTakenMessageContent";
import { KEY_VUE_DEVICE_ID } from "../constant";
```

（`KEY_VUE_DEVICE_ID` 定义在 `src/renderer/src/constant/index.ts:32`，值为 `'vue-device-id'`；照抄 `websocket/index.ts:2` 的 import 写法调整相对路径。）

- [ ] **Step 3: Bye 分支改走新路径**

`voipclient.ts:179-184` 的 Bye 接收分支，把 `this.cancelCall();` 改为：

```ts
             this.endByRemoteBye();
```

这同时修掉了 Electron 收到 Bye 又回发 Bye 的既有问题。

- [ ] **Step 4: 接听时补发 406**

`answerCall(audioOnly)`（`:74-83`）中，`this.offerMessage(this.currentSession.clientId,answerTMesage);` 之后、`this.startPreview();` 之前加：

```ts
        this.sendCallTaken(CallTakenMessageContent.REASON_ANSWERED);
```

- [ ] **Step 5: 放行自发的 406**

`voipclient.ts:132` 的接收过滤，把：

```ts
      if(new Date().getTime() - protoMessage.timestamp < 90000 && protoMessage.direction === 1 && protoMessage.conversationType == 0){
```

改为：

```ts
      // 406 是自己其他设备发来的（direction === 0），必须放行；其余信令仍只处理收到的。
      var isSelfSyncSignal = protoMessage.direction === 0
          && protoMessage.content.type === MessageContentType.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC;
      if(new Date().getTime() - protoMessage.timestamp < 90000 && (protoMessage.direction === 1 || isSelfSyncSignal) && protoMessage.conversationType == 0){
```

`MessageContentType` 在 `voipclient.ts` 里尚未 import（该文件 `:1-18` 的 import 区没有它），在 Step 2 加的两行 import 旁边补上：

```ts
import MessageContentType from "../websocket/message/messageContentType";
```

- [ ] **Step 6: 加 406 处理分支**

`onReceiveMessage` 的分发链中，`CallStartMessageContent` 分支之后加：

```ts
          } else if(content instanceof CallTakenMessageContent){
            // 只处理自己其他设备发来的，且必须还在响铃。
            if(protoMessage.direction === 0
                && this.currentSession
                && this.currentSession.callState === CallState.STATUS_INCOMING
                && content.callId === this.currentSession.callId){
              console.log("call taken elsewhere reason "+content.reason);
              this.currentSession.endCall(
                  content.reason === CallTakenMessageContent.REASON_REJECTED
                      ? CallEndReason.REASON_RejectByOtherClient
                      : CallEndReason.REASON_AcceptByOtherClient);
              this.currentSession = null;
            }
```

- [ ] **Step 7: 删掉旧 hack**

`voipclient.ts:174-176`，Signal 分支里那段靠「Signal 到达时状态不对」猜测多端接听的代码：

```ts
              } else {
                 this.currentSession.endCall(CallEndReason.REASON_AcceptByOtherClient);
                // this.currentSession.sessionCallback.didCallEndWithReason(CallEndReason.REASON_AcceptByOtherClient);
              }
```

整个 `else` 块删除。它语义错误（收到任何非 Connecting/Connected 状态下的 403 都会误结束会话），且已被 406 取代。

- [ ] **Step 8: 跑全量测试**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/electron-vue-chat-pro
npx vitest run
npm run build
```

Expected: 测试全绿，build 成功

- [ ] **Step 9: 通话记录确认（预期无改动）**

`MessageBubble.vue:90-98` 目前对所有 `type >= 400` 的消息只显示 `digest()`（「[语音通话]」/「[视频通话]」），Electron 从不在通话结束时回写 CallStart 的 status。因此 spec 里「status 3」这一条**在 Electron 上是空操作**，不需要改动。

跑一遍确认没有回归：

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/electron-vue-chat-pro
npx vitest run src/renderer/src/components/chat/MessageBubble.test.ts
```

Expected: 全部通过。若该测试确实断言了状态文案，再按 Android 同款语义补 status 3 分支。

- [ ] **Step 10: 暂存**

```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/electron-vue-chat-pro
git add src/renderer/src/webrtc/callEndReason.ts src/renderer/src/webrtc/voipclient.ts
```

---

### Task 12: 三端交叉验收

**Files:** 无代码改动。

spec 的 6 组验收场景，覆盖三端两两组合。需要同账号在两端登录 + 第三个账号做主叫。

- [ ] **Step 1: 场景 1 —— 接听同步（6 种组合）**

被叫方两端组合分别取 (Android, iOS)、(Android, Electron)、(iOS, Electron)，每组两个方向各测一次（谁接听、谁观察）。

预期：接听端正常通话；观察端 2 秒内关闭接听界面并停止响铃。

- [ ] **Step 2: 场景 2 —— 拒接同步**

同样 6 种组合，在一端拒接。

预期：观察端自动关闭；主叫侧显示已拒接。

- [ ] **Step 3: 场景 3 —— 不误挂通话**

一端接听并保持通话，确认另一端被自动关闭后，通话端全程不中断。

- [ ] **Step 4: 场景 4 —— 通话记录**

通话结束后检查观察端的聊天记录。

预期：Android 显示「已在其他设备接听」；iOS 按 Task 9 Step 9 的结论；Electron 显示「[语音通话]」/「[视频通话]」（无 status 渲染，符合预期）。

- [ ] **Step 5: 场景 5 —— 新旧版本互通**

一端装新版、一端装旧版，重复场景 1。

预期：旧端表现为改动前的行为（等整通结束才关闭），不崩溃、不误显示；新端正常。

- [ ] **Step 6: 场景 6 —— 通话中挂断不误播**

一端接听进入通话后正常挂断（不是拒接）。

预期：不产生 406（Android 可用 logcat 确认对端设备无 `messageType-> 406`）；双方正常结束。

---

## 自查记录

**Spec 覆盖检查**

| Spec 章节 | 对应任务 |
|---|---|
| Part A 协议（406 字段、寻址、兼容） | Task 1（常量）、Task 3 / 8 / 10（三端编解码） |
| Part B1 常量 | Task 1 |
| Part B2 定向推送方法 | Task 2 |
| Part B3 路由拦截 | Task 2 |
| Part B4 集群 | Task 2（复用同一推送原语，无额外改动，已在设计中说明） |
| Part C1 发送 + 两条约束 | Task 5（Android）、Task 9 Step 5（iOS）、Task 11 Step 2/4（Electron） |
| Part C2 接收 | Task 4（Android）、Task 9 Step 6（iOS）、Task 11 Step 6（Electron） |
| Part C3 通话记录 | Task 6（Android）、Task 9 Step 7/9（iOS）、Task 11 Step 9（Electron，确认为空操作） |
| Part C4 各端改动点 | Task 3–6 / 8–9 / 10–11 |
| 边界与错误处理 | Task 3 / 8 / 10 的回退用例；Task 12 Step 5/6 |
| 测试与验收 6 场景 | Task 7（Android 早期验证）、Task 12（三端交叉） |
| 实施顺序 | 四个阶段的排列，Task 7 是往下走的闸门 |

**类型一致性**：`REASON_ANSWERED`/`REASON_REJECTED`（Android、Electron）与 `CallTakenReason.answered`/`.rejected`（iOS）线上取值统一为 `"answered"`/`"rejected"`；三端 payload 键统一为 `r`/`d`；结束原因命名三端各随本仓库既有风格（`RejectByOtherClient` / `.rejectedElsewhere` / `REASON_RejectByOtherClient`），已在各任务的 Interfaces 块中标明。

**已知需实现者现场确认的点**（不是占位符，是明确的分支判断）：
- Task 9 Step 3：`CallManagerTests.swift` 现有 helper 的实际命名
- Task 9 Step 9：iOS 通话气泡是否按 status 渲染
- Task 11 Step 9：Electron `MessageBubble.test.ts` 是否断言状态文案
