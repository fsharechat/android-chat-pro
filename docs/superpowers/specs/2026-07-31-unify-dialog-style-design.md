# 统一对话框样式 + 底部 tab 填充图标 — 设计文档

日期：2026-07-31

## 背景

`ServerInfoDialog`（`chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java`）是最近新做的「当前连接服务」弹窗，样式跟项目的设计 token 走：16dp 圆角 `surface` 卡片、`fog` 发丝分割线、底部两个等宽按钮（次要 `paper` 底 + `ink` 字，主要 `cobalt` 底 + 白字）。

应用里其余所有弹窗是 afollestad `material-dialogs:core:0.9.6.0`（chat 模块 25 处）和一个系统 `AlertDialog`（imagepicker 模块 1 处），视觉与上面这套完全不同。

**MaterialDialog 0.9.6.0 无法被主题化成这个样式**：它的 `md_*` 属性只覆盖颜色，没有圆角支持，且按钮固定是右对齐的扁平文字按钮，与两个等宽填充按钮结构上就不同。因此方案是自建弹窗组件并全量替换，而不是调主题。

顺带处理一个独立需求：底部 tab 选中时图标应像微信一样填充，而不是只换描线图标的 tint 颜色。

## 目标

1. 应用内所有弹窗视觉统一到 `ServerInfoDialog` 那套卡片样式。
2. 长按会话 / 长按消息 / 会话扩展菜单改为微信式底部弹出菜单。
3. 替换完成后移除 afollestad 依赖。
4. 底部 tab 选中态使用填充版图标。

## 非目标

- 不改 `ServerInfoDialog` 自身的交互与两状态逻辑，只让它复用提升后的公共 style。
- 不动 Toast、Snackbar、PopupWindow 等非对话框 UI。
- 不为其他模块（`p2penginekit` / `emojilibrary` / `client` / `push`）做弹窗改造 —— 这些模块目前没有任何弹窗。

---

## Part A — 弹窗组件

新包：`cn.wildfire.chat.kit.widget.dialog`（chat 模块）。

### A1. `FshareDialog` + `FshareDialog.Builder`

居中卡片弹窗，`extends Dialog`，构造时传 `R.style.FshareDialog`。窗口宽度 = 屏宽 86%（与 `ServerInfoDialog` 一致），高度 wrap content。

Builder API：

| 方法 | 说明 |
|---|---|
| `title(CharSequence)` | 可选。不设置时整个标题行与其下分割线 `GONE` |
| `content(CharSequence)` | 正文文本 |
| `customView(View)` | 用外部 View 替代正文区 |
| `input(hint, prefill, InputCallback)` | 正文区放一个 EditText；点主按钮时回调输入内容 |
| `secondary(CharSequence, OnClickListener)` | 左按钮；listener 为 null 时默认 dismiss |
| `primary(CharSequence, OnClickListener)` | 右按钮；listener 为 null 时默认 dismiss |
| `cancelable(boolean)` | 同时控制返回键与点击外部 |
| `build()` / `show()` | |

按钮布局规则：
- 两个按钮都设置 → 等宽并排，中间 12dp 间距（与 `server_info_dialog.xml` 一致）
- 只设置 `primary` → 主按钮占满整行
- 一个都不设置 → 整个按钮区与其上分割线 `GONE`

`content` / `customView` / `input` 三者互斥，同时设置时以最后一次调用为准。

**不带连接状态色点** —— `statusDot` 是 `ServerInfoDialog` 专有的，不进公共组件。

按钮点击默认自动 dismiss。`input` 场景下主按钮回调先执行再 dismiss。

### A2. `FshareLoadingDialog`

无标题、无按钮的加载弹窗。同款 16dp 圆角 `surface` 卡片，内容为一行：`ProgressBar`（indeterminate，`cobalt` 着色）+ 文案（`ink`）。宽度 wrap content 而非 86%，四周 20dp padding。

```java
FshareLoadingDialog dialog = FshareLoadingDialog.show(context, "登录中...", false);
// ...
dialog.dismiss();
```

第三个参数为 `cancelable`。文案可为空（对应 `GroupInfoActivity` 只有 spinner 没有文案的用法）。

### A3. `FshareBottomSheetDialog`

微信式底部菜单。基于 material 库的 `com.google.android.material.bottomsheet.BottomSheetDialog`（已在依赖中：`com.google.android.material:material:1.0.0-beta01`），复用其升起动画与下拉关闭手势，`setContentView` 塞自定义布局，并把 sheet 的默认背景设成透明，让圆角由我们的 drawable 提供。

结构：
- 菜单卡片：`bg_dialog_sheet`（上两角 12dp 圆角、`surface` 填充），满宽贴底
- 菜单项：52dp 行高，`ink` 文字 16sp 居中，项间 `fog` 分割线，`bg_dialog_list_item` ripple
- 8dp `paper` 间隔
- 「取消」整行按钮：52dp，`surface` 底，`ink` 文字，同样 ripple
- 底部留 `navigationBarHeight` 安全区（沿用系统 inset，不硬编码）

```java
new FshareBottomSheetDialog(context)
        .items(titles)
        .callback((position, text) -> { ... })
        .show();
```

点击任一菜单项后先 dismiss 再回调。

### A4. 共享资源

**Style**
- `R.style.FshareDialog` —— 由现有 `R.style.ServerInfoDialog`（`chat/src/main/res/values/styles.xml:77`）改名提升为通用。原有注释（关于父主题必须是 `Theme.AppCompat.DayNight.Dialog` 的坑）一并保留。`ServerInfoDialog.java:69` 改为引用 `R.style.FshareDialog`，`ServerInfoDialog` 这个 style 名删除。
- `R.style.FshareBottomSheetDialog` —— parent `Theme.Design.Light.BottomSheetDialog` 的 DayNight 版本，`bottomSheetStyle` 指向透明背景的 sheet style。

**Drawable（新增）**
- `bg_dialog_sheet.xml` —— 上两角 12dp 圆角，`@color/surface`
- `bg_dialog_button_full.xml` —— 整行主按钮，10dp 圆角 `cobalt` + `cobalt_pressed` ripple
- `bg_dialog_list_item.xml` —— `@color/surface` 底 + `@color/fog` ripple

**Drawable（复用，不改）**
- `bg_dialog_card.xml`、`bg_dialog_button_primary.xml`、`bg_dialog_button_secondary.xml`

**Layout（新增）**
- `fshare_dialog.xml` —— 标题行 / 分割线 / 内容容器 / 分割线 / 按钮行
- `fshare_dialog_loading.xml`
- `fshare_bottom_sheet.xml`
- `fshare_bottom_sheet_item.xml`

所有颜色走现有 token（`ink` / `surface` / `paper` / `fog` / `mist` / `cobalt`），`values-night/colors.xml` 自动适配深色模式，不新增颜色。

### A5. 调用点替换清单（chat 模块 25 处）

**确认框 → `FshareDialog`（8 处）**

| 文件 | 行 | 内容 |
|---|---|---|
| `app/main/MainActivity.java` | 422 | 修改个人昵称？ |
| `kit/conversationlist/ConversationListAdapter.java` | 261 | 上下文菜单二次确认（嵌套在列表回调里） |
| `kit/conversationlist/ConversationListFragment.java` | 204 | 退群/解散后删除会话确认 |
| `kit/conversation/ConversationMessageAdapter.java` | 360 | 消息上下文菜单二次确认（嵌套在列表回调里） |
| `kit/conversation/GroupConversationInfoFragment.java` | 271 | 解散群组 |
| `kit/conversation/GroupConversationInfoFragment.java` | 292 | 退出群组 |
| `kit/conversation/message/viewholder/NormalMessageContentViewHolder.java` | 121 | 重新发送? |
| `kit/setting/ThemeSettingActivity.java` | 63 | 切换主题 |

**加载框 → `FshareLoadingDialog`（12 处）**

| 文件 | 行 |
|---|---|
| `app/main/PCLoginActivity.java` | 55 |
| `app/login/SMSLoginActivity.java` | 95 |
| `app/login/LoginActivity.java` | 87 |
| `kit/group/AddGroupMemberActivity.java` | 94 |
| `kit/group/SetGroupNameActivity.java` | 84 |
| `kit/group/GroupInfoActivity.java` | 102 |
| `kit/group/RemoveGroupMemberActivity.java` | 63 |
| `kit/user/ChangeMyNameActivity.java` | 92 |
| `kit/channel/ChannelInfoActivity.java` | 105 |
| `kit/channel/CreateChannelActivity.java` | 107 |
| `kit/conversation/CreateConversationActivity.java` | 42 |
| `kit/conversation/forward/PickConversationTargetToForwardActivity.java` | 28 |

注意 `GroupInfoActivity` 用一个 `dialog` 成员字段配合 `actionCount` 做引用计数（`showLoading()` / `hideLoading()`），替换时保持这套计数逻辑不变，只换 dialog 类型。

**菜单 → `FshareBottomSheetDialog`（3 处）**

| 文件 | 行 | 场景 |
|---|---|---|
| `kit/conversationlist/ConversationListAdapter.java` | 255 | 长按会话 |
| `kit/conversation/ConversationMessageAdapter.java` | 354 | 长按消息 |
| `kit/conversation/ext/core/ConversationExtension.java` | 64 | 会话扩展菜单 |

前两处的回调里嵌套了确认框（见上表），底部菜单 dismiss 后再弹居中确认卡片。

**输入框 → `FshareDialog.input()`（1 处）**
- `kit/conversation/GroupConversationInfoFragment.java:240` —— 修改群昵称

原代码里输入回调（`InputCallback`）和 `onPositive` 各写了一半逻辑（`onPositive` 只做 dismiss）。迁移后统一收拢到 `primary()` 的回调里。

**自定义 View → `FshareDialog.customView()`（1 处）**
- `kit/conversation/forward/ForwardActivity.java:149` —— `ForwardPromptView`

**清理**：`kit/conversationlist/viewholder/ConversationViewHolder.java:180` 是整段注释掉的死代码，无需迁移，连同该文件的 MaterialDialog import 一并删除。

### A6. imagepicker 模块（1 处）

`imagepicker/src/main/java/com/lqr/imagepicker/ui/ImagePreviewDelActivity.java:48` 的「要删除这张照片吗？」系统 `AlertDialog`。

模块依赖是单向的（`chat` → `imagepicker`，见 `chat/build.gradle:127`），`imagepicker` 引用不到 chat 里的组件。

**决策：在 `imagepicker` 内复制一份最小实现**，而不是为一个弹窗新建 `uikit` 基础 module。

复制内容：
- `SimpleConfirmDialog.java`（只支持 title / content / 两按钮，无 input、无 customView、无 list）
- `bg_dialog_card.xml` / `bg_dialog_button_primary.xml` / `bg_dialog_button_secondary.xml`
- `simple_confirm_dialog.xml`
- style `SimpleConfirmDialog`
- 颜色 token：imagepicker 有自己的 `colors.xml`，新增 `ink` / `surface` / `paper` / `fog` / `cobalt` / `cobalt_pressed` 五六个值，数值与 chat 保持一致，并加 `values-night` 版本

**已知代价**：这套卡片资源在两个 module 各存一份，将来调整弹窗样式需要改两处。文件头加注释指明与 chat 模块的对应关系。若后续有第三个模块需要弹窗，再抽 `uikit` module。

### A7. 移除依赖

25 处全部替换后，从 `chat/build.gradle:114` 移除 `implementation 'com.afollestad.material-dialogs:core:0.9.6.0'`，并确认全项目无 `com.afollestad` import 残留。

---

## Part B — 底部 tab 填充图标

当前 `chat/src/main/res/menu/main_bottom_navigation.xml` 的三个 item 直接指向描线版 vector（`ic_nav_chat` / `ic_nav_contacts` / `ic_nav_me`），选中态只由 `app:itemIconTint="@color/bottom_nav_color"` 换颜色（`cobalt` ↔ `mist`），图标形状不变。

改法：

1. **新增填充版 vector**（24dp，`viewport 24`，`fillColor="#FF000000"`，实际颜色由 tint 决定）
   - `ic_nav_chat_filled.xml` —— Material `chat_bubble` 实心版
   - `ic_nav_contacts_filled.xml` —— Material `people` 实心版
   - `ic_nav_me_filled.xml` —— Material `person` 实心版

2. **新增 3 个 state selector**（`drawable/`）
   - `ic_nav_chat_state.xml`、`ic_nav_contacts_state.xml`、`ic_nav_me_state.xml`
   - `<item android:drawable="@drawable/ic_nav_xxx_filled" android:state_checked="true" />`
   - `<item android:drawable="@drawable/ic_nav_xxx" />`（兜底项不写 state，保证任何状态都能匹配）

3. **menu 改指向 selector**：`main_bottom_navigation.xml` 三个 `android:icon` 分别改为对应的 `_state`。

4. **`itemIconTint` 保持不变**（仍是 `@color/bottom_nav_color`）—— 填充图标在选中时被着成 `cobalt`，未选中描线图标着成 `mist`，与现有配色一致，无需改 `colors.xml`。

5. **删除 4 个废弃 selector**（全项目零引用，且引用的是旧设计的 mipmap 资源）：
   - `drawable/bottom_nav_message_selector.xml`
   - `drawable/bottom_nav_contacts_selector.xml`
   - `drawable/bottom_nav_me_selector.xml`
   - `drawable/bottom_nav_discovery_selector.xml`

`MainActivity` 里操作 `BottomNavigationMenuView` 子 View 做角标定位的代码（`MainActivity.java:117` / `:155` / `:170`）不受影响 —— 它只动角标 View 的位置，不碰图标 drawable。

---

## 验证

自动化：`./gradlew :chat:assembleDebug` 通过，且全项目 grep 不到 `com.afollestad` 与 `MaterialDialog`。

手动（真机，深浅色各走一遍）：

| # | 路径 | 预期 |
|---|---|---|
| 1 | 首页顶栏「飞享IM」 | `ServerInfoDialog` 与改造前完全一致（回归） |
| 2 | 登录页点登录 | 加载框为圆角卡片 + spinner |
| 3 | 长按会话 | 底部升起菜单，选「删除会话」后弹居中确认卡片 |
| 4 | 长按消息 | 底部升起菜单 |
| 5 | 会话输入框「+」扩展菜单 | 底部升起菜单 |
| 6 | 群聊 → 我在本群的昵称 | 输入弹窗，改名生效 |
| 7 | 群聊 → 退出/解散群组 | 确认卡片，两个等宽按钮 |
| 8 | 转发消息 | `ForwardPromptView` 正常嵌入卡片，能带附言发送 |
| 9 | 设置 → 主题切换 | 确认卡片，确认后应用重启 |
| 10 | 消息发送失败点重发 | 确认卡片 |
| 11 | 图片预览删除照片 | imagepicker 弹窗与 chat 视觉一致 |
| 12 | 底部三个 tab 逐个点 | 选中项图标为填充版，颜色 `cobalt`；未选中为描线版 `mist` |
| 13 | tab 有未读时 | 红色角标位置不变（回归） |

## 风险

- **深色模式回归**：`FshareDialog` style 必须继承 `Theme.AppCompat.DayNight.Dialog`，并显式指定 `colorControlNormal` / `colorControlActivated`。这是 `ServerInfoDialog` 已经踩过的坑（见 `styles.xml` 现有注释），提升为公共 style 时原样保留。
- **BottomSheetDialog 版本**：依赖是 `material:1.0.0-beta01`，`BottomSheetDialog` 在此版本可用，但需实际验证透明背景 + 自定义圆角的写法（`R.id.design_bottom_sheet` 的背景要设成 transparent）。若该 beta 版本行为有问题，退回手写 `Dialog` + `Gravity.BOTTOM` + `windowAnimationStyle` 滑入动画。
- **`GroupInfoActivity` 的引用计数**：`showLoading()`/`hideLoading()` 配对逻辑替换时容易改错，导致 loading 不消失。
- **imagepicker 资源重复**：已知并接受，见 A6。
