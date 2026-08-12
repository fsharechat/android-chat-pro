# 用户设置多端同步（android-chat-pro）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 android 端把用户保存的群组、会话置顶、会话免打扰通过 `UG`/`UP`/`UN` 三个信令同步到服务端，重新登录时拉回来，多端在线时实时互通。

**Architecture:** 服务端已在 2.2.12-SNAPSHOT 打通这三个信令。android 侧的写入链路（`ClientService` → `JavaProtoLogic.setUserSetting` → `ProtoMessageDataStore`）已经存在但只写本地内存、从不发网络，且 key 格式与服务端不一致。本计划先把 key 格式统一到服务端的 `type-line-target` 并修掉写入层对非会话 scope 的崩溃，再补 UG/UP 两个网络方法与 UG/UN 两个 handler，最后把登录流程接上。群列表页与「保存到通讯录」开关的 UI 完全不用改——它们本来就读写 scope 6。

**Tech Stack:** Java 8、Gradle、Android library 模块、JUnit 4、protobuf 2.5.0。

**设计文档:** `chat-server-pro` 仓库的 `docs/superpowers/specs/2026-08-12-user-setting-sync-design.md`

**服务端依赖:** 需要服务端 2.2.12-SNAPSHOT（`push-group` + `push-connector` 都要更新，且 `push-group` 必须先上）已部署到联调环境，否则 UG/UP 会收到未知 topic 的错误码。

## Global Constraints

- **不要自动 commit。** 每个任务末尾只做「跑测试 + `git add`」，commit 由仓库所有者自己执行。本计划所有 `git add` 步骤后面都不得跟 `git commit`。
- **必须用 JDK 11 构建**：所有 `gradlew` 命令加前缀 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home`。
- **仓库路径**：`/Users/liaojinlong/Share/GitWorkplace/github/fshare/android-chat-pro`。`chat-server-pro` 是**另一个独立 git 工作区**，本计划不改它，也不能把它的文件放进本仓库的 `git add`。
- **`SubSignal` 只允许在枚举末尾追加**，禁止插入或重排。它的 ordinal 是二进制协议的一部分，与 `chat-server-pro/push-common`、`ios-chat-pro/Sources/IMTransport/SubSignal.swift` 逐位对齐。改动前末位是 `SAI = 40`，新增固定为 `UG = 41`、`UP = 42`、`UN = 43`。
- **会话类 setting 的 key 格式固定为 `type-line-target`**（连字符，如 `1-0-groupId`），与服务端 `MemoryMessagesStore.getUserConversationSlient()` 的读取格式一致。**现有代码用的是冒号（`USER_SETTING_SPLIT_TAG = ":"`），必须改成连字符**，否则上传的 key 服务端认不出。
- **取消操作写 value `"0"`**，不删除本地记录，与服务端的 tombstone 语义一致；无论是本地取消写入，还是从服务端拉回 `"0"`，都只是把它当普通值存下，不删除该条记录。读取方一律以「是否等于 `"1"`」判断是否生效，其余值（含 `"0"`）都视为未设置。
- **scope 取值**（`cn.wildfirechat.remote.UserSettingScope`，已存在，不要改）：`FavoriteGroup = 6` 保存到通讯录的群组（key 为 groupId），`ConversationTop = 3` 会话置顶，`ConversationSilent = 1` 会话免打扰，`GlobalSilent = 2` 全局免打扰（key 为空串）。
- **不修改 protobuf**。`client/src/main/java/cn/wildfirechat/proto/WFCMessage.java` 是 protobuf 2.x 生成后直接提交的。本计划用到的 `WFCMessage.Version`、`WFCMessage.ModifyUserSettingReq`、`WFCMessage.GetUserSettingResult`、`WFCMessage.UserSettingEntry`、`WFCMessage.ConnectAckPayload.getSettingHead()` **全部已存在**，直接用。
- **本地 version 与本地设置必须同生共死。** 设置存在 `ProtoMessageDataStore.userSettingMap`（纯内存，进程退出即丢），所以 version 也必须放在同一个对象里、绝不能持久化到 SharedPreferences——否则重启后 version 还在、设置没了，客户端会误判「无需拉取」而永久丢失设置。

---

## 现状（已逐一核实，实现前不必重新调研）

- **生效的 store 是 `ProtoMessageDataStore`**：`DataStoreFactory:440-452` 把 `setUserSetting`/`getUserSetting`/`getUserSettings` 三个方法全部委托给 `protoMessageDataStore`。`ImMemoryStoreImpl` 里的同名实现在这条链路上是死代码。
- **`ProtoMessageDataStore.userSettingMap`（`:37`）是纯内存 `ConcurrentHashMap`**，该类虽然 `extends SqliteDatabaseStore`，但用户设置没有落库。这正是「重登丢群」的根因。
- **`JavaProtoLogic.setUserSetting():698` 只写本地，从不发网络。**
- **`JavaProtoLogic.setConversationTop():492` 与 `setConversationSilent():500` 是死代码**，全仓库零调用方（`setConversationTop` 还打了个恒为 `false` 的日志）。真实链路是 `ClientService:550-562`，它把两者都转成 `setUserSetting(scope, key, "1"/"0", null)`。
- **key 格式三方不一致**：写侧 `ClientService:551/561` 用 `USER_SETTING_SPLIT_TAG`（`Constants.java:4` = `":"`）拼成 `type:line:target`；死代码读侧 `ImMemoryStoreImpl.conversationSetting():341` 用连字符且把 line 硬编码为 0；服务端用连字符。
- **`ProtoMessageDataStore.setUserSetting():600-612` 对 scope 6 会崩溃**：它对任何非空 key 无条件 `key.split(USER_SETTING_SPLIT_TAG)` 然后 `Integer.parseInt(keys[0])`。scope 6 的 key 是群 id，`parseInt` 抛 `NumberFormatException`；scope 2 的 key 是空串，被 `!TextUtils.isEmpty(key)` 挡住反而没事。
- **UI 侧不用改**：`ChatManager.getMyGroups():3102` 已经读 scope 6；群列表页 `GroupListFragment:57` 调它；群详情页「保存到通讯录」开关 `conversation_info_group_fragment.xml:226` → `GroupConversationInfoFragment:406` → `GroupViewModel.setFavGroup():271` 写的也是 scope 6。
- **`client` 模块没有 `src/test` 目录，`build.gradle` 也没有测试依赖**；`push-sdk` 有 `testImplementation 'junit:junit:4.12'` 和现成的 `RoundRobinHostSelectorTest`。
- **`ConnectAckMessageHandler` 目前忽略 `settingHead`**，只用 `friendHead` 和 `msgHead`。
- 发送 API 在 `AbstractProtoService`：`sendMessage(Signal, SubSignal, byte[], Object callback)`（`:412`）与 `SimpleFuture sendMessageSync(Signal, SubSignal, byte[])`（`:608`），后者把 future 放进 `futureMap`，由 handler 按 messageId 取出。

---

## 杀进程重开后的恢复时序（本计划要达成的目标状态）

实现前先把这条链路在脑子里跑通，后面几个任务的顺序和取舍都是由它决定的。

冷启动进入 `ConnectAckMessageHandler.processMessage`：

```
本地 userSettingVersion = 0（内存 store，重启必然归零）
CONNECT_ACK 带回 settingHead = 服务端该用户全部设置里最大的 updateDt
  settingHead > 0  →  pullUserSettings(0)   即全量拉取
  settingHead == 0 →  跳过（服务端确实没有任何设置）
        ↓
UG 应答 → UserSettingPullHandler 逐条 setUserSetting(scope, key, value)
        ↓                                      ↓
  写进内存 userSettingMap            scope 3/1 顺带 updateConversation()
                                     把 isTop/isSilent 写回会话 BLOB
        ↓
setUserSettingVersion(本批最大 updateDt)
```

**关键：`pullUserSettings` 必须排在 `pullMessage` 之前**（Task 4 Step 3 就是这么插的）。原因见下面场景 B。

### 场景 A：杀进程重开，本地 SQLite 还在

- **置顶 / 免打扰**：会话行还在，BLOB 里的 `isTop`/`isSilent` 本来就在，**不等 UG 就已经显示正确**。UG 回来后 `updateConversation` 再确认一遍，结果一致。
- **群组列表**：scope 6 只存在内存 map，重启后必然为空，**只能靠 UG 恢复**。UG 写回 map 后，`ChatManager.getMyGroups()` 下次被调用（打开通讯录「我的群组」）即可读到。

### 场景 B：重装 / 换设备，SQLite 是空的

- 会话列表要靠拉消息重建。UG 先执行，把设置灌进内存 map；随后 `pullMessage` 拉回消息、`newProtoConversation()`（`ProtoMessageDataStore:489-499`）建会话对象时会调 `conversationSetting(ConversationTop/ConversationSilent, type, target)` 从内存 map 读取——**这条读路用的是连字符 key，正是 Task 2 统一分隔符之后才会真正命中的那条**。所以新建出来的会话直接带着正确的置顶/免打扰状态。
- 如果顺序反过来（先拉消息再拉设置），会话建出来时 map 还是空的，全部是未置顶未免打扰；等 UG 回来 `updateConversation` 虽然能补救，但中间有一段界面状态是错的。
- **群名称、头像**：`ChatManager.getMyGroups():3117` 对每个群 id 调 `getGroupInfo(groupId, false)`，而 `ProtoService.getGroupInfo():402` 在本地缓存缺失时会自动发 GPGI 向服务端拉取，所以群信息不需要本计划做任何额外工作。

### 已知的恢复期特征（不是缺陷，但要知道）

- `getGroupInfo` 的服务端回源是**同步调用、每个群各自 500ms 超时**（`ProtoService:406-411`），且 `getMyGroups()` 是逐个串行调的。恢复 20 个已保存的群，最坏情况在 work handler 上耗时约 10 秒。首次进入「我的群组」页可能有可感知的等待，之后走内存缓存就快了。若将来要优化，方向是把 `getMyGroups` 改成批量 GPGI（`PullUserRequest` 本身支持一次带多个 uid），不在本计划范围内。
- UG 写回本地后**不会主动通知 UI 刷新**。群列表页 `GroupListFragment:57` 是 `onResume` 时主动调 `getMyGroups()` 的，所以进入页面就能看到；但如果用户恰好停在该页面且 UG 在此之后才返回，需要退出重进。这个时序窗口很窄（UG 在 CONNECT_ACK 后立刻发出），本计划不额外加刷新通知。

---

### Task 1: `SubSignal` 追加 UG / UP / UN 并锁定 ordinal 契约

**Files:**
- Modify: `push-sdk/src/main/java/com/comsince/github/push/SubSignal.java:51`
- Test: `push-sdk/src/test/java/com/comsince/github/push/SubSignalOrdinalTest.java`（新建）

**Interfaces:**
- Produces: `SubSignal.UG`（ordinal 41）、`SubSignal.UP`（42）、`SubSignal.UN`（43），供后续任务的 handler 与发送方法使用。

- [ ] **Step 1: 写失败的测试**

创建 `push-sdk/src/test/java/com/comsince/github/push/SubSignalOrdinalTest.java`：

```java
package com.comsince.github.push;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * SubSignal 的 ordinal 是二进制协议的一部分，与服务端 push-common、ios 的 SubSignal.swift 逐位对齐。
 * 只允许在末尾追加；任何插入或重排都会让线上客户端解析错位。
 */
public class SubSignalOrdinalTest {

    @Test
    public void existingOrdinalsAreNotShifted() {
        assertEquals(0, SubSignal.NONE.ordinal());
        assertEquals(9, SubSignal.US.ordinal());
        assertEquals(22, SubSignal.GPGI.ordinal());
        assertEquals(40, SubSignal.SAI.ordinal());
    }

    @Test
    public void userSettingSignalsAreAppendedAtTheEnd() {
        assertEquals(41, SubSignal.UG.ordinal());
        assertEquals(42, SubSignal.UP.ordinal());
        assertEquals(43, SubSignal.UN.ordinal());
    }

    @Test
    public void toEnumResolvesUserSettingOrdinals() {
        assertEquals(SubSignal.UG, SubSignal.toEnum(41));
        assertEquals(SubSignal.UP, SubSignal.toEnum(42));
        assertEquals(SubSignal.UN, SubSignal.toEnum(43));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :push-sdk:testDebugUnitTest --tests '*SubSignalOrdinalTest*'
```

Expected: 编译失败，`cannot find symbol: variable UG`

- [ ] **Step 3: 追加枚举值**

`push-sdk/src/main/java/com/comsince/github/push/SubSignal.java`，把第 51-52 行的

```java
    SAI
    ;
```

改为

```java
    SAI,
    // 用户设置同步：UG 拉取、UP 上报、UN 服务端通知。
    // 只允许在末尾追加，ordinal 与服务端 push-common、ios SubSignal.swift 对齐
    UG,
    UP,
    UN
    ;
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :push-sdk:testDebugUnitTest --tests '*SubSignalOrdinalTest*'
```

Expected: `3 tests completed, 0 failed`

- [ ] **Step 5: git add（不要 commit）**

```bash
git add push-sdk/src/main/java/com/comsince/github/push/SubSignal.java \
        push-sdk/src/test/java/com/comsince/github/push/SubSignalOrdinalTest.java
```

---

### Task 2: 统一 key 格式并修掉非会话 scope 的写入崩溃

这是整个功能的正确性地基：key 格式不统一，同步上去服务端认不出；`setUserSetting` 对 scope 6 崩溃，「保存到通讯录」根本走不到网络层。

**Files:**
- Create: `client/src/main/java/cn/wildfirechat/proto/UserSettingKeys.java`
- Test: `client/src/test/java/cn/wildfirechat/proto/UserSettingKeysTest.java`（新建，`client` 模块此前没有 test 源集）
- Modify: `client/build.gradle:53-63`（加测试依赖）
- Modify: `client/src/main/java/cn/wildfirechat/Constants.java:4`
- Modify: `client/src/main/java/cn/wildfirechat/client/ClientService.java:550-562`
- Modify: `client/src/main/java/cn/wildfirechat/proto/store/ProtoMessageDataStore.java:592-613`
- Modify: `client/src/main/java/cn/wildfirechat/proto/store/ImMemoryStoreImpl.java:339-347`

**Interfaces:**
- Produces:
  - `static String UserSettingKeys.conversationKey(int conversationType, int line, String target)` → `"type-line-target"`
  - `static boolean UserSettingKeys.isConversationScope(int scope)` → scope 是 3 或 1 时为 true
  - `static String[] UserSettingKeys.parseConversationKey(String key)` → 长度 3 的 `{type, line, target}`，格式不合法时返回 `null`
  - `Constants.USER_SETTING_SPLIT_TAG` 的值由 `":"` 改为 `"-"`

- [ ] **Step 1: 给 client 模块加测试依赖**

编辑 `client/build.gradle`，在 `dependencies {` 块内（第 54 行 `api fileTree(...)` 之后）加一行：

```gradle
    testImplementation 'junit:junit:4.12'
```

与 `push-sdk/build.gradle:23` 保持同一版本。

- [ ] **Step 2: 写失败的测试**

创建 `client/src/test/java/cn/wildfirechat/proto/UserSettingKeysTest.java`。注意：这是 JVM 单测，`UserSettingKeys` 里**不能**引用 `android.text.TextUtils` 等 Android 类，否则会抛 `RuntimeException: Stub!`。

```java
package cn.wildfirechat.proto;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UserSettingKeysTest {

    @Test
    public void conversationKeyUsesHyphenSeparatedTypeLineTarget() {
        // 必须与服务端 MemoryMessagesStore.getUserConversationSlient 的格式完全一致
        assertEquals("1-0-group123", UserSettingKeys.conversationKey(1, 0, "group123"));
        assertEquals("0-0-user456", UserSettingKeys.conversationKey(0, 0, "user456"));
    }

    @Test
    public void conversationKeyKeepsNonZeroLine() {
        assertEquals("1-3-group123", UserSettingKeys.conversationKey(1, 3, "group123"));
    }

    @Test
    public void onlyTopAndSilentAreConversationScopes() {
        assertTrue(UserSettingKeys.isConversationScope(3));   // ConversationTop
        assertTrue(UserSettingKeys.isConversationScope(1));   // ConversationSilent
        assertFalse(UserSettingKeys.isConversationScope(6));  // FavoriteGroup，key 是群 id
        assertFalse(UserSettingKeys.isConversationScope(2));  // GlobalSilent，key 是空串
    }

    @Test
    public void parseConversationKeyRoundTrips() {
        String[] parts = UserSettingKeys.parseConversationKey("1-0-group123");

        assertArrayEquals(new String[]{"1", "0", "group123"}, parts);
    }

    @Test
    public void parseConversationKeyReturnsNullForGroupIdKey() {
        // scope 6 的 key 是群 id，没有分隔符 —— 旧代码在这里 parseInt 崩溃
        assertNull(UserSettingKeys.parseConversationKey("group123"));
    }

    @Test
    public void parseConversationKeyReturnsNullForEmptyOrNull() {
        assertNull(UserSettingKeys.parseConversationKey(""));
        assertNull(UserSettingKeys.parseConversationKey(null));
    }

    @Test
    public void parseConversationKeyReturnsNullWhenTypeOrLineNotNumeric() {
        assertNull(UserSettingKeys.parseConversationKey("a-0-target"));
        assertNull(UserSettingKeys.parseConversationKey("1-b-target"));
    }

    @Test
    public void parseConversationKeyKeepsHyphensInsideTarget() {
        // 群 id 本身可能含连字符，只能按前两个分隔符切
        String[] parts = UserSettingKeys.parseConversationKey("1-0-abc-def-ghi");

        assertArrayEquals(new String[]{"1", "0", "abc-def-ghi"}, parts);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :client:testDebugUnitTest --tests '*UserSettingKeysTest*'
```

Expected: 编译失败，`cannot find symbol: class UserSettingKeys`

- [ ] **Step 4: 创建 `UserSettingKeys`**

`client/src/main/java/cn/wildfirechat/proto/UserSettingKeys.java`：

```java
package cn.wildfirechat.proto;

import cn.wildfirechat.remote.UserSettingScope;

/**
 * 用户设置 key 的唯一定义处。
 * 会话类 setting 的 key 格式为 type-line-target，必须与服务端
 * MemoryMessagesStore.getUserConversationSlient() 的拼法完全一致，
 * 否则上传的设置服务端读不到。
 */
public final class UserSettingKeys {

    private static final String SPLIT = "-";

    private UserSettingKeys() {
    }

    public static String conversationKey(int conversationType, int line, String target) {
        return conversationType + SPLIT + line + SPLIT + target;
    }

    /**
     * 只有置顶和会话免打扰的 key 是会话三元组。
     * scope 6 的 key 是群 id、scope 2 的 key 是空串，都不能按会话 key 解析。
     */
    public static boolean isConversationScope(int scope) {
        return scope == UserSettingScope.ConversationTop || scope == UserSettingScope.ConversationSilent;
    }

    /**
     * 解析会话 key，返回 {type, line, target}；不是合法会话 key 时返回 null。
     * target 里可能含连字符（群 id），因此只按前两个分隔符切分。
     */
    public static String[] parseConversationKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String[] parts = key.split(SPLIT, 3);
        if (parts.length != 3) {
            return null;
        }
        if (!isNumeric(parts[0]) || !isNumeric(parts[1])) {
            return null;
        }
        return parts;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :client:testDebugUnitTest --tests '*UserSettingKeysTest*'
```

Expected: `8 tests completed, 0 failed`

- [ ] **Step 6: 把分隔符常量改成连字符**

`client/src/main/java/cn/wildfirechat/Constants.java` 第 4 行：

```java
    public static final String USER_SETTING_SPLIT_TAG = "-";
```

（保留常量本身，`ProtoMessageDataStore` 的旧 import 不会因此报错；真正的格式定义已经收敛到 `UserSettingKeys`。）

- [ ] **Step 7: `ClientService` 改用 `UserSettingKeys`**

`client/src/main/java/cn/wildfirechat/client/ClientService.java`，把第 550-552 行与第 560-562 行改为：

```java
        @Override
        public void setConversationTop(int conversationType, String target, int line, boolean top) throws RemoteException {
            setUserSetting(ConversationTop, UserSettingKeys.conversationKey(conversationType, line, target), top ? "1" : "0", null);
        }
```

```java
        @Override
        public void setConversationSilent(int conversationType, String target, int line, boolean silent) throws RemoteException {
            setUserSetting(ConversationSilent, UserSettingKeys.conversationKey(conversationType, line, target), silent ? "1" : "0", null);
        }
```

在文件头部加 import：

```java
import cn.wildfirechat.proto.UserSettingKeys;
```

第 93 行的 `import static cn.wildfirechat.Constants.USER_SETTING_SPLIT_TAG;` 如果因此变成未使用，一并删掉。

- [ ] **Step 8: 修掉 `ProtoMessageDataStore.setUserSetting` 对非会话 scope 的崩溃**

`client/src/main/java/cn/wildfirechat/proto/store/ProtoMessageDataStore.java` 第 592-613 行整段替换为：

```java
    @Override
    public void setUserSetting(int scope, String key, String value) {
        Map<String,String> scopeMap = userSettingMap.get(scope);
        if(scopeMap == null){
            scopeMap = new HashMap<>();
        }
        scopeMap.put(key,value);
        userSettingMap.put(scope,scopeMap);

        // 只有置顶/免打扰的 key 是会话三元组，才需要回写会话对象。
        // scope 6 的 key 是群 id、scope 2 的 key 是空串，旧代码在这里 parseInt 会抛 NumberFormatException
        if(UserSettingKeys.isConversationScope(scope)){
            String[] keys = UserSettingKeys.parseConversationKey(key);
            if(keys == null){
                logger.e("illegal conversation setting key scope="+scope+" key="+key);
                return;
            }
            int conversationType = Integer.parseInt(keys[0]);
            int line = Integer.parseInt(keys[1]);
            String target = keys[2];
            ProtoConversationInfo updateProtoConversation = getConversation(conversationType,target,line);
            if(scope == ConversationTop){
                updateProtoConversation.setTop(value.equals("1"));
            } else if(scope == ConversationSilent){
                updateProtoConversation.setSilent(value.equals("1"));
            }
            updateConversation(conversationType,target,line,updateProtoConversation);
        }
    }
```

注意两处顺带修正：`updateConversation` 的第三个参数原本写死 `0`，现在传真实的 `line`；`value.equals("1") ? true : false` 简化为 `value.equals("1")`。

在文件头部加 import：

```java
import cn.wildfirechat.proto.UserSettingKeys;
```

代码里的 `logger` 是从父类 `SqliteDatabaseStore:15` 继承来的 `Log logger` 字段，不需要新声明。

- [ ] **Step 9: 让死代码读侧也用同一套 key**

`client/src/main/java/cn/wildfirechat/proto/store/ImMemoryStoreImpl.java` 第 339-347 行的 `conversationSetting` 改为：

```java
    private boolean conversationSetting(int scope,int conversationType,String target){
        boolean flag = false;
        String setting = getUserSetting(scope, UserSettingKeys.conversationKey(conversationType, 0, target));
        if(!TextUtils.isEmpty(setting)){
            flag = setting.equals("1");
        }
        return flag;
    }
```

这条链路当前是死代码（`DataStoreFactory` 把 userSetting 委托给了 `ProtoMessageDataStore`），改它只是防止将来有人切回这个 store 时踩同一个 key 不一致的坑。顺手去掉原来那行把 key 又拼一遍的日志。

在文件头部加 import：

```java
import cn.wildfirechat.proto.UserSettingKeys;
```

- [ ] **Step 10: 编译并回归**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :client:assembleDebug :push-sdk:testDebugUnitTest :client:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，`UserSettingKeysTest` 8 个、`SubSignalOrdinalTest` 3 个全过。

- [ ] **Step 11: git add（不要 commit）**

```bash
git add client/build.gradle \
        client/src/main/java/cn/wildfirechat/proto/UserSettingKeys.java \
        client/src/test/java/cn/wildfirechat/proto/UserSettingKeysTest.java \
        client/src/main/java/cn/wildfirechat/Constants.java \
        client/src/main/java/cn/wildfirechat/client/ClientService.java \
        client/src/main/java/cn/wildfirechat/proto/store/ProtoMessageDataStore.java \
        client/src/main/java/cn/wildfirechat/proto/store/ImMemoryStoreImpl.java
```

---

### Task 3: 本地 version 存储与 UG / UP 网络方法

**Files:**
- Modify: `client/src/main/java/cn/wildfirechat/proto/store/ImMemoryStore.java:72-74`（接口加两个方法）
- Modify: `client/src/main/java/cn/wildfirechat/proto/store/DataStoreAdapter.java`（默认实现）
- Modify: `client/src/main/java/cn/wildfirechat/proto/store/ProtoMessageDataStore.java:37`（真实实现，紧挨 `userSettingMap`）
- Modify: `client/src/main/java/cn/wildfirechat/proto/store/DataStoreFactory.java:440-452`（委托）
- Create: `client/src/main/java/cn/wildfirechat/proto/handler/UserSettingPullHandler.java`
- Create: `client/src/main/java/cn/wildfirechat/proto/handler/NotifyUserSettingHandler.java`
- Modify: `client/src/main/java/cn/wildfirechat/proto/ProtoService.java:74-93`（注册 handler）与文件末尾（新增两个方法）

**Interfaces:**
- Consumes: Task 1 的 `SubSignal.UG/UP/UN`；Task 2 的 `UserSettingKeys`
- Produces:
  - `long ImMemoryStore.getUserSettingVersion()` / `void ImMemoryStore.setUserSettingVersion(long version)`
  - `void ProtoService.pullUserSettings(long version)` —— 发 UG，异步，结果由 `UserSettingPullHandler` 落到本地 store
  - `void ProtoService.updateUserSetting(int scope, String key, String value, JavaProtoLogic.IGeneralCallback callback)` —— 发 UP

`ProtoMessageDataStore` 里 version 与 `userSettingMap` 是同一个对象的两个字段，一起生一起死。**不要**把 version 写进 SharedPreferences。

- [ ] **Step 1: 接口加两个方法**

`client/src/main/java/cn/wildfirechat/proto/store/ImMemoryStore.java`，在第 74 行 `Map<String, String> getUserSettings(int scope);` 之后加：

```java
    long getUserSettingVersion();
    void setUserSettingVersion(long version);
```

- [ ] **Step 2: `DataStoreAdapter` 加默认实现**

`client/src/main/java/cn/wildfirechat/proto/store/DataStoreAdapter.java`，在类内任意位置加：

```java
    @Override
    public long getUserSettingVersion() {
        return 0;
    }

    @Override
    public void setUserSettingVersion(long version) {
    }
```

`SqliteDatabaseStore` 是 `abstract` 且继承自 `DataStoreAdapter`，不需要单独实现。

- [ ] **Step 3: `ProtoMessageDataStore` 真实实现**

`client/src/main/java/cn/wildfirechat/proto/store/ProtoMessageDataStore.java`，在第 37 行 `userSettingMap` 声明之后加字段：

```java
    // 与 userSettingMap 同为纯内存，必须一起生一起死：
    // 若把 version 持久化而设置没持久化，重启后客户端会误判「无需拉取」而永久丢失设置
    private volatile long userSettingVersion = 0;
```

并在 `getUserSettings` 方法之后加：

```java
    @Override
    public long getUserSettingVersion() {
        return userSettingVersion;
    }

    @Override
    public void setUserSettingVersion(long version) {
        this.userSettingVersion = version;
    }
```

- [ ] **Step 4: `DataStoreFactory` 委托**

`client/src/main/java/cn/wildfirechat/proto/store/DataStoreFactory.java`，在第 452 行 `getUserSettings` 的实现之后加：

```java
    @Override
    public long getUserSettingVersion() {
        return protoMessageDataStore.getUserSettingVersion();
    }

    @Override
    public void setUserSettingVersion(long version) {
        protoMessageDataStore.setUserSettingVersion(version);
    }
```

委托给 `protoMessageDataStore`（不是 `memoryStore`），与同文件第 440-452 行三个 userSetting 方法保持一致。

- [ ] **Step 5: 创建 UG 响应 handler**

`client/src/main/java/cn/wildfirechat/proto/handler/UserSettingPullHandler.java`：

```java
package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;
import com.google.protobuf.InvalidProtocolBufferException;

import cn.wildfirechat.proto.ProtoService;
import cn.wildfirechat.proto.WFCMessage;

/**
 * UG 的应答：服务端只返回 updateDt 大于请求 version 的条目。
 * value 为 "0" 是 tombstone，表示该设置已被其他设备取消，本地要删掉。
 */
public class UserSettingPullHandler extends AbstractMessageHandler {
    private Log log = LoggerFactory.getLogger(UserSettingPullHandler.class);

    public UserSettingPullHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUB_ACK == header.getSignal() && SubSignal.UG == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        int errorCode = byteBufferList.get();
        if (errorCode != 0) {
            log.e("UserSettingPullHandler error code " + errorCode);
            return;
        }
        try {
            WFCMessage.GetUserSettingResult result =
                    WFCMessage.GetUserSettingResult.parseFrom(byteBufferList.getAllByteArray());
            log.i("pull user setting count " + result.getEntryCount());

            long maxUpdateDt = protoService.getImMemoryStore().getUserSettingVersion();
            for (WFCMessage.UserSettingEntry entry : result.getEntryList()) {
                protoService.getImMemoryStore().setUserSetting(entry.getScope(), entry.getKey(), entry.getValue());
                if (entry.getUpdateDt() > maxUpdateDt) {
                    maxUpdateDt = entry.getUpdateDt();
                }
            }
            protoService.getImMemoryStore().setUserSettingVersion(maxUpdateDt);
        } catch (InvalidProtocolBufferException e) {
            log.e("UserSettingPullHandler parse error " + e.getMessage());
        }
    }
}
```

说明：tombstone（`value == "0"`）直接按普通值写进本地 map，读侧（`ChatManager.getMyGroups()` 判 `entry.getValue().equals("1")`、`conversationSetting` 判 `.equals("1")`）本来就只认 `"1"`，所以写 `"0"` 与删除等价，且保留了「服务端说过它被取消」这一事实。不要在这里删 map 条目。

- [ ] **Step 6: 创建 UN 通知 handler**

`client/src/main/java/cn/wildfirechat/proto/handler/NotifyUserSettingHandler.java`：

```java
package cn.wildfirechat.proto.handler;

import com.comsince.github.core.ByteBufferList;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;
import com.comsince.github.push.SubSignal;

import cn.wildfirechat.proto.ProtoService;

/**
 * UN：服务端告知「你的设置变了，最新 version 是 X」，body 为 8 字节 version。
 * 本端据此发 UG 拉增量。发起修改的那台设备也会收到自己的 UN，多拉一次是幂等的。
 */
public class NotifyUserSettingHandler extends AbstractMessageHandler {
    private Log log = LoggerFactory.getLogger(NotifyUserSettingHandler.class);

    public NotifyUserSettingHandler(ProtoService protoService) {
        super(protoService);
    }

    @Override
    public boolean match(Header header) {
        return Signal.PUBLISH == header.getSignal() && SubSignal.UN == header.getSubSignal();
    }

    @Override
    public void processMessage(Header header, ByteBufferList byteBufferList) {
        long version = byteBufferList.getLong();
        long localVersion = protoService.getImMemoryStore().getUserSettingVersion();
        log.i("receive user setting notify version " + version + " local " + localVersion);
        if (version <= localVersion) {
            return;
        }
        workExecutor.execute(new Runnable() {
            @Override
            public void run() {
                protoService.pullUserSettings(localVersion);
            }
        });
    }
}
```

- [ ] **Step 7: 注册两个 handler**

`client/src/main/java/cn/wildfirechat/proto/ProtoService.java`，在第 82 行 `messageHandlers.add(new FriendPullHandler(this));` 之后加：

```java
        messageHandlers.add(new UserSettingPullHandler(this));
        messageHandlers.add(new NotifyUserSettingHandler(this));
```

并在文件头部加对应的两条 import（与同目录其他 handler 的 import 写法一致）。

- [ ] **Step 8: `ProtoService` 加 UG / UP 两个方法**

在 `ProtoService` 里 `getMyFriendList` 方法（第 242-255 行）之后加：

```java
    /**
     * 拉取用户设置增量。version 传本地已同步到的最大 updateDt，新设备传 0 即全量。
     * 结果由 UserSettingPullHandler 落到本地 store，这里不等待。
     */
    public void pullUserSettings(long version){
        log.i("pull user settings from version "+version);
        WFCMessage.Version request = WFCMessage.Version.newBuilder().setVersion(version).build();
        sendMessage(Signal.PUBLISH, SubSignal.UG, request.toByteArray(), null);
    }

    /**
     * 上报一条用户设置。取消操作传 value "0"（tombstone），不要用删除表达。
     */
    public void updateUserSetting(int scope, String key, String value, JavaProtoLogic.IGeneralCallback callback){
        log.i("update user setting scope "+scope+" key "+key+" value "+value);
        WFCMessage.ModifyUserSettingReq request = WFCMessage.ModifyUserSettingReq.newBuilder()
                .setScope(scope)
                .setKey(key == null ? "" : key)
                .setValue(value == null ? "" : value)
                .build();
        sendMessage(Signal.PUBLISH, SubSignal.UP, request.toByteArray(), callback);
    }
```

`key` / `value` 的 null 归一是必需的：proto2 里这三个字段都是 `required string`，传 null 会抛 `NullPointerException`；scope 2（全局免打扰）的 key 本来就是空串。

- [ ] **Step 9: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :client:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

本任务没有新增单测：两个 handler 依赖 `ByteBufferList` 与 `ProtoService` 的运行时状态，两个网络方法依赖真实连接，都无法在 JVM 单测里构造。可测的 key 逻辑已在 Task 2 覆盖，端到端由 Task 5 的真机验证兜底。

- [ ] **Step 10: git add（不要 commit）**

```bash
git add client/src/main/java/cn/wildfirechat/proto/store/ImMemoryStore.java \
        client/src/main/java/cn/wildfirechat/proto/store/DataStoreAdapter.java \
        client/src/main/java/cn/wildfirechat/proto/store/ProtoMessageDataStore.java \
        client/src/main/java/cn/wildfirechat/proto/store/DataStoreFactory.java \
        client/src/main/java/cn/wildfirechat/proto/handler/UserSettingPullHandler.java \
        client/src/main/java/cn/wildfirechat/proto/handler/NotifyUserSettingHandler.java \
        client/src/main/java/cn/wildfirechat/proto/ProtoService.java
```

---

### Task 4: 写入接网络、登录触发拉取、清理死代码

**Files:**
- Modify: `client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java:492-501`（三个方法）
- Modify: `client/src/main/java/cn/wildfirechat/proto/handler/ConnectAckMessageHandler.java:28-40`

**Interfaces:**
- Consumes: Task 3 的 `ProtoService.pullUserSettings(long)` / `ProtoService.updateUserSetting(int, String, String, IGeneralCallback)`、`ImMemoryStore.getUserSettingVersion()`；Task 2 的 `UserSettingKeys.conversationKey(...)`
- Produces: 完整链路——UI 改设置 → 本地生效 + UP 上报；登录 → 按 settingHead 决定是否 UG

- [ ] **Step 1: `setUserSetting` 写本地后上报**

`client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java` 第 698-701 行整段替换为：

```java
    public static void setUserSetting(int scope, String key, String value, JavaProtoLogic.IGeneralCallback callback){
        logger.i("setUserSetting scope "+scope+" key "+key+" value "+value);
        // 先写本地，UI 立即生效；再上报服务端，失败不回滚，下次登录以服务端为准
        protoService.getImMemoryStore().setUserSetting(scope,key,value);
        protoService.updateUserSetting(scope, key, value, callback);
    }
```

- [ ] **Step 2: 让两个死方法变正确（而不是继续误导）**

同文件第 492-494 行与第 500 行。`setConversationTop` 当前只打了一行恒为 `false` 的日志，`setConversationSilent` 是空方法体，两者全仓库零调用方。改成真正委托，将来若有人调用不会得到静默失败：

```java
    public static void setConversationTop(int conversationType, String target, int line, boolean top){
        setUserSetting(UserSettingScope.ConversationTop,
                UserSettingKeys.conversationKey(conversationType, line, target), top ? "1" : "0", null);
    }
```

```java
    public static void setConversationSilent(int conversationType, String target, int line, boolean silent){
        setUserSetting(UserSettingScope.ConversationSilent,
                UserSettingKeys.conversationKey(conversationType, line, target), silent ? "1" : "0", null);
    }
```

在文件头部加 import：

```java
import cn.wildfirechat.remote.UserSettingScope;
```

（`UserSettingKeys` 与本类同包 `cn.wildfirechat.proto`，无需 import。）

- [ ] **Step 3: 登录后按 settingHead 拉取**

`client/src/main/java/cn/wildfirechat/proto/handler/ConnectAckMessageHandler.java`，在 `processMessage` 里读出 `settingHead` 并在 `workExecutor` 的 `run()` 中、`protoService.getMyFriendList(true);` 那一行之后加入拉取：

```java
            WFCMessage.ConnectAckPayload connectAckPayload = WFCMessage.ConnectAckPayload.parseFrom(byteBufferList.getAllByteArray());
            long lastMessageSeq = protoService.getImMemoryStore().getLastMessageSeq();
            long settingHead = connectAckPayload.getSettingHead();
            long localSettingVersion = protoService.getImMemoryStore().getUserSettingVersion();
            logger.i(TAG,"friendHead "+connectAckPayload.getFriendHead()+" msgHead "+connectAckPayload.getMsgHead()+" settingHead "+settingHead+" localSettingVersion "+localSettingVersion+" lastMessageSeq "+lastMessageSeq+" serverTime "+connectAckPayload.getServerTime());
            workExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    protoService.getMyFriendList(true);

                    // 用户设置存在纯内存 store，进程重启后 localSettingVersion 归零，
                    // 这里就是一次全量拉取；服务端没有任何设置时 settingHead 为 0，跳过
                    if (settingHead > localSettingVersion) {
                        protoService.pullUserSettings(localSettingVersion);
                    }
```

其余部分（`lastMessageSeq` 的两个分支、注释掉的代码）保持原样不动。

**顺序是硬要求：`pullUserSettings` 必须在 `pullMessage` 之前。** 重装或换设备时会话列表要靠拉消息重建，`newProtoConversation()`（`ProtoMessageDataStore:489-499`）建会话对象时会从内存 `userSettingMap` 读取置顶/免打扰。设置先到位，新建的会话才带着正确状态；反过来则会话先建出来全是未置顶未免打扰，虽然 UG 回来后 `updateConversation` 能补救，但中间有一段界面是错的。不要把这段代码挪到两个 `lastMessageSeq` 分支之后。

- [ ] **Step 4: 编译并回归全部单测**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :client:assembleDebug :client:testDebugUnitTest :push-sdk:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，`UserSettingKeysTest` 8 个 + `SubSignalOrdinalTest` 3 个全过。

- [ ] **Step 5: git add（不要 commit）**

```bash
git add client/src/main/java/cn/wildfirechat/proto/JavaProtoLogic.java \
        client/src/main/java/cn/wildfirechat/proto/handler/ConnectAckMessageHandler.java
```

---

### Task 5: 长按会话菜单补「消息免打扰」并做真机验证

`ConversationContextMenuItemTags` 现有 tag 只有 remove / clear / stick_top / cancel_stick_top / unSubscribe_channel，长按菜单里没有免打扰入口。会话详情页的 `silentSwitchButton` 已经有了，不用改。

**Files:**
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationContextMenuItemTags.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationViewHolder.java:188-212`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListViewModel.java:271-273`
- Create: `chat/src/main/res/drawable/ic_menu_silent.xml`
- Create: `chat/src/main/res/drawable/ic_menu_unsilent.xml`
- Modify: `chat/build.gradle:19-20`（版本号）

**Interfaces:**
- Consumes: Task 4 打通的完整链路；`ConversationInfo.isSilent`（`client/.../model/ConversationInfo.java:19`，已存在）；`ChatManager.setConversationSilent(Conversation, boolean)`（`ChatManager.java:3162`，已存在）

- [ ] **Step 1: 加两个 tag**

`chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationContextMenuItemTags.java` 加两行：

```java
    String TAG_SILENT = "silent";
    String TAG_CANCEL_SILENT = "cancel_silent";
```

- [ ] **Step 2: 加两个图标**

`chat/src/main/res/drawable/ic_menu_silent.xml`（Material `notifications_off`）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--菜单图标：fillColor 仅为占位，实际颜色由 FshareContextMenu 按项目是否 destructive 着色-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M20,18.69L7.84,6.14 5.27,3.49 4,4.76l2.8,2.8v0.01c-0.52,0.99 -0.8,2.16 -0.8,3.42v5l-2,2v1h13.73l2,2L21,19.72l-1,-1.03zM12,22c1.11,0 2,-0.89 2,-2h-4c0,1.11 0.89,2 2,2zM18,14.68V11c0,-3.08 -2.13,-5.64 -5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,3.17 10,4v0.68c-0.15,0.03 -0.29,0.08 -0.42,0.12 -0.1,0.03 -0.2,0.07 -0.3,0.11h-0.01c-0.01,0 -0.01,0 -0.02,0.01 -0.23,0.09 -0.46,0.2 -0.68,0.31 0,0 -0.01,0 -0.01,0.01L18,14.68z" />
</vector>
```

`chat/src/main/res/drawable/ic_menu_unsilent.xml`（Material `notifications`）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--菜单图标：fillColor 仅为占位，实际颜色由 FshareContextMenu 按项目是否 destructive 着色-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z" />
</vector>
```

- [ ] **Step 3: ViewModel 加 setConversationSilent**

`chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListViewModel.java`，在第 271-273 行 `setConversationTop` 之后加：

```java
    public void setConversationSilent(ConversationInfo conversationInfo, boolean silent) {
        ChatManager.Instance().setConversationSilent(conversationInfo.conversation, silent);
    }
```

- [ ] **Step 4: 加两个菜单项并接进过滤器**

`chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationViewHolder.java`，在第 196 行 `cancelStickConversationTop` 方法之后加：

```java
    @ConversationContextMenuItem(tag = ConversationContextMenuItemTags.TAG_SILENT, title = "消息免打扰", icon = R.drawable.ic_menu_silent, priority = 3)
    public void silentConversation(View itemView, ConversationInfo conversationInfo) {
        conversationListViewModel.setConversationSilent(conversationInfo, true);
    }

    @ConversationContextMenuItem(tag = ConversationContextMenuItemTags.TAG_CANCEL_SILENT, title = "取消免打扰", icon = R.drawable.ic_menu_unsilent, priority = 4)
    public void cancelSilentConversation(View itemView, ConversationInfo conversationInfo) {
        conversationListViewModel.setConversationSilent(conversationInfo, false);
    }
```

并在同文件的 `contextMenuItemFilter` 里（第 203-212 行），`TAG_CANCEL_TOP` 那段之后、`return false;` 之前加：

```java
        if (ConversationContextMenuItemTags.TAG_SILENT.equals(itemTag)) {
            return conversationInfo.isSilent;
        }

        if (ConversationContextMenuItemTags.TAG_CANCEL_SILENT.equals(itemTag)) {
            return !conversationInfo.isSilent;
        }
```

与置顶一致：已免打扰时隐藏「消息免打扰」，未免打扰时隐藏「取消免打扰」，所以菜单里任何时候只出现其中一项。

- [ ] **Step 5: 升版本号**

`chat/build.gradle` 第 19-20 行：

```gradle
        versionCode 20010
        versionName "2.0.10"
```

- [ ] **Step 6: 编译安装到真机**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home \
  ./gradlew :chat:assembleDebug
/Users/liaojinlong/Library/Android/sdk/platform-tools/adb devices
/Users/liaojinlong/Library/Android/sdk/platform-tools/adb -s 381QYEC3222BP install -r chat/build/outputs/apk/debug/chat-debug.apk
```

真机 MEIZU 20 序列号 `381QYEC3222BP`，包名 `cn.comsince.fschat`。该机调试期间会间歇掉线，install 卡住先跑 `adb devices` 确认。若 apk 路径与上面不符，用 `find chat/build/outputs -name "*.apk"` 找实际产物。

- [ ] **Step 7: 真机验证清单**

需要联调环境的服务端已更新到 2.2.12-SNAPSHOT（`push-group` 先于 `push-connector`）。用 `adb logcat | grep -iE "user setting|UserSetting"` 观察日志。

| # | 操作 | 预期 |
|---|---|---|
| 1 | 群详情页打开「保存到通讯录」 | 日志出现 `update user setting scope 6`；不再抛 `NumberFormatException` |
| 2 | 杀进程重新登录 | 日志出现 `settingHead` 非 0、`pull user settings from version 0`；通讯录「我的群组」里该群还在 |
| 3 | 长按会话选「消息免打扰」，再长按 | 菜单变为「取消免打扰」；日志出现 `scope 1` 且 key 形如 `1-0-<target>`（**连字符**，不是冒号） |
| 4 | 长按会话置顶后杀进程重登 | 会话仍在列表顶部 |
| 5 | 同账号第二台设备在线时，在本机改任一设置 | 第二台设备日志出现 `receive user setting notify version`，随后自动 `pull user settings` 并更新界面 |
| 6 | 群详情页关闭「保存到通讯录」，杀进程重登 | 该群不再出现在「我的群组」（服务端返回 tombstone `"0"`） |
| 7 | **卸载重装**（或清除应用数据）后用同一账号登录 | 对应「恢复时序」的场景 B：日志中 `pull user settings from version 0` 出现在 `pullMessage` **之前**；进入「我的群组」能看到全部已保存的群（首次可能有数秒等待，是逐个 GPGI 拉群信息所致）；消息拉回来后，之前置顶/免打扰过的会话直接带着正确状态出现，不需要二次刷新 |

第 3 条的 key 分隔符是本次改动的核心风险点，务必在日志里亲眼确认是连字符。第 7 条是唯一能验证「UG 必须先于 pullMessage」这条顺序约束的场景，不要跳过。

- [ ] **Step 8: git add（不要 commit）**

```bash
git add chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationContextMenuItemTags.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationViewHolder.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListViewModel.java \
        chat/src/main/res/drawable/ic_menu_silent.xml \
        chat/src/main/res/drawable/ic_menu_unsilent.xml \
        chat/build.gradle
```

---

## 已知限制与后续项

- **`userSettingMap` 仍不持久化，但置顶/免打扰另有一条持久化路径。** 区分清楚：
  - scope 6（群组保存）与 scope 2（全局免打扰）**只**存在 `ProtoMessageDataStore.userSettingMap`（`:37` 内存 map），进程重启即空。
  - scope 3 / scope 1（置顶、会话免打扰）除了写这个内存 map，还会经 `updateConversation()`（`:481`）把 `isTop`/`isSilent` 序列化进 `conversations.conversation_info` BLOB 落 SQLite，重启后由 `getConversations():575` 从 BLOB 读回。**但这份持久化依附于会话行**——会话被删除/清空，或新设备上会话尚未靠拉消息重建时，设置一并消失。这正是本需求的根源之一。
  - 本计划不改变上述任一存储位置，只是让四个 scope 都能从服务端拉回。每次登录 `UG(0)` 全量拉取后，`setUserSetting` 会把置顶/免打扰重新写进会话 BLOB，两条路径结果一致，不冲突。
  - 冷启动多一次全量拉取，条目数量级为「保存的群数 + 置顶数 + 免打扰数」，可忽略。
- **`ImMemoryStoreImpl` 的 userSetting 实现仍是死代码**，本计划只把它的 key 拼法对齐，没有删除。删或不删由仓库所有者决定。
- **全局免打扰（scope 2）没有 UI 入口**，本计划只保证协议侧可用。
- **老服务端兼容**：若联调环境还没更新到 2.2.12-SNAPSHOT，UG/UP 会收到未知 topic 的错误码。`pullUserSettings` 与 `updateUserSetting` 都是 fire-and-forget，不会阻塞登录流程，但设置不会同步。真机验证前先确认服务端版本。
