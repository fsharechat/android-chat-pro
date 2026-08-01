# 多端同时登录时的 1v1 音视频通话状态同步 — 设计文档

日期：2026-08-01

涉及仓库：`chat-server-pro`、`android-chat-pro`、`ios-chat-pro`、`electron-vue-chat-pro`

## 背景

同一账号在 Android / iOS / Electron 多端同时登录时，收到 1v1 音视频通话邀请，所有在线端会同时弹出接听界面并响铃。当用户在其中一端接听后：

1. **其他端不会停止响铃**，接听界面一直停留，直到整通电话结束
2. 其他端上那个残留的接听界面如果被用户手动挂断，**会把正在通话的那一端也挂断**

第 2 点的原因在 `AVEngineKit.java:1014-1022`：`endCall` 除 `AcceptByOtherClient` 外都会向对端发 `ByeMessage`；而对端的 session 只按 `userId` 认人，不区分设备，任何一台设备发来的同 callId Bye 都会触发 `endCall(RemoteHangup)`。

第 1 点的现象最终会"自愈"，但机制是错的：接听端挂断后，对端收到 Bye 走 `endCall(RemoteHangup)`，该 reason 不在豁免列表里，于是对端**又回发一条 Bye 给用户 B**，这条以 `direction = Receive` 到达 B 的所有设备，响铃端才跟着结束。所以响铃端必须等到整通电话结束才会关闭。

## 诊断结论

Android(`AVEngineKit.java:436-440`) 与 iOS(`CallManager.swift:164-167`) 本来都写了「自己其他端接听了就静默收场」的分支，判定条件是收到 `direction = Send`（即 `fromUser == 自己`）的 401 Answer。这些代码是对的，但**从未被触发**。

在魅族 20（`381QYEC3222BP`）上抓 logcat 做了两次真机复现：

**复现一（Electron 接听，Android 响铃端）**

```
12:01:11.559  messageType-> 400   → 12:01:11.561 MDR   ← 来电，接听界面弹出
12:01:16.785  messageType-> 403   → 12:01:16.785 MDR
12:01:17.077  messageType-> 403   → 12:01:17.078 MDR
12:01:17.079  messageType-> 403   → 12:01:17.081 MDR
12:01:17.081  messageType-> 403   → 12:01:17.083 MDR
12:01:36.418  messageType-> 402   → 12:01:36.419 MDR   ← 主叫回发的 Bye
12:01:36.439  didCallEndWithReason=RemoteHangup        ← 界面这才关闭
```

**复现二（另一台 Android 接听，魅族响铃端）**

```
收到的消息类型统计：400 × 1，403 × 15
401 / 402 / 405：0 条
整通只收到 1 条 MN（对应 400），之后全是服务端直推的 MP（403）
```

两个判据：

- `ReceiveMessageHandler.uploadReport()` 只对 `direction == 1`（收到的消息）上报送达回执。上表中**每一条到达的信令后面都紧跟一次 MDR**，说明全部来自对端（主叫），没有一条来自同账号的其他设备。
- 复现二里接听端是 Android，`AVEngineKit.java:1001-1004` 明确会发 401，但响铃端既没收到 MN 通知也没收到 MP 直推。

**结论：生产服务端不会把 VOIP 信令（401 `No_Persist` / 402 `No_Persist` / 403、405 `Transparent`）扇出给发送方自己的其他设备。** 普通文字消息可以多端同步，说明线上的 `getNotifyReceivers` 对这几类 VOIP 消息没有把 `fromUser` 计入 receivers，与 `chat-server-pro` 当前源码 `MemoryMessagesStore.java:130` 不一致（线上版本较旧）。

因此**任何依赖"自发信令自动扇出"的纯客户端方案都不成立**，必须新增服务端投递能力。

顺带查到的两个客户端缺陷：

- Electron `voipclient.ts:74-83` 的 `answerCall` 只发 405 `AnswerT`，不发 401 `Answer`（Android `AVEngineKit.java:1001-1004`、iOS `CallManager.swift:112-114` 都发两条）
- Electron `voipclient.ts:132` 用 `protoMessage.direction === 1` 过滤，自发信令全部被丢弃，所以即使服务端投递了它也不会处理

（另：iOS `CallManager.swift:112` 的注释「AnswerT(405,透传给自己其他端)」写反了，实际设计上 405 是透传给对端、401 才用于多端同步。）

## 目标

1. 任一端接听后，同账号其他端自动停止响铃并关闭接听界面
2. 任一端拒接后，同账号其他端同样自动关闭（整通拒绝语义）
3. 其他端的关闭动作**不得**影响正在通话的那一端
4. 其他端关闭后，本地通话记录不能显示成错误的「对方未接听」
5. 不改动 400 / 401 / 402 / 403 / 404 / 405 现有信令的任何行为，风险面限制在新增路径上

## 非目标

- 不改服务端通用消息扇出语义（`publish2Receivers` 的正常分支不动）——那会影响未读计数、会话更新等，回归成本高
- 不做群组音视频的多端同步，本设计只覆盖 1v1
- 不做「仅本端静音、其他端继续响」的语义，拒接一律是整通拒绝
- 一期不做设备级锁定（见「二期」）

---

## Part A — 协议：新增 406 信令

现有 VOIP contentType 占用 400–405（`ProtoConstants.java:147-152`），**406 空闲**。

新增 `VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC = 406`，语义单一：**「这通电话我在别的设备上处理了」，只发给自己账号的其他设备**。

| 字段 | 值 |
|---|---|
| contentType | 406 |
| PersistFlag | `Transparent`（不落库、不产生未读） |
| `content` | `callId` |
| `binaryContent` | JSON：`{"r":"answered"\|"rejected","d":"<发送端 clientId>"}` |

- `r` — 处理方式，一期只有 `answered` / `rejected` 两个值
- `d` — 发送端的 IM `clientId`（Android `ChatManager.getClientId()`、iOS `IMClient.clientIdentifier`、Electron `localStorage[KEY_VUE_DEVICE_ID]`）。一期不消费，为二期的设备锁定预留

**向后兼容**：全新类型，老版本客户端 `contentMapper.get(406)` 返回 null、Electron `MessageConfig.getMessageContentClazz(406)` 返回 undefined，消息被直接跳过，不会崩溃也不会误显示。因此 payload 布局没有历史包袱，不需要走 `searchableContent` 塞字段之类的取巧手段。

**发送寻址**：客户端发 406 时，conversation target **仍然填对端 userId**，沿用现有发送路径。原因是服务端 `SendMessageHandler.action()` 对单聊有好友校验，而 `MessageServiceImpl.java:155` 的 `isFriend` 走好友表查询，`isFriend(self, self)` 一般为 false，把 target 填成自己会被 `ERROR_CODE_NOT_FRIEND` 拒绝。实际路由由服务端按 contentType 决定，不看 target。

---

## Part B — 服务端（chat-server-pro）

### B1. 常量

`push-stub/src/main/java/com/comsince/github/proto/ProtoConstants.java`：

```java
public static final int VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC = 406;
```

### B2. 定向推送方法

`spring-boot-dubbo-push-connector/.../handler/im/MessagesPublisher.java` 新增：

```java
public void publishMessage2SelfOtherClients(String userId, String exceptClientId, FSCMessage.Message message)
```

实现要点：

- 遍历 `sessionService.sessionForUser(userId)`
- 跳过 `targetSession.getClientID() == null` 以及 `exceptClientId.equals(targetSession.getClientID())` 的会话
- 组包与推送复用现有 `publishMessageToClientId()`（`MessagesPublisher.java:74-97`）里那段逻辑：`PullMessageResult` 包一条 message → 首字节写 0 作为错误码 → `PublishAckMessagePacket` + `SubSignal.MP` → `PushUtil.pushMessageByBsId()`

把 `publishMessageToClientId()` 中的组包部分抽成私有方法供两者复用，避免复制粘贴。

**为什么走 MP 直推而不是 MN 通知**：406 是 `Transparent`，不需要落库和拉取；MP 直推与现有 403 走客户端完全相同的接收路径（`ReceiveMessageHandler.processMessage` → `processNotifyPullMessage`），客户端零额外适配。

### B3. 路由拦截

`spring-boot-dubbo-push-connector/.../handler/im/SendMessageHandler.java` 的 `action()` 中，在现有好友/黑名单校验**之后**、`saveAndPublish` **之前**拦截：

```java
if (message.getContent().getType() == ProtoConstants.VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC) {
    publisher.publishMessage2SelfOtherClients(fromUser, clientID, message);
    ackPayload = ackPayload.capacity(20);
    ackPayload.writeLong(messageId);
    ackPayload.writeLong(timestamp);
    return ErrorCode.ERROR_CODE_SUCCESS;
}
```

即：**不落库、不投递给对端、不产生未读**，只投给自己其他设备。ack 格式与正常发送一致，客户端的发送回调无需改动。

放在校验之后是为了最小改动 —— 406 的 target 是对端，本来就是好友，校验能通过。

### B4. 集群

`PushUtil.pushMessageByBsId()` 是本节点推送。生产是三节点集群（`Config.java:19-22`），跨节点通过 Kafka 分发。新方法与现有 `publishMessageToClientId()` / `publishTransparentMessage2Receivers()` 用的是同一套推送原语，跨节点行为与它们一致，不需要额外处理。

---

## Part C — 客户端三端

### C1. 发送

| 时机 | 动作 |
|---|---|
| 接听 | 现有 401/405 发送之后，补发一条 406 `{"r":"answered","d":"<本端 clientId>"}` |
| 拒接 | 现有 402 发送之后，补发一条 406 `{"r":"rejected","d":"<本端 clientId>"}` |

Electron 原本缺失的 401 **不需要补** —— 406 直接承担了多端同步的职责，401 只用于通知对端。

**两条约束，三端都要遵守**（不遵守会误播）：

1. **406 `rejected` 只在 `state == Incoming` 时发送。** 三端的拒接与通话中挂断都共用同一个方法（Android `CallSession.endCall()` `:1010-1012`、iOS `reject()`/`hangUp()` 都走 `hangUp(reason:)` `:130-134`、Electron `cancelCall()` `:65-72`），不加状态判断的话，通话中正常挂断也会广播一条 `rejected`。其他端此时并不在响铃，不会造成可见故障，但属于无意义的信令噪音，且会干扰二期的设备锁定判断。
2. **只有本端用户主动触发的结束才发 406。** 因收到对端 Bye、超时、媒体错误等被动结束一律不发 —— 那些情况下对端已经知道通话结束，而自己的其他端会通过对端回发的 Bye 自然结束。

### C2. 接收

收到 406 且**同时满足**以下条件时处理，否则忽略：

- `direction == Send`（即 `fromUser == 自己`，来自本账号的其他设备）
- `callId` 与 `currentSession.callId` 一致
- `currentSession.state == Incoming`

处理动作：

| | |
|---|---|
| **要做** | 停铃声/震动、释放媒体资源（响铃阶段可能已开摄像头预览）、清理 `currentSession`、关闭接听界面、按 `r` 写本地通话记录状态 |
| **不要做** | **发 Bye**（关键，否则会挂掉对端已接通的通话）、给对端发任何回执 |

`r = "answered"` → 结束原因 `AcceptByOtherClient`；`r = "rejected"` → 新增结束原因 `RejectByOtherClient`。两者都必须走「不发 Bye」的路径。

### C3. 通话记录（必须显式处理）

`AVEngineKit.java:867-881`，`setState(Idle)` 时会更新那条 400 CallStart 消息：

```java
if (state == CallState.Connected) { setConnectTime(...); setStatus(1); }
else { setEndTime(...); setStatus(2); }
```

非接听端的 `previousState` 是 `Incoming` 而非 `Connected`，会走到 `setStatus(2)` 且 `connectTime` 保持 0。再看 `VoipMessageViewHolder.java:42-52`：`connectTime > 0` 才显示通话时长，否则显示**「对方未接听」**。

结果是接听端显示「通话时长 03:20」，其他端却留下一条「对方未接听」，且该条目还能点击回拨。**每接一次多端来电就多一条假的未接来电，必须一并修掉。**

方案：新增 `status = 3`，渲染文案「已在其他设备接听」。

- `r = "answered"` → `status = 3`
- `r = "rejected"` → `status = 2`（沿用现有「对方未接听」文案，本来就没接听，语义可接受，不再新增类型）

`status` 只经 `ChatManager.updateMessage()` 本地更新、不发网络，三端各自渲染自己那条记录即可，不涉及协议改动。

### C4. 各端具体改动点

**Android（android-chat-pro）**

- 新增 `p2penginekit/.../message/CallTakenMessage.java`，`@ContentTag(type = 406, flag = PersistFlag.Transparent)`
- `AVEngineKit.init()`（`:246-250`）注册该 content 类
- `AVEngineKit.onReceiveCallMessage()`（`:358`）：
  - `:371` 的类型白名单加入 `CallTakenMessage`
  - `:374` 的时间窗与 direction 门加入 `|| message.content instanceof CallTakenMessage`
  - 新增分支处理，按 C2 执行
- `CallEndReason`（`:1111-1119`）新增 `RejectByOtherClient`
- `CallSession.endCall()`（`:1014-1022`）的豁免判断从 `reason != AcceptByOtherClient` 改为「`reason` 不属于 {`AcceptByOtherClient`, `RejectByOtherClient`}」
- `setState()`（`:867-881`）的通话记录更新：增加「本次结束是因为其他端处理」的分支，写 `status = 3`（answered）或 `status = 2`（rejected）
- `VoipMessageViewHolder.onBind()`（`:37-54`）增加 `status == 3` → 「已在其他设备接听」
- `answerCall()`（`:993-1007`，发送处在 `:1001-1004`）与拒接路径（`endCall()` `:1010-1012`）补发 406

**iOS（ios-chat-pro）**

- `CallSignalCodec.swift` 增加 406 的编解码 case
- `CallSession.swift:11-19` 的 `CallEndReason` 新增 `rejectedElsewhere`（已有 `acceptedElsewhere`）
- `CallManager.swift:164-167` 的 `sender == myUserId()` 分支：处理 406，按 `r` 分派到 `acceptedElsewhere` / `rejectedElsewhere`
- `CallManager.answer()`（`:107-125`）与 `reject()`（`:127`，实际走 `hangUp(reason:)` `:130-134`）补发 406
- 通话记录渲染按 C3 增加 `status == 3` 分支
- 顺手修正 `:112` 那条写反的注释

**Electron（electron-vue-chat-pro）**

- `src/renderer/src/websocket/message/messageContentType.ts` 增加 `VOIP_CONTENT_TYPE_MULTI_CLIENT_SYNC = 406`
- 新增 `src/renderer/src/webrtc/message/callTakenMessageContent.ts`
- `src/renderer/src/websocket/message/messageConfig.ts`（参照 `:211-222` 的写法）注册，`flag: PersistFlag.Transparent`
- `src/renderer/src/webrtc/callEndReason.ts` 新增 `REASON_RejectByOtherClient`
- `voipclient.ts:132` 的 `protoMessage.direction === 1` 放开，允许自发的 406 进入分发
- `voipclient.ts` 新增 406 分支，按 C2 执行；同时删掉 `:174-176` 挂在 Signal 分支上那个 `REASON_AcceptByOtherClient` 的 hack（它靠"Signal 到达时状态不对"来猜测，语义错误且已被 406 取代）
- `answerCall()`（`:74-83`）补发 406 `answered`
- **拒接补发 406 `rejected` 不能直接加在 `cancelCall()`（`:65-72`）里** —— 该方法被 Bye 接收分支（`:183`）复用，而它自身还会回发一条 Bye（`:68`）。若照字面加，对端挂断时 Electron 会向自己其他设备误播一条 `rejected`。必须先把「本端主动拒接/挂断」与「因收到对端 Bye 而结束」拆成两条路径，只有前者补发 406（顺带这也修掉了 Electron 收到 Bye 又回发 Bye 的既有问题）
- 通话记录：**无需改动**。Electron 从不回写 CallStart 的 status（全仓库 `updateMessageContent` 仅 recall 一处调用者），`MessageBubble.vue` 对 VOIP 消息一律走 `digest()`，不按 status 分支渲染。C3 的 status 3 在本端是空操作 —— 照做会加出一段永远不生效的代码。

---

## 边界与错误处理

- **406 早于 401 到达对端**：不可能互相影响 —— 406 只投给自己其他设备，对端永远收不到 406
- **其他端已离线**：离线端不会响铃，无需同步；重连后不会补收 406（`Transparent` 不落库），但也不会补收 400 的响铃，行为与今天一致
- **406 丢失**：退化为当前行为（响铃端等到整通结束才关），不会比现状更差
- **406 到达时本端已不在 Incoming**（例如用户手速更快自己也点了接听/拒接）：条件不满足直接忽略，避免二次 `endCall`
- **老版本客户端**：不认识 406，直接跳过；与新版本互通时，老端表现为当前行为，新端正常同步
- **同一账号两台设备几乎同时接听**：两台都会向对端发 401，对端 `AVEngineKit.java:417-434` 只认第一个（后到的 `state` 已是 `Connecting`，直接 return），第二台会因为收到第一台的 406 而关闭。极端竞态下第二台可能已经开始建 PeerConnection，此时它收到 406 仍会 `endCall`，媒体资源被正常释放。残余风险见「二期」
- **响铃端超时仍会发 Bye，能挂掉已接通的通话**：Android `Timeout` 不在 `endCall` 的 Bye 豁免列表内，iOS `.timeout` 走 `hangUp` 发 Bye。若 406 丢失、或响铃端恰在 406 推送那一刻掉线（406 是 `Transparent`，不落库、重连不补收），T+60s 该端发出的 Bye 会到达对端，挂断另一台设备已接通的通话。与改动前的失效模式相同，**不是本次引入的回归**，但它是一期之后唯一残留的「误挂通话」通道，正是二期设备级锁定要解决的问题。

## 测试与验收

**验收场景（三端两两组合，共 6 组）**

1. A 呼叫 B，B 的两个端同时响铃 → 在端 1 接听 → **端 2 在 2 秒内自动关闭接听界面并停止响铃**，端 1 通话正常
2. 同上，在端 1 拒接 → 端 2 自动关闭，A 侧显示已拒接
3. 端 1 接听并通话中，手动强杀/退出端 2 的残留界面（若还能复现）→ **端 1 通话不受影响**
4. 通话结束后检查端 2 的聊天记录：显示「已在其他设备接听」，不是「对方未接听」
5. 新版本与老版本客户端互通：老端表现为当前行为，不崩溃、不误显示

**Android 侧日志判据**（沿用本次诊断的方法）

```bash
adb -s <serial> logcat -v time | grep -E "messageType->|subSignal MDR|didCallEndWithReason"
```

- 响铃端应出现 `messageType-> 406`
- 该条 406 后面**不应**跟 MDR 上报（证明 `direction = Send`，来自自己的另一台设备）
- 应打出 `didCallEndWithReason=AcceptByOtherClient`（或 `RejectByOtherClient`）

**单元测试**

服务端 `publishMessage2SelfOtherClients` 的会话过滤逻辑（跳过 `exceptClientId`、跳过 null clientId）可以独立测。三端的信令分发因为与 WebRTC 强耦合，一期不强求单测，靠上述真机场景验收；若后续要补，应先把信令路由从 `AVEngineKit` / `voipclient` 中抽成不依赖媒体栈的纯函数。

## 实施顺序

四个仓库分四次落地，顺序不能颠倒：

1. **chat-server-pro** — Part B，部署到生产
2. **android-chat-pro** — Part C，先用两台 Android 复测场景 1，确认 406 通路打通
3. **ios-chat-pro** — Part C
4. **electron-vue-chat-pro** — Part C

第 2 步是关键验证点：只有它跑通，才说明服务端投递能力生效，再铺另外两端。

## 二期：设备级锁定

一期解决后仍有残余竞态：其他端在 406 到达前的极短窗口内被用户手动挂断，仍会向对端发 Bye 挂掉已接通的通话。

二期方案：给 402 `ByeMessage` 也带上发送端 `clientId`（406 的 `d` 字段已经有了），`CallSession` 增加 `peerDeviceId`：

- 主叫收到第一个 401 时锁定 `peerDeviceId`
- 此后 `bye.d != peerDeviceId` 的 Bye 一律忽略
- 对抢答的第二台设备回一条带 `t`（定向目标 deviceId）的 Bye，只有匹配的设备才处理

402 是既有类型，加字段需要考虑老版本兼容：字段缺失时退化为当前行为。放二期是为了不让一期的改动面过大。
