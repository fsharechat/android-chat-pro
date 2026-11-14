# Android 消息发送面板玻璃态重构方案

日期：2026-11-14
状态：已实现并在魅族20验证；详见 `docs/ui/玻璃态消息输入面板.md`
关联：`ios-chat-pro/docs/superpowers/specs/2026-11-14-liquid-glass-message-composer-design.md`。

## 1. 方案定位

与 iOS 统一采用「独立＋圆按钮 + 悬浮玻璃输入胶囊 + 键盘替换式表情/更多面板」。外观参考用户提供的 Apple 信息截图，操作延续现有 IM 习惯。Android 使用 Java/XML View 体系，保留现有 TCP、ViewModel、扩展插件与录音发送链路。

统一的是信息层级和基本操作，不照搬 iOS 的系统材质、pt尺寸或功能限制。Android 实现称为「玻璃态/液态玻璃风格」，不宣称使用 Apple 原生 Liquid Glass。

本工程 `chat/build.gradle`：minSdk21、compileSdk29、targetSdk29，构建使用 JDK11。首期不以升级SDK、迁移Compose为前提。

## 2. 现状盘点与功能保护

依据：ConversationInputPanel.java、conversation_input_panel.xml、ConversationActivity.java、Draft.java、AudioRecorderPanel.java、conversation/ext/*、InputAwareLayout.java，以及 `docs/ui/玻璃态底部导航.md`。

| 能力 | Android 当前实现 | 重构要求 |
|---|---|---|
| 文本 | EditText，多行；非空时发送替换更多 | 保留输入法、选区、复制粘贴，＋改为常驻 |
| @ | MentionSpan、@所有人、选人、整块删除、头像长按插入 | 保留 span、wire字段和光标位置，不能只保存String |
| 草稿 | onActivityPause保存，Draft JSON含mentions | 保留现有草稿机制；恢复时使用带span的解析路径 |
| 表情 | EmotionLayout、Emoji、贴纸、贴纸远端URL缓存 | 保留全部数据、分页与删除，不能替换成iOS的纯Unicode面板 |
| 相册 | ImageExt，最多9张图片，可显示相机入口 | 保留多选和既有发送流程；不降为iOS单选 |
| 拍摄 | ShootExt，照片和小视频分支 | 两者保留，不降为仅拍照 |
| 文件 / 位置 | FileExt / LocationExt | 保留返回结果、权限和上传处理 |
| 通话 | AudioVoipExt / VideoVoipExt，按会话过滤 | 保留实际可用入口，不直接套用iOS群聊隐藏策略 |
| 群聊语音入口 | AudioVoipExt选一名成员后发起一对一通话 | 保留并明确文案，不宣称是多人群通话 |
| 输入状态 | 文本输入中通知、录音开始通知、媒体入口通知 | 保留现有信令与节流，样式更新不能重复触发通知 |
| 扩展系统 | ConversationExtManager注册，ConversationExtension分发结果 | 保留插件与requestCode路由，不按六个硬编码按钮重写业务 |
| 录音 | AMR_NB，最短约1秒、最长60秒、末10秒倒计时 | 保留格式/时长/上传入口，改进取消与资源释放 |

差异与问题：

- 当前白底输入区、横向分割线、多个位图按钮视觉偏重，且有正文时＋消失。
- 当前列表和输入栏纵向排列，消息不会绘制到输入栏后方，需要先改变层叠结构。
- 草稿保存了Mention元数据，但setDraft仅取getContent设置文本；重构应使用Draft.parseDraft恢复span，不能让外观看似@而发送元数据丢失。
- AudioRecorderPanel把ACTION_CANCEL与ACTION_UP合并，取消时可能进入成功发送路径；deattch仅清引用，未完整处理停止录音、移除任务与关闭弹层。
- 当前录音取消阈值含固定40像素，应换算dp并加入滞回。
- 表情“添加/设置”当前仅Toast，不应包装成已完整实现的表情商店；本次不新增相关业务。

## 3. 布局与尺寸

### 3.1 默认与输入状态

```text
空正文：
  ( ＋ )   ╭──────────────────────────╮
           │ 发消息…        ☺   波形  │
           ╰──────────────────────────╯

有正文：
  ( ＋ )   ╭──────────────────────────╮
           │ 明天下午见     ☺    ↑   │
           ╰──────────────────────────╯
```

＋展开更多，展开后变×；表情展开后变键盘。波形只进入语音模式，不立即录音。↑调用现有sendTextMsg，发送槽位不随着文字长度变化。发送按钮用品牌强调色；普通图标为主题中性色，不固定成微信绿。

| 项目 | 初始值 | 适配 |
|---|---|---|
| 左右留白 | 12dp | 窄屏可降为8dp，横屏避开刘海/系统边界 |
| ＋圆与胶囊最小高度 | 48dp | ＋在多行时保持底部对齐 |
| ＋与胶囊间距 | 8dp | 透明空隙不拦截消息触摸 |
| 胶囊圆角 | 26dp | 多行后不继续取高度一半 |
| 操作点击槽 | 至少48×48dp | 发送视觉圆32–36dp；图标约22dp |
| 正文 | 16–17sp | 跟随系统字体，不能用dp替代sp |
| 正文高度 | 常规4–5行封顶后内部滚动 | 大字体/短屏重新按可用高度计算 |
| 底部间距 | 内容区底部8dp | 导航栏inset只消费一次，不能固定再加导航条高度 |
| 功能图标 | 56–60dp承托，圆角16dp | 标题与图标整个单元可点，超大字允许重排 |

以360dp可用宽度计算：24留白+48圆钮+8间距后，胶囊280dp；两个48dp操作槽后仍有正文空间。320dp、分屏和横屏优先减外边距或允许编辑区域变窄，不压缩点击槽。布局使用start/end语义。

### 3.2 语音模式

```text
  ( ＋ )   ╭──────────────────────────╮
           │ ⌨     按住说话      ☺   │
           ╰──────────────────────────╯
```

⌨切回文本并恢复正文。☺切到文本+表情。录音中显示时长、音量、上滑取消提示；不展示可误触的发送箭头。正文非空时尾部用于发送，在更多中增加「语音消息」入口，允许保留文字后发语音，并区别于「语音通话」。

## 4. 表情与更多面板

- 沿用EmotionLayout与贴纸能力；表情插入当前位置，选区替换、组合Emoji删除与MentionSpan整块删除共同回归。
- 保留扩展注册与会话filter机制，常用布局参考：第一行「照片、拍摄、文件、位置」，第二行「语音通话、视频通话、语音消息」。以真实可用扩展为准，超过一页保留分页能力。
- 群聊入口沿用现有实现；需要先选成员的一对一通话必须说明，不能改成未实现的多人通话。
- 选择照片、拍摄、文件、位置后保持正文和@；取消返回恢复原面板，避免自动弹键盘挡住内容；通话启动则收起面板。
- 面板高度继续参考最近有效停靠键盘高度，保留现有KeyboardHeightFrameLayout思路，增加按可用高度限幅。低高度/大字体允许滚动，不能固定260dp覆盖正文。
- 面板采用稳定实体或高不透明背景，玻璃重点留给上方悬浮控件。贴纸、功能格不逐个加模糊。

## 5. 玻璃材质实现

### 5.1 首选复用现有背景采样能力

工程已有GlassBackdropRenderer、GlassTabBar和GlassEdgeEffectFactory。现有渲染器构造参数直接依赖GlassTabBar，不能原样传入输入框；先提取窄接口GlassBackdropTarget（建议名）：提供采样区域、接收模糊结果、请求绘制与必要视觉反馈。

- 首页GlassTabBar和新增ComposerGlassBackgroundView分别实现目标接口，输入状态不依赖材质实现。
- 只采集悬浮dock后方消息层，不采集包含dock的整棵根视图，不采集键盘、录音弹窗或外部选择器，避免递归采样与重复阴影。
- ＋圆和胶囊共用一次dock包围区采样，各自裁剪；不为每个按钮创建线程或位图。
- 沿用低分辨率采样、可复用buffer、后台单线程模糊和性能退化策略。View.draw依旧在主线程执行，后台只处理稳定的像素副本；不得工作线程访问View树。
- 保留CaptureCanvas标记和GlassEdgeEffectFactory隔离。消息RecyclerView也接入隔离，避免重现首页曾出现的离屏绘制破坏EdgeEffect、持续拖动回跳。
- 采样坐标统一转换到window坐标，再抵消source scrollX/scrollY；键盘动画、窗口移动与多行高度改变时更新区域，不能只复用首页固定底栏坐标。
- 缓冲区采用受控双缓冲或同等所有权机制，绘制线程仍在读的Bitmap不得回收；关闭后丢弃过期工作结果。
- 圆角裁剪内放背景材质，外部单独绘制阴影；输入文字/图标置于材质上层，不参与模糊。

### 5.2 性能分级

| 等级 | 效果 | 切换依据 |
|---|---|---|
| A | 低分辨率背景模糊+中性承托+薄高光+软阴影 | 实机采样/滚动耗时合格 |
| B | 降低采样分辨率/刷新率，保留玻璃轮廓与输入对比度 | 持续掉帧或处理队列积压 |
| C | 高不透明主题背景+描边+轻阴影 | 低内存、不可采样内容或用户关闭透明效果 |

静止停止持续采样；滚动/消息更新/主题切换/输入几何变化驱动刷新。初始性能预算可参照现有导航实现，但不能照搬其单台魅族20的性能结果作为会话页指标。

Android高版本的模糊API不列为首期依赖；尤其不能简单模糊EditText或整个输入容器，否则模糊的是前景内容。若将来引入新SDK渲染路径，应独立核实API与SDK条件，并保留compileSdk29方案。避免私有API和全窗口模糊。

## 6. 层叠结构与键盘协调

### 6.1 视图层次

```text
InputAwareLayout（保持现有对外契约）
 ├─ toolbar
 └─ conversationContentHost（覆盖布局）
     ├─ SwipeRefreshLayout / RecyclerView
     ├─ BottomProtectionView（装饰，不拦截触摸）
     └─ ComposerHost
         ├─ 浮动＋与输入胶囊
         └─ customPanelHost（表情 / 更多，互斥）
```

RecyclerView延伸到dock后方，clipToPadding=false。dock区域对应paddingBottom动态更新，最后一条消息能完整滚到胶囊上方；系统IME/自定义面板占用区应作为列表可见区域的边界，不能把列表绘制到面板下方后又重复加同等高度padding。

计算规则：先确定有效列表viewport（已扣除由窗口resize或面板占位实际消耗的高度），只对该viewport内的dock遮挡增加paddingBottom；若某条实现路径未缩小viewport，则由协调器一次性补偿那部分遮挡。导航栏inset也只计一次。

### 6.2 IME与系统导航

- 由一个ComposerLayoutCoordinator负责面板、键盘和底部padding；InputAwareLayout仍可作为适配层，不能同时让多个类改高度。
- 首期沿用现有软键盘高度检测并明确resize契约，核对ConversationActivity及基类实际windowSoftInputMode；不要误改其他Activity的adjustPan/adjustResize声明。
- 键盘→面板时保留上一帧有效占位，收到键盘收起通知后以同高度面板替换；面板→键盘相反。使用transitionID过滤过期回调，不能在两种surface之间出现一帧零高度。
- 新版Insets/IME动画支持作为后续可选适配，需先核对现有AndroidX和SDK依赖，不能仅在compileSdk29源码中直接引用高版本平台API。
- 三键导航、手势导航、分屏、横屏、悬浮输入法、外接键盘分别测量；不能把可见窗口差值一律认定为全宽键盘。
- 不以此任务顺带修改全应用edge-to-edge策略。输入面板遵守当前会话窗口坐标与系统栏消费方式。
- 返回键先关闭表情/更多，再按当前系统IME返回分发规则收键盘；收起后再返回会话列表。录音时返回表示取消，不能发送。
- 打开面板前记录是否处于底部；底部保持最新消息可见，阅读历史时保持首个可见messageId及top偏移，避免强制跳底。其余新消息自动滚动策略不顺带重写。

## 7. 状态、编辑与草稿

统一状态模型，避免靠View.VISIBLE/GONE反推状态：

```text
mode = TEXT | VOICE
surface = COLLAPSED | IME | EMOJI | MORE
recording = IDLE | REQUESTING_PERMISSION | RECORDING | CANCEL_ARMED | FINISHING
editor = Editable(含MentionSpan) + selectionStart/End
```

- IME/EMOJI必为TEXT；VOICE只允许COLLAPSED/MORE；录音时禁止其他surface打开。
- 文本非空时↑可见，＋保持；空白文本不可发送，发送入口内也再次检查trim后内容。
- 输入法composing span存在时不直接发送尚未确认的拼音内容，保留Return换行语义。
- 主题/布局切换保留同一EditText和Editable，不通过setText(String)重建文本，避免丢失MentionSpan、组合输入和撤销状态。
- 草稿加载改用Draft.parseDraft并校验span范围；空草稿、畸形元数据应安全回退。持久化仍走现有saveDraft，不增加表字段。
- 返回页面/进程恢复只恢复正文、mentions与必要模式，不恢复RECORDING状态，不自动打开麦克风。
- 程序恢复草稿、主题渲染不能触发@选人或输入中信令；明确区分用户输入与程序恢复。

## 8. 录音安全与手感

- 复用AMR_NB编码和sendAudioFile，不复制iOS的M4A→AMR流程。
- ACTION_DOWN建立会话，权限与MediaRecorder实际启动成功后才进入RECORDING、发TYPING_VOICE。启动失败回到IDLE并提供中文提示。
- ACTION_MOVE按dp计算取消区域，建议上移64dp进入CANCEL_ARMED，回落到44dp内恢复；阈值为设计初值。
- ACTION_UP仅在有效录音会话、发送意图且时长合格时发送一次；ACTION_CANCEL无条件取消，不能共用发送分支。
- 保留最长60秒和最后10秒倒计时；超时若为发送意图则结束发送，处于CANCEL_ARMED则丢弃，之后迟到的UP不得再发送。
- 统一结束入口处理手势取消、音频焦点丢失、权限变化、onPause、onDestroy与切换模式：停止并release recorder、释放音频焦点、取消Handler任务、dismiss弹层、清理未发送文件。
- 固定持有tick/hide的Runnable引用后再removeCallbacks，避免用新的方法引用误以为删除了旧任务。
- 首次授权完成后要求重新按住；sessionID防止异步回调在用户松手/离开后继续录音。
- 用户取消不弹英文失败Toast；使用轻量触感/中文反馈。TalkBack提供明确的开始、停止发送、取消操作，不能只有长按滑动一种路径。

## 9. 组件拆分与改动边界

| 文件/组件 | 改动 |
|---|---|
| ConversationInputPanel.java / conversation_input_panel.xml | 兼容原对外入口，重排常驻＋/胶囊/输入/表情/发送，状态驱动渲染 |
| ComposerState.java（新增） | 合法状态转移与发送/取消意图 |
| ComposerLayoutCoordinator.java（新增） | IME/面板占位、导航inset、列表anchor与transitionID |
| ComposerGlassBackgroundView.java（新增） | 胶囊/＋裁剪、主题、高光与性能分级 |
| GlassBackdropRenderer.java | 提取目标接口、支持会话采样；首页导航回归不可省略 |
| ConversationActivity.java / conversation_activity.xml | 覆盖布局、列表padding、生命周期和返回键接入 |
| AudioRecorderPanel.java / AudioRecorder.java | 取消、权限、异常与资源生命周期，保留编码上传 |
| Draft.java / 草稿恢复入口 | 保留并恢复MentionSpan，校验异常元数据 |
| conversation/ext/* | 保留插件契约，仅调整面板呈现和必要语音消息入口 |
| values / values-night | composer颜色、尺寸、文案，使用主题资源与矢量图标 |

不改服务端、protobuf、推送与通话底层，不改变现有相册选择数量或附件协议。GlassTabBar共享渲染器抽象属于本次必要影响范围，应单独回归首页。

## 10. 动效、无障碍与验收

动效建议：高度变化180–220ms，图标切换120–160ms；键盘跟随实际系统变化，不用统一固定时长覆盖输入法。取消装饰性持续动画，保留系统触摸反馈。尊重系统动画关闭状态；没有统一可依赖的透明度设置时，提供独立材质策略切换入口/内部策略接口，不假定各Android版本都有iOS同名开关。

TalkBack为控件提供「更多功能/收起更多」「表情/键盘」「语音输入」「发送」标签与状态。装饰图层不进入无障碍焦点。点击目标至少48dp，大字体允许重排，强调按钮前景随主题保证可读性。

实施分三步：①状态与录音/草稿等价整理；②覆盖布局、IME协调与玻璃呈现；③性能分级与系统导航/无障碍回归。首期即能在当前SDK构建，不依赖未来高版本材质。

| 验收项 | 内容 |
|---|---|
| 全功能 | 文本、@/所有人、整块删除、草稿重进、Emoji、贴纸、9图多选、拍照/小视频、文件、位置、各会话已有通话入口 |
| 编辑保持 | 正文+@切面板、发附件、选择器取消、后台恢复；更多始终可用，选区与span不丢 |
| 录音 | 正常、过短、60秒超时、上滑/回滑、ACTION_CANCEL、多指、权限拒绝、音频焦点丢失、退后台、快速重复按压，无误发送/重复发送 |
| 几何 | 320/360/常见大屏dp、横屏、分屏、三键/手势导航、大字体、外接/浮动键盘、快速切换，列表不双抬升 |
| 背景采样 | 滚动底部不同颜色测试行确认来源；多行改变区域；连续顶/底拉伸无回跳；透明空隙触摸消息正常 |
| 材质/性能 | 浅深主题、低内存回退、长列表/媒体消息、来消息同时切面板；静止无持续采样，线程/Bitmap退出后释放 |
| 共享回归 | 首页三个Tab采样来源、滚动回弹、前后台、静止绘制，防止共享渲染器重构影响已有行为 |

自动化重点：状态转移、草稿span恢复、取消永不发送、超时只消费一次；原生测试覆盖真实采样来源与EdgeEffect。构建使用JDK11执行`:chat:assembleDebug`，测试按实际变更配置；真机验收后记录结果，不用既有导航验证记录替代。当前构建与验证结果见 `docs/ui/玻璃态消息输入面板.md`，未覆盖的场景仍按本节逐项验收。

## 11. 资料与限制

已核对本地源码和 `docs/ui/玻璃态底部导航.md`。Android官方站点本次两次访问均连接失败，因此不把未在线核实的新平台API作为落地前提；未来引入时再核实对应SDK与AndroidX要求。参考入口：

- 输入法可见性：https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
- 无障碍：https://developer.android.com/guide/topics/ui/accessibility/apps
- RenderEffect：https://developer.android.com/reference/android/graphics/RenderEffect

文中的尺寸、阈值、交互与性能分级均为本工程设计建议。
