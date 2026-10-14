# 语音转文字：android-chat-pro Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 客户端实现语音消息转文字：未播放红点右侧「转文字」按钮、长按菜单「转文字 / 隐藏文字」、气泡下方文字块、设置页「自动转文字」开关。

**Architecture:** 协议层沿用 `recall` 的完整链路：`SubSignal.ASR` → `ProtoService.speechToText` → `SpeechToTextHandler`（PUB_ACK）→ `JavaProtoLogic` → AIDL `IRemoteClient.speechToText(uid, IGeneralCallback2)` → `ChatManager.speechToText(uid, GeneralCallback2)`。UI 进程新增纯 Java 的 `TranscriptEngine`（状态机 + 超时 + 自动队列，可单测）和 Android 包装 `TranscriptManager`（SharedPreferences 持久化、主线程回调、LiveData 通知）。红点已有（`MessageStatus.Played`），只加按钮。

**Tech Stack:** Java（JDK 11 构建）、AIDL、protobuf 2.5 生成的 `WFCMessage`、ButterKnife、LiveData、JUnit 4

**Spec:** `chat-server-pro/docs/superpowers/specs/2026-10-13-voice-to-text-design.md`（§1 交互约定、§3、§6.4）

**前置依赖:** 服务端计划 Task 1（`chat-proto` 已生成 `SpeechToTextRequest/Result`）。Task 7 手动验证依赖服务端已部署。

## Global Constraints

- JDK 11，`./gradlew` 构建；单测 `./gradlew :<module>:testDebugUnitTest`（Android 库/应用模块）或 `:push-sdk:test`（纯 Java 模块，按模块实际任务名）。
- `SubSignal` 只允许末尾追加，`ASR` ordinal 必须为 **44**（与服务端、iOS 一致）。
- proto 文件只从 `../chat-proto/` 复制生成结果，不在本仓库重新生成。
- 所有失败一律显示「转文字失败，点击重试」；空文本显示「未识别到文字」。
- 点击「转文字」（按钮或长按菜单）把消息置为已播放；自动转文字不置已播放。
- 自动转文字只处理「实时」收到的他人语音：`now - (serverTime - serverDeltaTime) <= 2 分钟`；并发 2；按 messageUid 去重。
- 客户端单次请求超时 15 秒（协议层非 MS 请求没有超时，必须在 UI 侧兜底），超时后迟到的结果丢弃。
- 自动转文字开关默认关，只存本地 SharedPreferences。

## Review Focus

1. **请求没有回包时界面不能永远停在「转文字中…」**（断线、服务端无响应：协议层对非 MS 请求不设超时）—— Task 3 `timeoutTurnsLoadingIntoError` 与 `lateResultAfterTimeoutIsIgnored`。
2. **AIDL 回调在 binder 线程**，直接改 UI 会崩 —— Task 4 `TranscriptManager` 统一 `mainHandler.post`（手动走查第 2 项验证）。
3. **识别进行中点「隐藏文字」后结果回来不应再弹出** —— Task 3 `hideWhileLoadingStaysHidden`。
4. **重复点击不重复请求** —— Task 3 `requestWhileLoadingIsNoop`。
5. **离线期间积压的旧语音登录后不应被自动批量识别**（消耗免费额度）—— Task 3 `isRealtimeRejectsOldMessages`。

---

### Task 1: 协议层（SubSignal、proto、ProtoService、handler）

**Files:**
- Modify: `push-sdk/src/main/java/com/comsince/github/push/SubSignal.java`（`UN` 之后）
- Test: `push-sdk/src/test/java/com/comsince/github/push/SubSignalOrdinalTest.java`
- Replace: `client/src/main/java/cn/wildfirechat/proto/WFCMessage.java`（来自 `../chat-proto/cn/wildfirechat/proto/WFCMessage.java`）
- Replace: `push-sdk/src/main/java/com/comsince/github/proto/FSCMessage.java`（来自 `../chat-proto/com/comsince/github/proto/FSCMessage.java`）
- Create: `client/src/main/java/cn/wildfirechat/proto/handler/SpeechToTextHandler.java`
- Modify: `client/src/main/java/cn/wildfirechat/proto/ProtoService.java`（第 101 行注册 handler；`recallMessage` 第 515 行之后新增方法）
- Modify: `client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java`（`recallMessage` 第 349 行之后）

**Interfaces:**
- Produces: `SubSignal.ASR`；`ProtoService.speechToText(long messageUid, JavaProtoLogic.IGeneralCallback2 callback)`；`JavaProtoLogic.speechToText(long messageUid, JavaProtoLogic.IGeneralCallback2 callback)`；`SpeechToTextHandler.parseText(byte[] body): String`（静态，可测）

- [ ] **Step 1: 写 ordinal 失败测试**

在 `push-sdk/src/test/java/com/comsince/github/push/SubSignalOrdinalTest.java` 最后一个 `}` 之前追加：

```java
    @Test
    public void speechToTextSignalIsAppendedAfterUserSetting() {
        assertEquals(44, SubSignal.ASR.ordinal());
        assertEquals(SubSignal.ASR, SubSignal.toEnum(44));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro && ./gradlew :push-sdk:test --tests com.comsince.github.push.SubSignalOrdinalTest`
Expected: 编译失败 `cannot find symbol: variable ASR`。

- [ ] **Step 3: SubSignal 追加**

`push-sdk/src/main/java/com/comsince/github/push/SubSignal.java` 中

```java
    UG,
    UP,
    UN
    ;
```

改为

```java
    UG,
    UP,
    UN,
    // 语音转文字：带 messageUid 请求，PUB_ACK 返回 SpeechToTextResult。只允许在末尾追加，ordinal 与服务端、ios 对齐
    ASR
    ;
```

- [ ] **Step 4: 替换 proto 生成文件**

Run:
```bash
cd /Users/liaojinlong/Share/GitWorkplace/github/fshare
cp chat-proto/cn/wildfirechat/proto/WFCMessage.java android-chat-pro/client/src/main/java/cn/wildfirechat/proto/WFCMessage.java
cp chat-proto/com/comsince/github/proto/FSCMessage.java android-chat-pro/push-sdk/src/main/java/com/comsince/github/proto/FSCMessage.java
grep -c "class SpeechToTextResult extends" android-chat-pro/client/src/main/java/cn/wildfirechat/proto/WFCMessage.java
```
Expected: `1`。若 `git diff --stat` 显示这两个文件出现与本需求无关的大量删除（说明 chat-proto 与 Android 现有版本不同步），停止并报告，不要继续。

- [ ] **Step 5: 运行 ordinal 测试确认通过**

Run: `cd android-chat-pro && ./gradlew :push-sdk:test --tests com.comsince.github.push.SubSignalOrdinalTest`
Expected: PASS（4 tests）

- [ ] **Step 6: 写 handler 解析的失败测试**

创建 `client/src/test/java/cn/wildfirechat/proto/handler/SpeechToTextHandlerTest.java`：

```java
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
```

- [ ] **Step 7: 运行确认失败**

Run: `./gradlew :client:testDebugUnitTest --tests cn.wildfirechat.proto.handler.SpeechToTextHandlerTest`
Expected: 编译失败 `cannot find symbol: class SpeechToTextHandler`。

- [ ] **Step 8: 实现 handler**

创建 `client/src/main/java/cn/wildfirechat/proto/handler/SpeechToTextHandler.java`（import 风格对照同目录 `RecallMessageHandler.java` 的实际 import）：

```java
package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.google.protobuf.InvalidProtocolBufferException;

import cn.wildfirechat.proto.JavaProtoLogic;
import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.WFCMessage;

/**
 * 语音转文字应答：1 字节错误码 + （成功时）SpeechToTextResult
 */
public class SpeechToTextHandler extends AbstractMessageHandler {

    public SpeechToTextHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUB_ACK == header.getSignal() && SubSignal.ASR == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        int errorCode = byteBufferList.get();
        JavaProtoLogic.IGeneralCallback2 callback = (JavaProtoLogic.IGeneralCallback2) getCallback(header.getMessageId());
        if (callback == null) {
            return;
        }
        if (errorCode != 0) {
            callback.onFailure(errorCode);
            return;
        }
        try {
            callback.onSuccess(parseText(byteBufferList.getAllByteArray()));
        } catch (InvalidProtocolBufferException e) {
            logger.e("parse SpeechToTextResult failed", e);
            callback.onFailure(-1);
        }
    }

    static String parseText(byte[] body) throws InvalidProtocolBufferException {
        return WFCMessage.SpeechToTextResult.parseFrom(body).getText();
    }
}
```

（若 `AbstractMessageHandler` 中日志字段名不是 `logger`，按该类实际字段名替换；`GetMinioUploadUrlHandler` 中使用的是 `logger.i(...)`。）

- [ ] **Step 9: ProtoService 与 JavaProtoLogic**

`ProtoService.java`：

1. 第 101 行 `messageHandlers.add(new RecallMessageHandler(this));` 之后加 `messageHandlers.add(new SpeechToTextHandler(this));`，并 import `cn.wildfirechat.proto.handler.SpeechToTextHandler`（若该文件用通配 import 则无需）。
2. `recallMessage(...)` 方法之后加：

```java
    public void speechToText(long messageUid, JavaProtoLogic.IGeneralCallback2 callback){
        log.i("speechToText message Uid "+messageUid);
        WFCMessage.SpeechToTextRequest request = WFCMessage.SpeechToTextRequest.newBuilder()
                .setMessageUid(messageUid)
                .build();
        sendMessage(Signal.PUBLISH, SubSignal.ASR, request.toByteArray(), callback);
    }
```

`JavaProtoLogic.java` 第 349–351 行 `recallMessage` 之后加：

```java
    public static void speechToText(long messageUid, JavaProtoLogic.IGeneralCallback2 callback){
        protoService.speechToText(messageUid, callback);
    }
```

- [ ] **Step 10: 运行测试确认通过**

Run: `./gradlew :client:testDebugUnitTest --tests cn.wildfirechat.proto.handler.SpeechToTextHandlerTest`
Expected: PASS（2 tests）

- [ ] **Step 11: Commit**

```bash
git add push-sdk/src/main/java/com/comsince/github/push/SubSignal.java push-sdk/src/test/java/com/comsince/github/push/SubSignalOrdinalTest.java push-sdk/src/main/java/com/comsince/github/proto/FSCMessage.java client/src/main/java/cn/wildfirechat/proto/WFCMessage.java client/src/main/java/cn/wildfirechat/proto/handler/SpeechToTextHandler.java client/src/test/java/cn/wildfirechat/proto/handler/SpeechToTextHandlerTest.java client/src/main/java/cn/wildfirechat/proto/ProtoService.java client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java
git commit -m "feat(proto): 新增 ASR 语音转文字信令（SubSignal 44）"
```

---

### Task 2: AIDL 与 ChatManager 暴露 speechToText

**Files:**
- Modify: `client/src/main/aidl/cn/wildfirechat/client/IRemoteClient.aidl`（接口末尾）
- Modify: `client/src/main/java/cn/wildfirechat/client/ClientService.java`（`recall(...)` 第 379 行之后）
- Modify: `client/src/main/java/cn/wildfirechat/remote/ChatManager.java`（`recallMessage(...)` 之后）

**Interfaces:**
- Consumes: Task 1 `JavaProtoLogic.speechToText`
- Produces: `ChatManager.Instance().speechToText(long messageUid, GeneralCallback2 callback)`；回调在 **binder 线程**，调用方负责切主线程。

本 Task 是跨进程接线，没有可在 JVM 单测中运行的逻辑；验证为编译 + Task 7 手动走查。

- [ ] **Step 1: AIDL**

`IRemoteClient.aidl` 接口最后一个方法声明之后（结尾 `}` 之前）加：

```aidl
    oneway void speechToText(in long messageUid, in IGeneralCallback2 callback);
```

（`IGeneralCallback2` 已在第 7 行 import。）

- [ ] **Step 2: ClientService 实现**

`ClientService.java` 中 `recall(long messageUid, final IGeneralCallback callback)` 方法之后加：

```java
        @Override
        public void speechToText(long messageUid, final IGeneralCallback2 callback) throws RemoteException {
            JavaProtoLogic.speechToText(messageUid, new JavaProtoLogic.IGeneralCallback2() {
                @Override
                public void onSuccess(String text) {
                    try {
                        callback.onSuccess(text);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    try {
                        callback.onFailure(errorCode);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
```

- [ ] **Step 3: ChatManager**

`ChatManager.java` 中 `recallMessage(final Message msg, final GeneralCallback callback)` 方法之后加：

```java
    /**
     * 语音消息转文字。回调在 binder 线程，调用方自行切主线程。
     */
    public void speechToText(long messageUid, final GeneralCallback2 callback) {
        if (!checkRemoteService()) {
            callback.onFail(ErrorCode.SERVICE_DIED);
            return;
        }
        try {
            mClient.speechToText(messageUid, new cn.wildfirechat.client.IGeneralCallback2.Stub() {
                @Override
                public void onSuccess(String text) throws RemoteException {
                    callback.onSuccess(text);
                }

                @Override
                public void onFailure(int errorCode) throws RemoteException {
                    callback.onFail(errorCode);
                }
            });
        } catch (RemoteException e) {
            e.printStackTrace();
            callback.onFail(ErrorCode.SERVICE_DIED);
        }
    }
```

（`ErrorCode` 若未 import，加 `import cn.wildfirechat.ErrorCode;`——以 `ChatManager.java` 中已有的 `ErrorCode` 用法为准；`checkRemoteService()` 已存在于该类。）

- [ ] **Step 4: 编译**

Run: `./gradlew :client:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add client/src/main/aidl/cn/wildfirechat/client/IRemoteClient.aidl client/src/main/java/cn/wildfirechat/client/ClientService.java client/src/main/java/cn/wildfirechat/remote/ChatManager.java
git commit -m "feat(client): AIDL 与 ChatManager 暴露 speechToText"
```

---

### Task 3: TranscriptEngine（纯 Java 状态机）与显示规则

**Files:**
- Create: `chat/src/main/java/cn/wildfire/chat/kit/conversation/transcript/TranscriptEngine.java`
- Create: `chat/src/main/java/cn/wildfire/chat/kit/conversation/transcript/VoiceTranscriptRules.java`
- Test: `chat/src/test/java/cn/wildfire/chat/kit/conversation/transcript/TranscriptEngineTest.java`
- Test: `chat/src/test/java/cn/wildfire/chat/kit/conversation/transcript/VoiceTranscriptRulesTest.java`

**Interfaces:**
- Produces:
  - `TranscriptEngine.Status { LOADING, DONE, ERROR }`
  - `TranscriptEngine.Entry`（不可变）：`Status status; String text; boolean hidden`
  - `TranscriptEngine.Transport { void speechToText(long uid, ResultCallback cb); }`，`ResultCallback { void onSuccess(String text); void onFail(int code); }`（**必须在主线程回调**）
  - `TranscriptEngine.Scheduler { void postDelayed(Runnable r, long delayMs); }`
  - `TranscriptEngine.Persistence { Map<Long, String[]> loadDone(); void saveDone(Map<Long, String[]> done); boolean loadAuto(); void saveAuto(boolean on); }`（`String[]{text, hidden?"1":"0"}`）
  - `TranscriptEngine.Listener { void onChanged(long uid); }`
  - `TranscriptEngine(Transport, Scheduler, Persistence, Listener)`；方法 `Entry get(long uid)`、`void request(long uid)`、`void hide(long uid)`、`boolean isAutoTranscribe()`、`void setAutoTranscribe(boolean)`、`void enqueueAuto(long uid)`
  - 常量 `TIMEOUT_MS = 15000`、`AUTO_CONCURRENCY = 2`
  - `VoiceTranscriptRules`：`enum Block { NONE, LOADING, TEXT, EMPTY, ERROR }`、`static boolean showPill(boolean incoming, boolean played, long uid, Entry e, boolean auto)`、`static Block block(Entry e)`、`static boolean showTranscribeMenu(long uid, Entry e)`、`static boolean showHideMenu(long uid, Entry e)`、`static boolean isRealtime(long serverTime, long serverDelta, long now)`、常量 `REALTIME_WINDOW_MS = 120000`

- [ ] **Step 1: 写规则的失败测试**

创建 `VoiceTranscriptRulesTest.java`：

```java
package cn.wildfire.chat.kit.conversation.transcript;

import org.junit.Test;

import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Entry;
import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VoiceTranscriptRulesTest {
    private static final Entry LOADING = new Entry(Status.LOADING, "", false);
    private static final Entry DONE = new Entry(Status.DONE, "hi", false);
    private static final Entry EMPTY = new Entry(Status.DONE, "", false);
    private static final Entry HIDDEN = new Entry(Status.DONE, "hi", true);
    private static final Entry ERROR = new Entry(Status.ERROR, "", false);

    @Test
    public void pillOnlyForIncomingUnplayedUntranscribedWithAutoOff() {
        assertTrue(VoiceTranscriptRules.showPill(true, false, 1L, null, false));
        assertFalse(VoiceTranscriptRules.showPill(true, false, 1L, null, true));
        assertFalse(VoiceTranscriptRules.showPill(true, true, 1L, null, false));
        assertFalse(VoiceTranscriptRules.showPill(false, false, 1L, null, false));
        assertFalse(VoiceTranscriptRules.showPill(true, false, 1L, LOADING, false));
        assertFalse(VoiceTranscriptRules.showPill(true, false, 0L, null, false));
    }

    @Test
    public void blockStates() {
        assertEquals(VoiceTranscriptRules.Block.NONE, VoiceTranscriptRules.block(null));
        assertEquals(VoiceTranscriptRules.Block.LOADING, VoiceTranscriptRules.block(LOADING));
        assertEquals(VoiceTranscriptRules.Block.TEXT, VoiceTranscriptRules.block(DONE));
        assertEquals(VoiceTranscriptRules.Block.EMPTY, VoiceTranscriptRules.block(EMPTY));
        assertEquals(VoiceTranscriptRules.Block.ERROR, VoiceTranscriptRules.block(ERROR));
        assertEquals(VoiceTranscriptRules.Block.NONE, VoiceTranscriptRules.block(HIDDEN));
    }

    @Test
    public void menuItemsAreMutuallyExclusive() {
        assertTrue(VoiceTranscriptRules.showTranscribeMenu(1L, null));
        assertTrue(VoiceTranscriptRules.showTranscribeMenu(1L, ERROR));
        assertTrue(VoiceTranscriptRules.showTranscribeMenu(1L, HIDDEN));
        assertFalse(VoiceTranscriptRules.showTranscribeMenu(1L, DONE));
        assertFalse(VoiceTranscriptRules.showTranscribeMenu(1L, LOADING));

        assertTrue(VoiceTranscriptRules.showHideMenu(1L, DONE));
        assertTrue(VoiceTranscriptRules.showHideMenu(1L, LOADING));
        assertFalse(VoiceTranscriptRules.showHideMenu(1L, null));
        assertFalse(VoiceTranscriptRules.showHideMenu(1L, HIDDEN));
    }

    @Test
    public void noMenuWithoutMessageUid() {
        assertFalse(VoiceTranscriptRules.showTranscribeMenu(0L, null));
        assertFalse(VoiceTranscriptRules.showHideMenu(0L, DONE));
    }

    @Test
    public void isRealtimeRejectsOldMessages() {
        long now = 1_000_000_000L;
        long delta = 500L; // 服务器比本机快 500ms
        assertTrue(VoiceTranscriptRules.isRealtime(now + delta - 1_000, delta, now));
        assertTrue(VoiceTranscriptRules.isRealtime(now + delta - VoiceTranscriptRules.REALTIME_WINDOW_MS, delta, now));
        assertFalse(VoiceTranscriptRules.isRealtime(now + delta - VoiceTranscriptRules.REALTIME_WINDOW_MS - 1, delta, now));
    }
}
```

- [ ] **Step 2: 写引擎的失败测试**

创建 `TranscriptEngineTest.java`：

```java
package cn.wildfire.chat.kit.conversation.transcript;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TranscriptEngineTest {

    static class Call {
        final long uid;
        final TranscriptEngine.ResultCallback cb;
        Call(long uid, TranscriptEngine.ResultCallback cb) { this.uid = uid; this.cb = cb; }
    }

    final List<Call> calls = new ArrayList<>();
    final List<Runnable> timers = new ArrayList<>();
    final List<Long> changed = new ArrayList<>();
    Map<Long, String[]> saved = new HashMap<>();
    boolean savedAuto = false;
    TranscriptEngine engine;

    @Before
    public void setUp() {
        engine = newEngine();
    }

    TranscriptEngine newEngine() {
        return new TranscriptEngine(
                (uid, cb) -> calls.add(new Call(uid, cb)),
                (r, delay) -> {
                    assertEquals(TranscriptEngine.TIMEOUT_MS, delay);
                    timers.add(r);
                },
                new TranscriptEngine.Persistence() {
                    @Override public Map<Long, String[]> loadDone() { return new HashMap<>(saved); }
                    @Override public void saveDone(Map<Long, String[]> done) { saved = new HashMap<>(done); }
                    @Override public boolean loadAuto() { return savedAuto; }
                    @Override public void saveAuto(boolean on) { savedAuto = on; }
                },
                changed::add);
    }

    @Test
    public void successGoesLoadingThenDoneAndPersists() {
        engine.request(7L);
        assertEquals(TranscriptEngine.Status.LOADING, engine.get(7L).status);

        calls.get(0).cb.onSuccess("你好");

        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
        assertEquals("你好", engine.get(7L).text);
        assertEquals("你好", saved.get(7L)[0]);
        assertTrue(changed.size() >= 2);
    }

    @Test
    public void failureIsErrorAndRetryable() {
        engine.request(7L);
        calls.get(0).cb.onFail(248);
        assertEquals(TranscriptEngine.Status.ERROR, engine.get(7L).status);
        assertFalse(saved.containsKey(7L));

        engine.request(7L);
        assertEquals(2, calls.size());
    }

    @Test
    public void requestWhileLoadingIsNoop() {
        engine.request(7L);
        engine.request(7L);

        assertEquals(1, calls.size());
    }

    @Test
    public void timeoutTurnsLoadingIntoError() {
        engine.request(7L);
        timers.get(0).run();

        assertEquals(TranscriptEngine.Status.ERROR, engine.get(7L).status);
    }

    @Test
    public void lateResultAfterTimeoutIsIgnored() {
        engine.request(7L);
        timers.get(0).run();
        calls.get(0).cb.onSuccess("late");

        assertEquals(TranscriptEngine.Status.ERROR, engine.get(7L).status);
    }

    @Test
    public void timeoutAfterSuccessIsIgnored() {
        engine.request(7L);
        calls.get(0).cb.onSuccess("ok");
        timers.get(0).run();

        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
    }

    @Test
    public void hideThenRequestUnhidesWithoutNetwork() {
        engine.request(7L);
        calls.get(0).cb.onSuccess("a");
        engine.hide(7L);
        assertTrue(engine.get(7L).hidden);
        assertEquals("1", saved.get(7L)[1]);

        engine.request(7L);

        assertFalse(engine.get(7L).hidden);
        assertEquals(1, calls.size());
    }

    @Test
    public void hideWhileLoadingStaysHidden() {
        engine.request(7L);
        engine.hide(7L);
        calls.get(0).cb.onSuccess("a");

        assertTrue(engine.get(7L).hidden);
        assertEquals(TranscriptEngine.Status.DONE, engine.get(7L).status);
    }

    @Test
    public void restoresDoneEntriesAndAutoFlag() {
        saved.put(9L, new String[]{"旧", "0"});
        savedAuto = true;

        TranscriptEngine restored = newEngine();

        assertEquals("旧", restored.get(9L).text);
        assertTrue(restored.isAutoTranscribe());
        assertNull(restored.get(10L));
    }

    @Test
    public void enqueueAutoIgnoredWhenOff() {
        engine.enqueueAuto(1L);

        assertTrue(calls.isEmpty());
    }

    @Test
    public void enqueueAutoRunsTwoAtATimeAndDedupes() {
        engine.setAutoTranscribe(true);
        assertTrue(savedAuto);

        engine.enqueueAuto(1L);
        engine.enqueueAuto(2L);
        engine.enqueueAuto(3L);
        engine.enqueueAuto(1L);
        assertEquals(2, calls.size());

        calls.get(0).cb.onSuccess("1");
        assertEquals(3, calls.size());
        assertEquals(3L, calls.get(2).uid);

        calls.get(1).cb.onFail(4);
        calls.get(2).cb.onSuccess("3");
        assertEquals(3, calls.size());
    }

    @Test
    public void enqueueAutoSkipsAlreadyTranscribed() {
        engine.request(1L);
        calls.get(0).cb.onSuccess("x");
        engine.setAutoTranscribe(true);

        engine.enqueueAuto(1L);

        assertEquals(1, calls.size());
    }

    @Test
    public void autoSlotIsFreedOnTimeout() {
        engine.setAutoTranscribe(true);
        engine.enqueueAuto(1L);
        engine.enqueueAuto(2L);
        engine.enqueueAuto(3L);

        timers.get(0).run();

        assertEquals(3, calls.size());
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :chat:testDebugUnitTest --tests 'cn.wildfire.chat.kit.conversation.transcript.*'`
Expected: 编译失败 `cannot find symbol: class TranscriptEngine`。

- [ ] **Step 4: 实现 TranscriptEngine**

```java
package cn.wildfire.chat.kit.conversation.transcript;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 语音转文字状态机（纯 Java，便于单测）。所有方法与回调都要求在同一线程（主线程）调用。
 * 协议层对非 MS 请求不设超时，这里用 TIMEOUT_MS 兜底，超时后迟到的结果丢弃。
 */
public class TranscriptEngine {
    public static final long TIMEOUT_MS = 15000;
    public static final int AUTO_CONCURRENCY = 2;

    public enum Status { LOADING, DONE, ERROR }

    public static final class Entry {
        public final Status status;
        public final String text;
        public final boolean hidden;

        public Entry(Status status, String text, boolean hidden) {
            this.status = status;
            this.text = text;
            this.hidden = hidden;
        }
    }

    public interface ResultCallback {
        void onSuccess(String text);

        void onFail(int code);
    }

    public interface Transport {
        void speechToText(long uid, ResultCallback cb);
    }

    public interface Scheduler {
        void postDelayed(Runnable r, long delayMs);
    }

    public interface Persistence {
        Map<Long, String[]> loadDone();

        void saveDone(Map<Long, String[]> done);

        boolean loadAuto();

        void saveAuto(boolean on);
    }

    public interface Listener {
        void onChanged(long uid);
    }

    private final Transport transport;
    private final Scheduler scheduler;
    private final Persistence persistence;
    private final Listener listener;

    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, Integer> inflight = new HashMap<>();
    private final Deque<Long> autoQueue = new ArrayDeque<>();
    private final Set<Long> autoRunning = new HashSet<>();
    private boolean autoTranscribe;
    private int seq = 0;

    public TranscriptEngine(Transport transport, Scheduler scheduler, Persistence persistence, Listener listener) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.persistence = persistence;
        this.listener = listener;
        for (Map.Entry<Long, String[]> e : persistence.loadDone().entrySet()) {
            String[] v = e.getValue();
            entries.put(e.getKey(), new Entry(Status.DONE, v[0], "1".equals(v[1])));
        }
        this.autoTranscribe = persistence.loadAuto();
    }

    public Entry get(long uid) {
        return entries.get(uid);
    }

    public void request(long uid) {
        Entry cur = entries.get(uid);
        if (cur != null && cur.status == Status.LOADING) {
            return;
        }
        if (cur != null && cur.status == Status.DONE) {
            if (cur.hidden) {
                put(uid, new Entry(Status.DONE, cur.text, false));
                persist();
            }
            return;
        }
        final int gen = ++seq;
        inflight.put(uid, gen);
        put(uid, new Entry(Status.LOADING, "", false));
        scheduler.postDelayed(() -> finish(uid, gen, null), TIMEOUT_MS);
        transport.speechToText(uid, new ResultCallback() {
            @Override
            public void onSuccess(String text) {
                finish(uid, gen, text == null ? "" : text);
            }

            @Override
            public void onFail(int code) {
                finish(uid, gen, null);
            }
        });
    }

    public void hide(long uid) {
        Entry cur = entries.get(uid);
        if (cur == null) {
            return;
        }
        put(uid, new Entry(cur.status, cur.text, true));
        persist();
    }

    public boolean isAutoTranscribe() {
        return autoTranscribe;
    }

    public void setAutoTranscribe(boolean on) {
        autoTranscribe = on;
        persistence.saveAuto(on);
    }

    /** 实时收到的他人语音：开关打开时排队自动识别，已有结果或已在队列中的跳过 */
    public void enqueueAuto(long uid) {
        if (!autoTranscribe || entries.containsKey(uid) || autoQueue.contains(uid) || autoRunning.contains(uid)) {
            return;
        }
        autoQueue.add(uid);
        drain();
    }

    private void drain() {
        while (autoRunning.size() < AUTO_CONCURRENCY && !autoQueue.isEmpty()) {
            long uid = autoQueue.poll();
            autoRunning.add(uid);
            request(uid);
        }
    }

    private void finish(long uid, int gen, String text) {
        Integer current = inflight.get(uid);
        if (current == null || current != gen) {
            return;
        }
        inflight.remove(uid);
        Entry cur = entries.get(uid);
        boolean hidden = cur != null && cur.hidden;
        put(uid, text == null ? new Entry(Status.ERROR, "", hidden) : new Entry(Status.DONE, text, hidden));
        persist();
        if (autoRunning.remove(uid)) {
            drain();
        }
    }

    private void put(long uid, Entry entry) {
        entries.put(uid, entry);
        listener.onChanged(uid);
    }

    private void persist() {
        Map<Long, String[]> done = new HashMap<>();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            if (v.status == Status.DONE) {
                done.put(e.getKey(), new String[]{v.text, v.hidden ? "1" : "0"});
            }
        }
        persistence.saveDone(done);
    }
}
```

- [ ] **Step 5: 实现 VoiceTranscriptRules**

```java
package cn.wildfire.chat.kit.conversation.transcript;

import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Entry;
import static cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine.Status;

/** 语音气泡上转文字相关控件的显示规则，与 electron / web / ios 保持一致 */
public final class VoiceTranscriptRules {
    public static final long REALTIME_WINDOW_MS = 2 * 60 * 1000;

    public enum Block { NONE, LOADING, TEXT, EMPTY, ERROR }

    private VoiceTranscriptRules() {
    }

    public static boolean showPill(boolean incoming, boolean played, long uid, Entry e, boolean auto) {
        return incoming && !played && uid > 0 && e == null && !auto;
    }

    public static Block block(Entry e) {
        if (e == null || e.hidden) {
            return Block.NONE;
        }
        if (e.status == Status.LOADING) {
            return Block.LOADING;
        }
        if (e.status == Status.ERROR) {
            return Block.ERROR;
        }
        return e.text.isEmpty() ? Block.EMPTY : Block.TEXT;
    }

    private static boolean showing(Entry e) {
        return e != null && !e.hidden && e.status != Status.ERROR;
    }

    public static boolean showTranscribeMenu(long uid, Entry e) {
        return uid > 0 && !showing(e);
    }

    public static boolean showHideMenu(long uid, Entry e) {
        return uid > 0 && showing(e);
    }

    /** 离线期间积压的语音登录后不自动识别，只识别 2 分钟内到达的 */
    public static boolean isRealtime(long serverTime, long serverDelta, long now) {
        return now - (serverTime - serverDelta) <= REALTIME_WINDOW_MS;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :chat:testDebugUnitTest --tests 'cn.wildfire.chat.kit.conversation.transcript.*'`
Expected: PASS（18 tests）

- [ ] **Step 7: Commit**

```bash
git add chat/src/main/java/cn/wildfire/chat/kit/conversation/transcript chat/src/test/java/cn/wildfire/chat/kit/conversation/transcript
git commit -m "feat(transcript): 语音转文字状态机与显示规则"
```

---

### Task 4: TranscriptManager（Android 包装）与自动识别接入

**Files:**
- Create: `chat/src/main/java/cn/wildfire/chat/kit/conversation/transcript/TranscriptManager.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/WfcUIKit.java`（`init` 第 76 行起；`onReceiveMessage` 第 221 行）

**Interfaces:**
- Consumes: Task 2 `ChatManager.Instance().speechToText`；Task 3 `TranscriptEngine`、`VoiceTranscriptRules.isRealtime`
- Produces: `TranscriptManager.init(Context)`、`TranscriptManager.get(): TranscriptManager`、`engine(): TranscriptEngine`、`changedLiveData(): LiveData<Long>`（值为变化的 messageUid）、`onReceiveMessages(List<Message>)`

- [ ] **Step 1: 实现 TranscriptManager**

```java
package cn.wildfire.chat.kit.conversation.transcript;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.SoundMessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.remote.ChatManager;
import cn.wildfirechat.remote.GeneralCallback2;

/**
 * UI 进程单例：SharedPreferences 持久化，AIDL 回调切回主线程，变化通过 LiveData 通知会话页刷新。
 */
public class TranscriptManager {
    private static final String PREFS = "voice_transcript";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_AUTO = "auto";

    private static TranscriptManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Long> changed = new MutableLiveData<>();
    private final TranscriptEngine engine;

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new TranscriptManager(context.getApplicationContext());
        }
    }

    public static TranscriptManager get() {
        return instance;
    }

    private TranscriptManager(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        engine = new TranscriptEngine(
                (uid, cb) -> ChatManager.Instance().speechToText(uid, new GeneralCallback2() {
                    @Override
                    public void onSuccess(String text) {
                        mainHandler.post(() -> cb.onSuccess(text));
                    }

                    @Override
                    public void onFail(int errorCode) {
                        mainHandler.post(() -> cb.onFail(errorCode));
                    }
                }),
                mainHandler::postDelayed,
                new TranscriptEngine.Persistence() {
                    @Override
                    public Map<Long, String[]> loadDone() {
                        Map<Long, String[]> out = new HashMap<>();
                        try {
                            JSONObject json = new JSONObject(sp.getString(KEY_ENTRIES, "{}"));
                            Iterator<String> keys = json.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                JSONArray v = json.getJSONArray(k);
                                out.put(Long.parseLong(k), new String[]{v.getString(0), v.getString(1)});
                            }
                        } catch (Exception e) {
                            // 数据损坏时丢弃，不影响使用
                        }
                        return out;
                    }

                    @Override
                    public void saveDone(Map<Long, String[]> done) {
                        JSONObject json = new JSONObject();
                        try {
                            for (Map.Entry<Long, String[]> e : done.entrySet()) {
                                json.put(String.valueOf(e.getKey()), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
                            }
                        } catch (Exception ignored) {
                        }
                        sp.edit().putString(KEY_ENTRIES, json.toString()).apply();
                    }

                    @Override
                    public boolean loadAuto() {
                        return sp.getBoolean(KEY_AUTO, false);
                    }

                    @Override
                    public void saveAuto(boolean on) {
                        sp.edit().putBoolean(KEY_AUTO, on).apply();
                    }
                },
                changed::setValue);
    }

    public TranscriptEngine engine() {
        return engine;
    }

    public LiveData<Long> changedLiveData() {
        return changed;
    }

    /** WfcUIKit.onReceiveMessage 在主线程调用：实时到达的他人语音交给自动队列 */
    public void onReceiveMessages(List<Message> messages) {
        if (messages == null || !engine.isAutoTranscribe()) {
            return;
        }
        long now = System.currentTimeMillis();
        long delta = ChatManager.Instance().getServerDeltaTime();
        for (Message m : messages) {
            if (m.direction == MessageDirection.Receive
                    && m.content instanceof SoundMessageContent
                    && m.messageUid > 0
                    && VoiceTranscriptRules.isRealtime(m.serverTime, delta, now)) {
                engine.enqueueAuto(m.messageUid);
            }
        }
    }
}
```

- [ ] **Step 2: 确认 onReceiveMessage 线程**

Run: `grep -n "onReceiveMessage(" client/src/main/java/cn/wildfirechat/remote/ChatManager.java | head`
查看 `ChatManager` 分发 `OnReceiveMessageListener` 的位置是否在 `mainHandler.post(...)` 内。Expected: 在主线程分发。若不在主线程，`onReceiveMessages` 内部改为 `mainHandler.post(() -> ...)` 包一层后再调用 `engine.enqueueAuto`。

- [ ] **Step 3: WfcUIKit 接入**

`WfcUIKit.java`：

1. `init(Application application)` 方法体开头加 `TranscriptManager.init(application);`，并 import `cn.wildfire.chat.kit.conversation.transcript.TranscriptManager`。
2. `onReceiveMessage(List<Message> messages, boolean hasMore)` 方法体第一行（`Log.i(...)` 之后）加：

```java
        TranscriptManager.get().onReceiveMessages(messages);
```

- [ ] **Step 4: 编译**

Run: `./gradlew :chat:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add chat/src/main/java/cn/wildfire/chat/kit/conversation/transcript/TranscriptManager.java chat/src/main/java/cn/wildfire/chat/kit/WfcUIKit.java
git commit -m "feat(transcript): TranscriptManager 持久化与实时语音自动识别"
```

---

### Task 5: 语音气泡 UI（按钮、文字块、长按菜单、会话页刷新）

**Files:**
- Modify: `chat/src/main/res/layout/conversation_item_audio_receive.xml`
- Modify: `chat/src/main/res/layout/conversation_item_audio_send.xml`
- Create: `chat/src/main/res/drawable/shape_voice_pill.xml`
- Create: `chat/src/main/res/drawable/shape_voice_transcript.xml`
- Modify: `chat/src/main/res/values/colors.xml`、`chat/src/main/res/values-night/colors.xml`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/MessageContextMenuItemTags.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/AudioMessageContentViewHolder.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationViewModel.java`（`playAudioMessage` 第 300 行附近）
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationMessageAdapter.java`（新增方法）
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationActivity.java`（第 389 行注册、第 663 行注销 observer）

**Interfaces:**
- Consumes: Task 3 `VoiceTranscriptRules`、Task 4 `TranscriptManager`
- Produces: `ConversationViewModel.markAudioPlayed(UiMessage)`；`ConversationMessageAdapter.notifyMessageUidChanged(long uid)`

显示规则已在 Task 3 单测覆盖；本 Task 为 UI 接线，验证为编译 + Task 7 手动走查。

- [ ] **Step 1: 颜色与 drawable**

`values/colors.xml` 的 `</resources>` 前加：

```xml
    <color name="voice_transcript_bg">#F0F1F3</color>
    <color name="voice_transcript_text">#222222</color>
    <color name="voice_pill_bg">#E6E7EA</color>
    <color name="voice_pill_text">#555555</color>
```

`values-night/colors.xml` 的 `</resources>` 前加：

```xml
    <color name="voice_transcript_bg">#2A2B2F</color>
    <color name="voice_transcript_text">#E6E6E6</color>
    <color name="voice_pill_bg">#34353A</color>
    <color name="voice_pill_text">#B8B8B8</color>
```

`drawable/shape_voice_pill.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/voice_pill_bg" />
    <corners android:radius="12dp" />
</shape>
```

`drawable/shape_voice_transcript.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/voice_transcript_bg" />
    <corners android:radius="6dp" />
</shape>
```

- [ ] **Step 2: 接收布局**

把 `conversation_item_audio_receive.xml` 整体替换为（原有一行控件包进内层横向 LinearLayout，id 不变）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:orientation="horizontal">

        <RelativeLayout
            android:id="@+id/audioContentLayout"
            android:layout_width="65dp"
            android:layout_height="40dp"
            android:background="@drawable/shape_bubble_receive"
            android:paddingLeft="12dp"
            android:paddingTop="8dp"
            android:paddingRight="12dp"
            android:paddingBottom="8dp">

            <ImageView
                android:id="@+id/audioImageView"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_alignParentLeft="true"
                android:layout_centerVertical="true"
                android:background="@drawable/audio_animation_left_list" />

        </RelativeLayout>

        <TextView
            android:id="@+id/durationTextView"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginLeft="8dp"
            android:text="6''"
            android:textColor="@color/mist" />

        <View
            android:id="@+id/playStatusIndicator"
            android:layout_width="8dp"
            android:layout_height="8dp"
            android:layout_marginLeft="8dp"
            android:background="@drawable/shape_red_dot" />

        <TextView
            android:id="@+id/transcribePillTextView"
            android:layout_width="wrap_content"
            android:layout_height="24dp"
            android:layout_marginLeft="8dp"
            android:background="@drawable/shape_voice_pill"
            android:gravity="center"
            android:paddingLeft="10dp"
            android:paddingRight="10dp"
            android:text="转文字"
            android:textColor="@color/voice_pill_text"
            android:textSize="12sp"
            android:visibility="gone" />
    </LinearLayout>

    <TextView
        android:id="@+id/transcriptTextView"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:background="@drawable/shape_voice_transcript"
        android:maxWidth="240dp"
        android:paddingLeft="12dp"
        android:paddingTop="8dp"
        android:paddingRight="12dp"
        android:paddingBottom="8dp"
        android:textColor="@color/voice_transcript_text"
        android:textIsSelectable="true"
        android:textSize="14sp"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 3: 发送布局**

把 `conversation_item_audio_send.xml` 整体替换为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="right"
    android:gravity="right"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/durationTextView"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_vertical"
            android:layout_marginRight="8dp"
            android:text="6''"
            android:textColor="@color/mist" />

        <RelativeLayout
            android:id="@+id/audioContentLayout"
            android:layout_width="100dp"
            android:layout_height="40dp"
            android:background="@drawable/shape_bubble_send"
            android:paddingLeft="12dp"
            android:paddingTop="8dp"
            android:paddingRight="12dp"
            android:paddingBottom="8dp">

            <ImageView
                android:id="@+id/audioImageView"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_alignParentRight="true"
                android:layout_centerInParent="true"
                android:background="@drawable/audio_animation_right_list" />

        </RelativeLayout>
    </LinearLayout>

    <TextView
        android:id="@+id/transcriptTextView"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:background="@drawable/shape_voice_transcript"
        android:maxWidth="240dp"
        android:paddingLeft="12dp"
        android:paddingTop="8dp"
        android:paddingRight="12dp"
        android:paddingBottom="8dp"
        android:textColor="@color/voice_transcript_text"
        android:textIsSelectable="true"
        android:textSize="14sp"
        android:visibility="gone" />
</LinearLayout>
```

（原文件中 `layout_toLeftOf` / `layout_alignParentRight` 是 RelativeLayout 属性，在 LinearLayout 中本就无效，此处去掉。）

- [ ] **Step 4: 菜单 tag**

`MessageContextMenuItemTags.java` 追加：

```java
    String TAG_TRANSCRIBE = "transcribe";
    String TAG_HIDE_TRANSCRIPT = "hideTranscript";
```

- [ ] **Step 5: ConversationViewModel 抽出 markAudioPlayed**

`ConversationViewModel.playAudioMessage(UiMessage message)` 中把

```java
        if (message.message.direction == MessageDirection.Receive && message.message.status != MessageStatus.Played) {
            message.message.status = MessageStatus.Played;
            ChatManager.Instance().setMediaMessagePlayed(message.message.messageId);
        }
```

替换为 `markAudioPlayed(message);`，并在该方法之后新增：

```java
    /** 播放或点击「转文字」后置为已播放（消红点） */
    public void markAudioPlayed(UiMessage message) {
        if (message.message.direction == MessageDirection.Receive && message.message.status != MessageStatus.Played) {
            message.message.status = MessageStatus.Played;
            ChatManager.Instance().setMediaMessagePlayed(message.message.messageId);
            postMessageUpdate(message);
        }
    }
```

- [ ] **Step 6: Adapter 按 messageUid 刷新**

`ConversationMessageAdapter.java` 在 `updateMessage(UiMessage message)` 之后加：

```java
    /** 转文字结果变化：按 messageUid 找到气泡并重绘 */
    public void notifyMessageUidChanged(long messageUid) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).message.messageUid == messageUid) {
                notifyItemChanged(i);
                return;
            }
        }
    }
```

- [ ] **Step 7: ConversationActivity 订阅**

`ConversationActivity.java`：

1. 字段区（`messageUpdateLiveDatObserver` 定义之后）加：

```java
    private Observer<Long> transcriptChangedObserver = uid -> {
        if (uid != null) {
            adapter.notifyMessageUidChanged(uid);
        }
    };
```

2. 第 389 行 `conversationViewModel.messageUpdateLiveData().observeForever(messageUpdateLiveDatObserver);` 之后加：

```java
            TranscriptManager.get().changedLiveData().observeForever(transcriptChangedObserver);
```

3. 第 663 行 `conversationViewModel.messageUpdateLiveData().removeObserver(messageUpdateLiveDatObserver);` 之后加：

```java
        TranscriptManager.get().changedLiveData().removeObserver(transcriptChangedObserver);
```

4. import `cn.wildfire.chat.kit.conversation.transcript.TranscriptManager`。

- [ ] **Step 8: AudioMessageContentViewHolder**

1. 字段区 `playStatusIndicator` 之后加：

```java
    @Nullable
    @Bind(R.id.transcribePillTextView)
    TextView transcribePillTextView;
    @Bind(R.id.transcriptTextView)
    TextView transcriptTextView;
```

2. `onBind` 中，把

```java
        if (message.message.direction == MessageDirection.Receive) {
            if (message.message.status != MessageStatus.Played) {
                playStatusIndicator.setVisibility(View.VISIBLE);
            } else {
                playStatusIndicator.setVisibility(View.GONE);
            }
        }
```

之后追加：

```java
        bindTranscript(message);
```

3. 类中新增：

```java
    private void bindTranscript(UiMessage message) {
        TranscriptEngine engine = TranscriptManager.get().engine();
        long uid = message.message.messageUid;
        TranscriptEngine.Entry entry = engine.get(uid);
        boolean incoming = message.message.direction == MessageDirection.Receive;
        boolean played = message.message.status == MessageStatus.Played;

        if (transcribePillTextView != null) {
            boolean showPill = VoiceTranscriptRules.showPill(incoming, played, uid, entry, engine.isAutoTranscribe());
            transcribePillTextView.setVisibility(showPill ? View.VISIBLE : View.GONE);
        }

        switch (VoiceTranscriptRules.block(entry)) {
            case LOADING:
                showTranscript("转文字中…", R.color.mist, false);
                break;
            case ERROR:
                showTranscript("转文字失败，点击重试", R.color.coral, true);
                break;
            case EMPTY:
                showTranscript("未识别到文字", R.color.mist, false);
                break;
            case TEXT:
                showTranscript(entry.text, R.color.voice_transcript_text, false);
                break;
            default:
                transcriptTextView.setVisibility(View.GONE);
                transcriptTextView.setOnClickListener(null);
                break;
        }
    }

    private void showTranscript(String text, int colorRes, boolean retryOnClick) {
        transcriptTextView.setVisibility(View.VISIBLE);
        transcriptTextView.setText(text);
        transcriptTextView.setTextColor(ContextCompat.getColor(context, colorRes));
        if (retryOnClick) {
            transcriptTextView.setOnClickListener(v -> TranscriptManager.get().engine().request(message.message.messageUid));
        } else {
            transcriptTextView.setOnClickListener(null);
        }
    }

    /** 点「转文字」视为已读：消红点 */
    private void transcribe(UiMessage message) {
        conversationViewModel.markAudioPlayed(message);
        TranscriptManager.get().engine().request(message.message.messageUid);
    }

    @Nullable
    @OnClick(R.id.transcribePillTextView)
    public void onTranscribePillClick(View view) {
        transcribe(message);
    }

    @MessageContextMenuItem(tag = MessageContextMenuItemTags.TAG_TRANSCRIBE, title = "转文字", icon = R.drawable.ic_menu_copy, priority = 9)
    public void transcribeFromMenu(View itemView, UiMessage message) {
        transcribe(message);
    }

    @MessageContextMenuItem(tag = MessageContextMenuItemTags.TAG_HIDE_TRANSCRIPT, title = "隐藏文字", icon = R.drawable.ic_menu_clear, priority = 9)
    public void hideTranscript(View itemView, UiMessage message) {
        TranscriptManager.get().engine().hide(message.message.messageUid);
    }

    @Override
    public boolean contextMenuItemFilter(UiMessage uiMessage, String tag) {
        // 返回 true 表示过滤掉（不显示）该菜单项
        long uid = uiMessage.message.messageUid;
        TranscriptEngine.Entry entry = TranscriptManager.get().engine().get(uid);
        if (MessageContextMenuItemTags.TAG_TRANSCRIBE.equals(tag)) {
            return !VoiceTranscriptRules.showTranscribeMenu(uid, entry);
        }
        if (MessageContextMenuItemTags.TAG_HIDE_TRANSCRIPT.equals(tag)) {
            return !VoiceTranscriptRules.showHideMenu(uid, entry);
        }
        return super.contextMenuItemFilter(uiMessage, tag);
    }
```

4. import：`androidx.core.content.ContextCompat`、`cn.wildfire.chat.kit.annotation.MessageContextMenuItem`、`cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine`、`cn.wildfire.chat.kit.conversation.transcript.TranscriptManager`、`cn.wildfire.chat.kit.conversation.transcript.VoiceTranscriptRules`。

（`context` / `message` / `conversationViewModel` 为父类 `MessageContentViewHolder` 已有字段，`NormalMessageContentViewHolder` 中 `recall` 等方法即如此使用。若 `@Nullable @OnClick` 组合在本项目 ButterKnife 版本不被支持，去掉 `@OnClick`，改为在 `bindTranscript` 里 `transcribePillTextView.setOnClickListener(v -> transcribe(message))`。）

- [ ] **Step 9: 编译与单测**

Run: `./gradlew :chat:assembleDebug :chat:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，测试全部通过。

- [ ] **Step 10: Commit**

```bash
git add chat/src/main/res chat/src/main/java/cn/wildfire/chat/kit/conversation
git commit -m "feat(chat): 语音气泡转文字按钮、文字块与长按菜单"
```

---

### Task 6: 设置页「自动转文字」开关

**Files:**
- Modify: `chat/src/main/res/layout/setting_activity.xml`（「主题设置」所在 LinearLayout 之后插入新分组）
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/setting/SettingActivity.java`

**Interfaces:**
- Consumes: Task 3 `TranscriptEngine.isAutoTranscribe / setAutoTranscribe`；Task 4 `TranscriptManager.get()`

- [ ] **Step 1: 布局**

在 `setting_activity.xml` 中包含 `themeSettingOptionItemView` 的 `</LinearLayout>` 之后插入（`SwitchButton` 属性与 `conversation_info_single_fragment.xml` 的 `silentSwitchButton` 一致）：

```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:layout_marginTop="@dimen/option_item_margin_top"
        android:background="@color/white"
        android:gravity="center_vertical">

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginLeft="@dimen/option_item_margin_left"
            android:layout_weight="1"
            android:text="自动转文字"
            android:textColor="@color/black0"
            android:textSize="16sp" />

        <com.kyleduo.switchbutton.SwitchButton
            android:id="@+id/autoTranscribeSwitchButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginRight="@dimen/option_item_margin_right"
            app:kswBackDrawable="@drawable/ios_back_drawable"
            app:kswBackMeasureRatio="1.8"
            app:kswThumbDrawable="@drawable/ios_thumb_selector"
            app:kswThumbMarginBottom="-4dp"
            app:kswThumbMarginLeft="-2dp"
            app:kswThumbMarginRight="-2dp"
            app:kswThumbMarginTop="-0dp"
            app:kswThumbWidth="10dp" />
    </LinearLayout>
```

- [ ] **Step 2: SettingActivity**

```java
    @Bind(R.id.autoTranscribeSwitchButton)
    SwitchButton autoTranscribeSwitchButton;

    @Override
    protected void afterViews() {
        super.afterViews();
        TranscriptEngine engine = TranscriptManager.get().engine();
        autoTranscribeSwitchButton.setCheckedImmediatelyNoEvent(engine.isAutoTranscribe());
        autoTranscribeSwitchButton.setOnCheckedChangeListener((buttonView, isChecked) -> engine.setAutoTranscribe(isChecked));
    }
```

import：`butterknife.Bind`、`com.kyleduo.switchbutton.SwitchButton`、`cn.wildfire.chat.kit.conversation.transcript.TranscriptEngine`、`cn.wildfire.chat.kit.conversation.transcript.TranscriptManager`。

（先 `grep -n "afterViews\|protected void" chat/src/main/java/cn/wildfire/chat/kit/WfcBaseActivity.java` 确认基类的初始化钩子名称；若不是 `afterViews`，用基类实际提供的视图初始化回调。）

- [ ] **Step 3: 编译**

Run: `./gradlew :chat:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add chat/src/main/res/layout/setting_activity.xml chat/src/main/java/cn/wildfire/chat/kit/setting/SettingActivity.java
git commit -m "feat(setting): 自动转文字开关"
```

---

### Task 7: 手动走查（依赖服务端已部署并开启 `push.asr.enabled`）

- [ ] **Step 1: 安装**

Run: `./gradlew :chat:assembleDebug` 并 `adb install -r chat/build/outputs/apk/debug/*.apk`；用 `13800000001` 登录，另一端 `13800000000`。

- [ ] **Step 2: 走查清单**

1. 对方发语音 → 气泡右侧红点 + 「转文字」按钮。
2. 点「转文字」→ 红点与按钮消失，下方「转文字中…」→ 出文字（不崩溃，验证回调已切主线程）。
3. 长按 → 只有「隐藏文字」；点击后文字块消失；再长按只有「转文字」，点击立即恢复（logcat 中无新的 `speechToText message Uid`）。
4. 新语音直接点播放 → 红点和按钮消失；长按「转文字」能出文字。
5. 设置 → 打开「自动转文字」→ 新语音自动出文字，红点保留，无按钮。
6. 飞行模式下点「转文字」→ 15 秒后显示「转文字失败，点击重试」；恢复网络点击可重试成功。
7. 杀进程重启 → 已转文字仍在，隐藏状态与开关保持。
8. 深色主题下按钮与文字块颜色正常。
9. 自己发的语音长按 → 有「转文字」，可转出文字显示在右侧气泡下方。
