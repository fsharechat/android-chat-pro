# 统一对话框样式 + 底部 tab 填充图标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把应用里全部 26 处弹窗统一到「当前连接服务」弹窗那套卡片样式，并让底部 tab 选中时使用填充版图标。

**Architecture:** 在 `chat` 模块新建 `cn.wildfire.chat.kit.widget.dialog` 包，提供三个组件 —— `FshareDialog`（居中卡片，覆盖确认/输入/自定义 View）、`FshareLoadingDialog`（无按钮加载卡片）、`FshareBottomSheetDialog`（微信式底部菜单）。三者复用 `ServerInfoDialog` 已有的卡片与按钮 drawable 和设计 token。`imagepicker` 模块因依赖方向单向（`chat` → `imagepicker`）引用不到，内部复制一份最小实现。全部替换完成后移除 afollestad 依赖。

**Tech Stack:** Java 8, Android SDK 29, AndroidX AppCompat, `com.google.android.material:material:1.0.0-beta01`（提供 `BottomSheetDialog`）, ButterKnife。

## Global Constraints

- **构建命令必须带 JDK 11 前缀**（默认 java 是 17，Gradle 会报 Groovy ReflectionCache 错误）：
  `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug`
- **adb 不在 PATH**，完整路径 `/Users/liaojinlong/Library/Android/sdk/platform-tools/adb`；常用真机为魅族 20 Pro（序列号 `391QYGBS22288`）。
- **不自动 commit**。每个 Task 末尾只做 `git add`，由用户自己执行 `git commit`。计划里给出建议的 commit message 供用户直接使用。
- **颜色一律走现有 token**：`ink`（主文字）、`mist`（次要文字）、`surface`（卡片底）、`paper`（页面底/次按钮底）、`fog`（分割线）、`cobalt` / `cobalt_pressed`（品牌蓝）、`text_color_white`（按钮白字）。禁止硬编码色值。`values/colors.xml` 与 `values-night/colors.xml` 已成对定义这些 token，深色模式自动适配。
- **弹窗 style 的父主题必须是 `Theme.AppCompat.DayNight.Dialog`**。`Dialog(Context, themeResId)` 会把整条父链的主题属性以 `force=true` 盖到 Activity 主题上，固定深色父主题会让 `RadioButton` / `ProgressBar` / ripple 这些靠主题属性取色的控件在浅色模式下取到深色配色。同时必须显式指定 `colorAccent` / `colorControlNormal` / `colorControlActivated`。
- **无单元测试**：本仓库只有 `junit:junit:4.12`（`chat/build.gradle:129`），没有 Robolectric / instrumentation 配置。Dialog 与 View 代码在此环境下无法写可运行的单元测试。每个 Task 的验收门槛是「编译通过 + 指定的真机手动验证项」，请如实记录验证结果，不要跳过。
- **深浅色都要验**：每个手动验证项都要在浅色和深色模式各走一遍（设置 → 主题切换）。

---

### Task 1: 公共资源层（style 提升 + 新增 drawable）

把 `ServerInfoDialog` 专用的 style 提升为通用 style，并补齐后续任务要用的 drawable。本任务不新增任何 Java 类，交付物是「连接服务弹窗改用新 style 后行为完全不变」。

**Files:**
- Modify: `chat/src/main/res/values/styles.xml`（`ServerInfoDialog` style，约 73-88 行）
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java:69`
- Create: `chat/src/main/res/drawable/bg_dialog_sheet.xml`
- Create: `chat/src/main/res/drawable/bg_dialog_button_full.xml`
- Create: `chat/src/main/res/drawable/bg_dialog_list_item.xml`

**Interfaces:**
- Consumes: 无
- Produces: `R.style.FshareDialog`、`R.style.FshareBottomSheetDialog`、`R.drawable.bg_dialog_sheet`、`R.drawable.bg_dialog_button_full`、`R.drawable.bg_dialog_list_item`

- [ ] **Step 1: 把 `ServerInfoDialog` style 改名为 `FshareDialog` 并新增底部菜单 style**

编辑 `chat/src/main/res/values/styles.xml`，把现有这段（注释一并保留，只改 `name`）：

```xml
    <!--连接服务弹窗：无标题栏、透明窗口背景，圆角由卡片 drawable 提供-->
    <!--父主题必须用 DayNight 版本：Dialog(Context, themeResId) 会把整条父链的主题属性
        以 force=true 盖到 Activity 主题上，固定深色父主题会让 RadioButton/ProgressBar/ripple
        这些靠主题属性取色的控件在浅色模式下取到深色配色（未选中圆环几乎不可见等）。
        这里显式指定控件色，跟随本项目的 token（values/colors.xml 与 values-night/colors.xml 会自动适配深浅色）。-->
    <style name="ServerInfoDialog" parent="Theme.AppCompat.DayNight.Dialog">
```

替换为：

```xml
    <!--应用统一弹窗样式：无标题栏、透明窗口背景，圆角由卡片 drawable 提供。
        FshareDialog / FshareLoadingDialog / ServerInfoDialog 共用。-->
    <!--父主题必须用 DayNight 版本：Dialog(Context, themeResId) 会把整条父链的主题属性
        以 force=true 盖到 Activity 主题上，固定深色父主题会让 RadioButton/ProgressBar/ripple
        这些靠主题属性取色的控件在浅色模式下取到深色配色（未选中圆环几乎不可见等）。
        这里显式指定控件色，跟随本项目的 token（values/colors.xml 与 values-night/colors.xml 会自动适配深浅色）。-->
    <style name="FshareDialog" parent="Theme.AppCompat.DayNight.Dialog">
```

style 体内的 item 一行都不改，保持：

```xml
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:backgroundDimEnabled">true</item>
        <item name="colorAccent">@color/cobalt</item>
        <item name="android:colorControlNormal">@color/mist</item>
        <item name="colorControlNormal">@color/mist</item>
        <item name="colorControlActivated">@color/cobalt</item>
    </style>
```

然后在它下面追加底部菜单用的两个 style：

```xml
    <!--底部弹出菜单：让 material 的 BottomSheetDialog 容器透明，圆角交给 bg_dialog_sheet-->
    <style name="FshareBottomSheetDialog" parent="Theme.Design.Light.BottomSheetDialog">
        <item name="bottomSheetStyle">@style/FshareBottomSheetStyle</item>
        <item name="android:backgroundDimEnabled">true</item>
        <item name="colorAccent">@color/cobalt</item>
    </style>

    <style name="FshareBottomSheetStyle" parent="Widget.Design.BottomSheet.Modal">
        <item name="android:background">@android:color/transparent</item>
    </style>
```

- [ ] **Step 2: 改 `ServerInfoDialog` 的 style 引用**

编辑 `chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java:69`：

```java
        super(activity, R.style.ServerInfoDialog);
```

改为：

```java
        super(activity, R.style.FshareDialog);
```

- [ ] **Step 3: 新增 `bg_dialog_sheet.xml`（底部菜单卡片，上两角圆角）**

创建 `chat/src/main/res/drawable/bg_dialog_sheet.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners
        android:topLeftRadius="12dp"
        android:topRightRadius="12dp" />
</shape>
```

- [ ] **Step 4: 新增 `bg_dialog_button_full.xml`（整行主按钮）**

创建 `chat/src/main/res/drawable/bg_dialog_button_full.xml`。形状与 `bg_dialog_button_primary.xml` 一致，单独一份是为了让「只有一个按钮」的场景在语义上区分开，将来可以独立调整圆角：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="@color/cobalt_pressed">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/cobalt" />
            <corners android:radius="10dp" />
        </shape>
    </item>
</ripple>
```

- [ ] **Step 5: 新增 `bg_dialog_list_item.xml`（菜单项 ripple）**

创建 `chat/src/main/res/drawable/bg_dialog_list_item.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="@color/fog">
    <item android:drawable="@color/surface" />
</ripple>
```

- [ ] **Step 6: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

预期：BUILD SUCCESSFUL。若报 `resource style/ServerInfoDialog not found`，说明还有别处引用了旧 style 名，用
`grep -rn "ServerInfoDialog" chat/src/main/res chat/src/main/java` 找出来改掉（注意 `ServerInfoDialog.java` 这个类名本身不要动）。

- [ ] **Step 7: 真机验证**

```bash
/Users/liaojinlong/Library/Android/sdk/platform-tools/adb -s 391QYGBS22288 install -r chat/build/outputs/apk/debug/*.apk
```

验证项（浅色 + 深色各一遍）：
1. 首页顶栏点「飞享IM」→ 弹窗出现，圆角、分割线、两个按钮外观与改造前一致
2. 点「切换节点」→ 节点列表的 RadioButton 未选中圆环在浅色模式下清晰可见（这是 style 父主题那个坑的回归点）
3. 点「返回」→ 回到信息态；点「关闭」→ 弹窗消失

- [ ] **Step 8: 暂存改动（不 commit）**

```bash
git add chat/src/main/res/values/styles.xml \
        chat/src/main/java/cn/wildfire/chat/kit/net/ServerInfoDialog.java \
        chat/src/main/res/drawable/bg_dialog_sheet.xml \
        chat/src/main/res/drawable/bg_dialog_button_full.xml \
        chat/src/main/res/drawable/bg_dialog_list_item.xml
```

建议 commit message：`refactor: 将连接服务弹窗 style 提升为通用 FshareDialog 并补齐弹窗 drawable`

---

### Task 2: `FshareDialog` 核心组件 + 迁移 8 处确认框

**Files:**
- Create: `chat/src/main/res/layout/fshare_dialog.xml`
- Create: `chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareDialog.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/app/main/MainActivity.java:421-436`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListAdapter.java:261-278`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListFragment.java:204-220`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationMessageAdapter.java:360-377`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/GroupConversationInfoFragment.java:271-291`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/GroupConversationInfoFragment.java:292-312`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/NormalMessageContentViewHolder.java:121-128`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/setting/ThemeSettingActivity.java:62-81`

**Interfaces:**
- Consumes: `R.style.FshareDialog`（Task 1）、`R.drawable.bg_dialog_button_full`（Task 1）、已有的 `R.drawable.bg_dialog_card` / `bg_dialog_button_primary` / `bg_dialog_button_secondary`
- Produces:
  - `cn.wildfire.chat.kit.widget.dialog.FshareDialog`
  - `FshareDialog.Builder(Context)`，链式方法 `title(CharSequence)`、`content(CharSequence)`、`customView(View)`、`input(CharSequence hint, CharSequence prefill)`、`secondary(CharSequence, FshareDialog.OnClickListener)`、`primary(CharSequence, FshareDialog.OnClickListener)`、`cancelable(boolean)`、`build()` 返回 `FshareDialog`、`show()` 返回 `FshareDialog`
  - `interface FshareDialog.OnClickListener { void onClick(FshareDialog dialog); }`
  - `FshareDialog.getInputText()` 返回 `String`
  - Task 3 会用到 `customView(View)` 与 `input(...)` / `getInputText()`；本任务把它们一并实现，Task 3 只做调用点迁移。

- [ ] **Step 1: 创建布局 `fshare_dialog.xml`**

创建 `chat/src/main/res/layout/fshare_dialog.xml`。结构对齐 `server_info_dialog.xml`（标题行 20dp padding、`fog` 分割线、按钮行 12dp padding、按钮 44dp 高、中间 12dp 间距）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_dialog_card"
    android:orientation="vertical">

    <TextView
        android:id="@+id/dialogTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fontFamily="sans-serif-medium"
        android:paddingStart="20dp"
        android:paddingTop="20dp"
        android:paddingEnd="20dp"
        android:paddingBottom="16dp"
        android:textColor="@color/ink"
        android:textSize="18sp"
        android:visibility="gone" />

    <View
        android:id="@+id/titleDivider"
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/fog"
        android:visibility="gone" />

    <!--正文区：content 文本、input 输入框或外部 customView 三选一，由代码填充-->
    <FrameLayout
        android:id="@+id/contentContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />

    <View
        android:id="@+id/buttonDivider"
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/fog" />

    <LinearLayout
        android:id="@+id/buttonBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp">

        <TextView
            android:id="@+id/secondaryButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:background="@drawable/bg_dialog_button_secondary"
            android:gravity="center"
            android:textColor="@color/ink"
            android:textSize="15sp"
            android:visibility="gone" />

        <TextView
            android:id="@+id/primaryButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:background="@drawable/bg_dialog_button_primary"
            android:gravity="center"
            android:textColor="@color/text_color_white"
            android:textSize="15sp"
            android:visibility="gone" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 2: 创建 `FshareDialog.java`**

创建 `chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareDialog.java`：

```java
package cn.wildfire.chat.kit.widget.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.wildfirechat.chat.R;

/**
 * 应用统一样式的居中卡片弹窗。样式基准是「当前连接服务」弹窗
 * （{@link cn.wildfire.chat.kit.net.ServerInfoDialog}）：16dp 圆角 surface 卡片、
 * fog 分割线、底部等宽双按钮。
 *
 * <p>正文区支持三选一：{@link Builder#content}、{@link Builder#input}、
 * {@link Builder#customView}，同时设置时以最后一次调用为准。
 *
 * <p>按钮点击回调返回后自动 dismiss；只设置 primary 时它占满整行。
 */
public class FshareDialog extends Dialog {

    /** 弹窗宽度占屏宽的比例，与 ServerInfoDialog 保持一致。 */
    private static final float WIDTH_RATIO = 0.86f;

    public interface OnClickListener {
        void onClick(FshareDialog dialog);
    }

    /** 正文区三选一的模式标记。 */
    private enum ContentMode {
        NONE, TEXT, INPUT, CUSTOM
    }

    private final Builder builder;
    private EditText inputView;

    private FshareDialog(@NonNull Builder builder) {
        super(builder.context, R.style.FshareDialog);
        this.builder = builder;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_dialog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (getContext().getResources().getDisplayMetrics().widthPixels * WIDTH_RATIO),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        setCancelable(builder.cancelable);
        setCanceledOnTouchOutside(builder.cancelable);

        bindTitle();
        bindContent();
        bindButtons();
    }

    private void bindTitle() {
        TextView titleView = findViewById(R.id.dialogTitle);
        View titleDivider = findViewById(R.id.titleDivider);
        if (TextUtils.isEmpty(builder.title)) {
            titleView.setVisibility(View.GONE);
            titleDivider.setVisibility(View.GONE);
            return;
        }
        titleView.setText(builder.title);
        titleView.setVisibility(View.VISIBLE);
        titleDivider.setVisibility(View.VISIBLE);
    }

    private void bindContent() {
        FrameLayout container = findViewById(R.id.contentContainer);
        switch (builder.contentMode) {
            case TEXT:
                container.addView(createContentTextView());
                break;
            case INPUT:
                inputView = createInputView();
                container.addView(inputView);
                break;
            case CUSTOM: {
                // customView 可能被上一次 show 挂过 parent，先摘下来再挂
                ViewGroup parent = (ViewGroup) builder.customView.getParent();
                if (parent != null) {
                    parent.removeView(builder.customView);
                }
                container.addView(builder.customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                break;
            }
            case NONE:
            default:
                container.setVisibility(View.GONE);
                break;
        }
    }

    private TextView createContentTextView() {
        TextView view = new TextView(getContext());
        view.setText(builder.content);
        view.setTextSize(15);
        view.setLineSpacing(dp(4), 1f);
        view.setTextColor(getContext().getResources().getColor(R.color.ink));
        // 无标题时正文自己撑起顶部留白，有标题时标题行已经留过了
        int top = TextUtils.isEmpty(builder.title) ? dp(20) : dp(4);
        view.setPadding(dp(20), top, dp(20), dp(20));
        view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private EditText createInputView() {
        EditText view = new EditText(getContext());
        view.setHint(builder.inputHint);
        view.setText(builder.inputPrefill);
        view.setSingleLine(true);
        view.setTextSize(15);
        view.setTextColor(getContext().getResources().getColor(R.color.ink));
        view.setHintTextColor(getContext().getResources().getColor(R.color.mist));
        if (!TextUtils.isEmpty(builder.inputPrefill)) {
            view.setSelection(builder.inputPrefill.length());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), TextUtils.isEmpty(builder.title) ? dp(20) : dp(4),
                dp(20), dp(20));
        view.setLayoutParams(lp);
        return view;
    }

    private void bindButtons() {
        TextView secondary = findViewById(R.id.secondaryButton);
        TextView primary = findViewById(R.id.primaryButton);
        View buttonDivider = findViewById(R.id.buttonDivider);
        LinearLayout buttonBar = findViewById(R.id.buttonBar);

        boolean hasSecondary = !TextUtils.isEmpty(builder.secondaryText);
        boolean hasPrimary = !TextUtils.isEmpty(builder.primaryText);

        if (!hasSecondary && !hasPrimary) {
            buttonDivider.setVisibility(View.GONE);
            buttonBar.setVisibility(View.GONE);
            return;
        }

        if (hasSecondary) {
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(builder.secondaryText);
            secondary.setOnClickListener(v -> {
                if (builder.secondaryListener != null) {
                    builder.secondaryListener.onClick(FshareDialog.this);
                }
                dismiss();
            });
        }

        if (hasPrimary) {
            primary.setVisibility(View.VISIBLE);
            primary.setText(builder.primaryText);
            primary.setOnClickListener(v -> {
                if (builder.primaryListener != null) {
                    builder.primaryListener.onClick(FshareDialog.this);
                }
                dismiss();
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) primary.getLayoutParams();
            if (hasSecondary) {
                lp.setMarginStart(dp(12));
            } else {
                // 只有一个按钮时占满整行，用不带左边距的整行背景
                lp.setMarginStart(0);
                primary.setBackgroundResource(R.drawable.bg_dialog_button_full);
            }
            primary.setLayoutParams(lp);
        }
    }

    /** 仅在用 {@link Builder#input} 构建时有意义，其余情况返回空串。 */
    @NonNull
    public String getInputText() {
        return inputView == null ? "" : inputView.getText().toString();
    }

    private int dp(int value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    public static class Builder {
        private final Context context;

        private CharSequence title;
        private CharSequence content;
        private View customView;
        private CharSequence inputHint;
        private CharSequence inputPrefill;
        private ContentMode contentMode = ContentMode.NONE;

        private CharSequence secondaryText;
        private OnClickListener secondaryListener;
        private CharSequence primaryText;
        private OnClickListener primaryListener;
        private boolean cancelable = true;

        public Builder(@NonNull Context context) {
            this.context = context;
        }

        public Builder title(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder content(CharSequence content) {
            this.content = content;
            this.contentMode = ContentMode.TEXT;
            return this;
        }

        public Builder customView(@NonNull View view) {
            this.customView = view;
            this.contentMode = ContentMode.CUSTOM;
            return this;
        }

        public Builder input(CharSequence hint, CharSequence prefill) {
            this.inputHint = hint;
            this.inputPrefill = prefill;
            this.contentMode = ContentMode.INPUT;
            return this;
        }

        public Builder secondary(CharSequence text, @Nullable OnClickListener listener) {
            this.secondaryText = text;
            this.secondaryListener = listener;
            return this;
        }

        public Builder primary(CharSequence text, @Nullable OnClickListener listener) {
            this.primaryText = text;
            this.primaryListener = listener;
            return this;
        }

        public Builder cancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        public FshareDialog build() {
            return new FshareDialog(this);
        }

        public FshareDialog show() {
            FshareDialog dialog = build();
            dialog.show();
            return dialog;
        }
    }
}
```

- [ ] **Step 3: 迁移 `MainActivity.java:421` — 修改个人昵称**

把这段：

```java
    private void updateDisplayName() {
        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .content("修改个人昵称？")
                .positiveText("修改")
                .negativeText("取消")
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        Intent intent = new Intent(MainActivity.this, ChangeMyNameActivity.class);
                        startActivity(intent);
                    }
                }).build();
```

替换为（注意原代码 `.build()` 之后下一行应有 `dialog.show();`，保留它）：

```java
    private void updateDisplayName() {
        FshareDialog dialog = new FshareDialog.Builder(this)
                .content("修改个人昵称？")
                .secondary("取消", null)
                .primary("修改", d -> {
                    Intent intent = new Intent(MainActivity.this, ChangeMyNameActivity.class);
                    startActivity(intent);
                })
                .build();
```

import 改动：删 `com.afollestad.materialdialogs.DialogAction` 与 `com.afollestad.materialdialogs.MaterialDialog`（若该文件其它地方还在用则先留着，本任务结束前 Task 7 会统一清理），加
`import cn.wildfire.chat.kit.widget.dialog.FshareDialog;`

- [ ] **Step 4: 迁移 `ConversationListFragment.java:204` — 退群后删除会话确认**

把这段：

```java
                    MaterialDialog dialog = new MaterialDialog.Builder(getActivity())
                            .title(title)
                            .content(content)
                            .positiveText("确认")
                            .negativeText("取消")
                            .onPositive(((quitDialog, which) -> {
                                ConversationInfo conversationInfo = data.getParcelableExtra("conversationInfo");
                                if(conversationInfo != null){
                                    //重新获取conversation 不然当前退出群组时，未读消息为0，导致删除会话仍然又未读消息
                                    conversationInfo = ChatManager.Instance().getConversation(conversationInfo.conversation);
                                    conversationListViewModel.removeConversation(conversationInfo);
                                }
                            }))
                            .onNegative((quitDialog, which) -> {
                                quitDialog.dismiss();
                            }).build();
                    dialog.show();
```

替换为：

```java
                    FshareDialog dialog = new FshareDialog.Builder(getActivity())
                            .title(title)
                            .content(content)
                            .secondary("取消", null)
                            .primary("确认", d -> {
                                ConversationInfo conversationInfo = data.getParcelableExtra("conversationInfo");
                                if (conversationInfo != null) {
                                    //重新获取conversation 不然当前退出群组时，未读消息为0，导致删除会话仍然又未读消息
                                    conversationInfo = ChatManager.Instance().getConversation(conversationInfo.conversation);
                                    conversationListViewModel.removeConversation(conversationInfo);
                                }
                            })
                            .build();
                    dialog.show();
```

- [ ] **Step 5: 迁移 `ConversationListAdapter.java:261` — 会话上下文菜单二次确认**

把这段（在 `itemsCallback` 内部）：

```java
                                new MaterialDialog.Builder(fragment.getActivity())
                                        .content(menuItem.contextMenuItem.confirmPrompt())
                                        .negativeText("取消")
                                        .positiveText("确认")
                                        .onPositive(new MaterialDialog.SingleButtonCallback() {
                                            @Override
                                            public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                                try {
                                                    menuItem.method.invoke(viewHolder, itemView, conversationInfo);
                                                } catch (IllegalAccessException e) {
                                                    e.printStackTrace();
                                                } catch (InvocationTargetException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        })
                                        .build()
                                        .show();
```

替换为：

```java
                                new FshareDialog.Builder(fragment.getActivity())
                                        .content(menuItem.contextMenuItem.confirmPrompt())
                                        .secondary("取消", null)
                                        .primary("确认", d -> {
                                            try {
                                                menuItem.method.invoke(viewHolder, itemView, conversationInfo);
                                            } catch (IllegalAccessException e) {
                                                e.printStackTrace();
                                            } catch (InvocationTargetException e) {
                                                e.printStackTrace();
                                            }
                                        })
                                        .show();
```

外层的 `new MaterialDialog.Builder(...).items(titles).itemsCallback(...)` 本步**不动**，Task 5 处理。

- [ ] **Step 6: 迁移 `ConversationMessageAdapter.java:360` — 消息上下文菜单二次确认**

把这段（在 `itemsCallback` 内部；注意它 invoke 的第三个参数是 `message`，与 Step 5 的 `conversationInfo` 不同）：

```java
                                new MaterialDialog.Builder(mContext)
                                        .content(menuItem.contextMenuItem.confirmPrompt())
                                        .negativeText("取消")
                                        .positiveText("确认")
                                        .onPositive(new MaterialDialog.SingleButtonCallback() {
                                            @Override
                                            public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                                try {
                                                    menuItem.method.invoke(viewHolder, itemView, message);
                                                } catch (IllegalAccessException e) {
                                                    e.printStackTrace();
                                                } catch (InvocationTargetException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        })
                                        .build()
                                        .show();
```

替换为：

```java
                                new FshareDialog.Builder(mContext)
                                        .content(menuItem.contextMenuItem.confirmPrompt())
                                        .secondary("取消", null)
                                        .primary("确认", d -> {
                                            try {
                                                menuItem.method.invoke(viewHolder, itemView, message);
                                            } catch (IllegalAccessException e) {
                                                e.printStackTrace();
                                            } catch (InvocationTargetException e) {
                                                e.printStackTrace();
                                            }
                                        })
                                        .show();
```

外层的 `new MaterialDialog.Builder(mContext).items(titles).itemsCallback(...)` 本步**不动**，Task 5 处理。

- [ ] **Step 7: 迁移 `GroupConversationInfoFragment.java:271` — 解散群组**

把这段：

```java
            MaterialDialog dialog = new MaterialDialog.Builder(getActivity())
                    .title("解散群组")
                    .content("请确认是否要解散此群组,群组解散将不能恢复,请谨慎操作")
                    .positiveText("确认")
                    .negativeText("取消")
                    .onPositive(((quitDialog, which) -> {
                        groupViewModel.dismissGroup(conversationInfo.conversation.target,Collections.singletonList(0))
                                .observe(this, aBoolean -> {
                                    if (aBoolean != null && aBoolean) {
                                        getActivity().setResult(RESULT_REMOVE_GROUP_CONVERSATION_SUCCESS,new Intent().putExtra("conversationInfo",conversationInfo).putExtra("groupName",groupInfo.name));
                                        getActivity().finish();
                                    } else {
                                        Toast.makeText(getActivity(), "解散群组失败", Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }))
                    .onNegative((quitDialog, which) -> {
                        quitDialog.dismiss();
                    }).build();
            dialog.show();
```

替换为：

```java
            FshareDialog dialog = new FshareDialog.Builder(getActivity())
                    .title("解散群组")
                    .content("请确认是否要解散此群组,群组解散将不能恢复,请谨慎操作")
                    .secondary("取消", null)
                    .primary("确认", d -> {
                        groupViewModel.dismissGroup(conversationInfo.conversation.target, Collections.singletonList(0))
                                .observe(this, aBoolean -> {
                                    if (aBoolean != null && aBoolean) {
                                        getActivity().setResult(RESULT_REMOVE_GROUP_CONVERSATION_SUCCESS, new Intent().putExtra("conversationInfo", conversationInfo).putExtra("groupName", groupInfo.name));
                                        getActivity().finish();
                                    } else {
                                        Toast.makeText(getActivity(), "解散群组失败", Toast.LENGTH_SHORT).show();
                                    }
                                });
                    })
                    .build();
            dialog.show();
```

- [ ] **Step 8: 迁移 `GroupConversationInfoFragment.java:292` — 退出群组**

同一文件的 `else` 分支。把这段：

```java
            MaterialDialog dialog = new MaterialDialog.Builder(getActivity())
                    .title("退出群组")
                    .content("请确认是否要退出此群组")
                    .positiveText("确认")
                    .negativeText("取消")
                    .onPositive(((quitDialog, which) -> {
                        groupViewModel.quitGroup(conversationInfo.conversation.target, Collections.singletonList(0)).observe(this, aBoolean -> {
                            if (aBoolean != null && aBoolean) {
                                getActivity().setResult(RESULT_QUIT_GROUP_CONVERSATION_SUCCESS,new Intent().putExtra("conversationInfo",conversationInfo).putExtra("groupName",groupInfo.name));
                                getActivity().finish();
                            } else {
                                Toast.makeText(getActivity(), "退出群组失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }))
                    .onNegative((quitDialog, which) -> {
                        quitDialog.dismiss();
                    }).build();
            dialog.show();
```

替换为：

```java
            FshareDialog dialog = new FshareDialog.Builder(getActivity())
                    .title("退出群组")
                    .content("请确认是否要退出此群组")
                    .secondary("取消", null)
                    .primary("确认", d -> {
                        groupViewModel.quitGroup(conversationInfo.conversation.target, Collections.singletonList(0)).observe(this, aBoolean -> {
                            if (aBoolean != null && aBoolean) {
                                getActivity().setResult(RESULT_QUIT_GROUP_CONVERSATION_SUCCESS, new Intent().putExtra("conversationInfo", conversationInfo).putExtra("groupName", groupInfo.name));
                                getActivity().finish();
                            } else {
                                Toast.makeText(getActivity(), "退出群组失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    })
                    .build();
            dialog.show();
```

- [ ] **Step 9: 迁移 `NormalMessageContentViewHolder.java:121` — 重新发送**

把这段：

```java
        new MaterialDialog.Builder(context)
                .content("重新发送?")
                .negativeText("取消")
                .positiveText("重发")
                .onPositive((dialog, which) -> conversationViewModel.resendMessage(message.message))
                .build()
                .show();
```

替换为：

```java
        new FshareDialog.Builder(context)
                .content("重新发送?")
                .secondary("取消", null)
                .primary("重发", d -> conversationViewModel.resendMessage(message.message))
                .show();
```

- [ ] **Step 10: 迁移 `ThemeSettingActivity.java:62` — 切换主题**

把这段：

```java
        new MaterialDialog.Builder(this)
                .title("切换主题")
                .content("当前主题：" + currentMode + "\n新主题：" + newMode + "\n\n切换主题需要重启应用，是否继续？")
                .positiveText("确定")
                .negativeText("取消")
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(MaterialDialog dialog, DialogAction which) {
                        ThemeManager.getInstance(ThemeSettingActivity.this).setThemeMode(themeMode);
                        Toast.makeText(ThemeSettingActivity.this, "主题已切换，应用将重启", Toast.LENGTH_SHORT).show();
                        
                        // 重启应用
                        Intent intent = new Intent(ThemeSettingActivity.this, SplashActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    }
                })
                .show();
```

替换为：

```java
        new FshareDialog.Builder(this)
                .title("切换主题")
                .content("当前主题：" + currentMode + "\n新主题：" + newMode + "\n\n切换主题需要重启应用，是否继续？")
                .secondary("取消", null)
                .primary("确定", d -> {
                    ThemeManager.getInstance(ThemeSettingActivity.this).setThemeMode(themeMode);
                    Toast.makeText(ThemeSettingActivity.this, "主题已切换，应用将重启", Toast.LENGTH_SHORT).show();

                    // 重启应用
                    Intent intent = new Intent(ThemeSettingActivity.this, SplashActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
                .show();
```

- [ ] **Step 11: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

预期：BUILD SUCCESSFUL。常见错误：漏加 `import cn.wildfire.chat.kit.widget.dialog.FshareDialog;`，或 lambda 参数名 `d` 与外层变量重名（改个名即可）。

- [ ] **Step 12: 真机验证**

安装后逐项验证（浅色 + 深色各一遍）：

1. 我 → 点昵称 → 「修改个人昵称？」卡片，两个等宽按钮；点「修改」跳转改名页，点「取消」关闭
2. 群聊 → 群设置 → 退出群组 → 有标题的确认卡片；确认后退群成功
3. 群主账号 → 群设置 → 解散群组 → 确认卡片
4. 断网发一条消息使其失败 → 点感叹号 → 「重新发送?」卡片
5. 我 → 设置 → 主题 → 换一个主题 → 确认卡片，确认后应用重启
6. 会话列表长按一条会话 → 选「删除会话」→ 二次确认卡片（外层菜单此时仍是旧样式，属预期，Task 5 处理）
7. 检查每个卡片：圆角、`fog` 分割线、左按钮 `paper` 底 `ink` 字、右按钮 `cobalt` 底白字

- [ ] **Step 13: 暂存改动（不 commit）**

```bash
git add chat/src/main/res/layout/fshare_dialog.xml \
        chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareDialog.java \
        chat/src/main/java/cn/wildfire/chat/app/main/MainActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListAdapter.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListFragment.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationMessageAdapter.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/GroupConversationInfoFragment.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/message/viewholder/NormalMessageContentViewHolder.java \
        chat/src/main/java/cn/wildfire/chat/kit/setting/ThemeSettingActivity.java
```

建议 commit message：`feat: 新增统一样式弹窗 FshareDialog 并迁移 8 处确认框`

---

### Task 3: 迁移输入框与自定义 View 弹窗

`FshareDialog` 的 `input()` / `customView()` 在 Task 2 已实现，本任务只做这两处调用点迁移，验证这两条代码路径。

**Files:**
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/GroupConversationInfoFragment.java:239-266`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/forward/ForwardActivity.java:146-175`

**Interfaces:**
- Consumes: `FshareDialog.Builder.input(CharSequence hint, CharSequence prefill)`、`FshareDialog.getInputText()`、`FshareDialog.Builder.customView(View)`（均来自 Task 2）
- Produces: 无

- [ ] **Step 1: 迁移群昵称输入弹窗**

`GroupConversationInfoFragment.java:240` 现在把逻辑拆在两处：`input(...)` 的回调里做真正的改名，`onPositive` 里只做 dismiss。迁移后统一收拢到 `primary()`。把这段：

```java
        MaterialDialog dialog = new MaterialDialog.Builder(getActivity())
                .input("请输入你的群昵称", groupMember.alias, false, (dialog1, input) -> {
                    groupViewModel.modifyMyGroupAlias(groupInfo.target, input.toString().trim())
                            .observe(GroupConversationInfoFragment.this, new Observer<OperateResult>() {
                                @Override
                                public void onChanged(@Nullable OperateResult operateResult) {
                                    ConfigEventViewModel configEventViewModel = WfcUIKit.getAppScopeViewModel(ConfigEventViewModel.class);
                                    configEventViewModel.postGroupAliasEvent(groupInfo.target, true);
                                    UserInfo userInfo = userViewModel.getUserInfo(userViewModel.getUserId(), groupInfo.target, false);
                                    conversationMemberAdapter.updateMember(userInfo);
                                    if (operateResult.isSuccess()) {
                                        myGroupNickNameOptionItemView.setRightText(input.toString().trim());
                                    } else {
                                        Toast.makeText(getActivity(), "修改群昵称失败:" + operateResult.getErrorCode(), Toast.LENGTH_SHORT).show();

                                    }
                                }
                            });
                })
                .negativeText("取消")
                .positiveText("确定")
                .onPositive((dialog12, which) -> {
                    dialog12.dismiss();
                })
                .build();
        dialog.show();
```

替换为：

```java
        FshareDialog dialog = new FshareDialog.Builder(getActivity())
                .title("修改群昵称")
                .input("请输入你的群昵称", groupMember.alias)
                .secondary("取消", null)
                .primary("确定", d -> {
                    String alias = d.getInputText().trim();
                    groupViewModel.modifyMyGroupAlias(groupInfo.target, alias)
                            .observe(GroupConversationInfoFragment.this, new Observer<OperateResult>() {
                                @Override
                                public void onChanged(@Nullable OperateResult operateResult) {
                                    ConfigEventViewModel configEventViewModel = WfcUIKit.getAppScopeViewModel(ConfigEventViewModel.class);
                                    configEventViewModel.postGroupAliasEvent(groupInfo.target, true);
                                    UserInfo userInfo = userViewModel.getUserInfo(userViewModel.getUserId(), groupInfo.target, false);
                                    conversationMemberAdapter.updateMember(userInfo);
                                    if (operateResult.isSuccess()) {
                                        myGroupNickNameOptionItemView.setRightText(alias);
                                    } else {
                                        Toast.makeText(getActivity(), "修改群昵称失败:" + operateResult.getErrorCode(), Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                })
                .build();
        dialog.show();
```

原来的 MaterialDialog `input()` 不带标题，这里补一个「修改群昵称」标题，因为新弹窗的输入框没有 MaterialDialog 那种浮动 hint 标签，无标题时用户看不出在改什么。

- [ ] **Step 2: 迁移转发弹窗**

`ForwardActivity.java:149`。把这段：

```java
        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .customView(view, false)
                .negativeText("取消")
                .positiveText("发送")
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        Message extraMsg = null;
                        if (!TextUtils.isEmpty(view.getEditText())) {
                            TextMessageContent content = new TextMessageContent(view.getEditText());
                            extraMsg = new Message();
                            extraMsg.content = content;
                        }
                        forwardViewModel.forward(targetConversation, message, extraMsg)
                                .observe(ForwardActivity.this, new Observer<OperateResult<Integer>>() {
                                    @Override
                                    public void onChanged(@Nullable OperateResult<Integer> integerOperateResult) {
                                        if (integerOperateResult.isSuccess()) {
                                            Toast.makeText(ForwardActivity.this, "转发成功", Toast.LENGTH_SHORT).show();
                                            finish();
                                        } else {
                                            Toast.makeText(ForwardActivity.this, "转发失败" + integerOperateResult.getErrorCode(), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });

                    }
                })
                .build();
        dialog.show();
```

替换为：

```java
        FshareDialog dialog = new FshareDialog.Builder(this)
                .customView(view)
                .secondary("取消", null)
                .primary("发送", d -> {
                    Message extraMsg = null;
                    if (!TextUtils.isEmpty(view.getEditText())) {
                        TextMessageContent content = new TextMessageContent(view.getEditText());
                        extraMsg = new Message();
                        extraMsg.content = content;
                    }
                    forwardViewModel.forward(targetConversation, message, extraMsg)
                            .observe(ForwardActivity.this, new Observer<OperateResult<Integer>>() {
                                @Override
                                public void onChanged(@Nullable OperateResult<Integer> integerOperateResult) {
                                    if (integerOperateResult.isSuccess()) {
                                        Toast.makeText(ForwardActivity.this, "转发成功", Toast.LENGTH_SHORT).show();
                                        finish();
                                    } else {
                                        Toast.makeText(ForwardActivity.this, "转发失败" + integerOperateResult.getErrorCode(), Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                })
                .build();
        dialog.show();
```

`ForwardPromptView` 内部通过 `LayoutInflater.inflate(R.layout.forward_prompt_dialog, this, true)` 自带布局，作为 `customView` 直接塞进 `contentContainer` 即可，不需要额外改它。原 `.customView(view, false)` 的第二个参数是 MaterialDialog 的「是否给自定义 View 加内边距」，`FshareDialog` 不提供这个开关，内边距由 `forward_prompt_dialog.xml` 自己负责 —— Step 4 的验证项 3 检查实际留白是否合适，偏挤的话给 `forward_prompt_dialog.xml` 根布局补 padding。

- [ ] **Step 3: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

- [ ] **Step 4: 真机验证**

1. 群聊 → 群设置 → 我在本群的昵称 → 输入弹窗带「修改群昵称」标题，输入框预填当前昵称且光标在末尾；改一个名点「确定」，右侧文案随之更新
2. 同上，点「取消」→ 昵称不变
3. 长按一条消息 → 转发 → 选一个会话 → 转发预览卡片正常显示头像/名字/消息内容，输入附言后点「发送」，目标会话收到消息 + 附言
4. 深色模式下输入框文字为 `ink`、hint 为 `mist`，光标可见

- [ ] **Step 5: 暂存改动（不 commit）**

```bash
git add chat/src/main/java/cn/wildfire/chat/kit/conversation/GroupConversationInfoFragment.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/forward/ForwardActivity.java
```

建议 commit message：`feat: 群昵称输入与消息转发弹窗迁移到 FshareDialog`

---

### Task 4: `FshareLoadingDialog` + 迁移 12 处加载框

**Files:**
- Create: `chat/src/main/res/layout/fshare_dialog_loading.xml`
- Create: `chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareLoadingDialog.java`
- Modify: 下表 12 个文件

**Interfaces:**
- Consumes: `R.style.FshareDialog`（Task 1）、`R.drawable.bg_dialog_card`
- Produces: `cn.wildfire.chat.kit.widget.dialog.FshareLoadingDialog`，静态方法
  `public static FshareLoadingDialog show(Context context, CharSequence message, boolean cancelable)`；实例方法沿用 `Dialog` 的 `dismiss()` / `isShowing()`

- [ ] **Step 1: 创建布局 `fshare_dialog_loading.xml`**

创建 `chat/src/main/res/layout/fshare_dialog_loading.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_dialog_card"
    android:gravity="center_vertical"
    android:minWidth="120dp"
    android:orientation="horizontal"
    android:padding="20dp">

    <ProgressBar
        android:id="@+id/loadingProgress"
        android:layout_width="28dp"
        android:layout_height="28dp"
        android:indeterminate="true" />

    <TextView
        android:id="@+id/loadingMessage"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:textColor="@color/ink"
        android:textSize="15sp"
        android:visibility="gone" />
</LinearLayout>
```

`ProgressBar` 的颜色由 `R.style.FshareDialog` 里的 `colorAccent`（`@color/cobalt`）决定，不需要单独着色。

- [ ] **Step 2: 创建 `FshareLoadingDialog.java`**

创建 `chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareLoadingDialog.java`：

```java
package cn.wildfire.chat.kit.widget.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.wildfirechat.chat.R;

/**
 * 统一样式的加载弹窗：与 {@link FshareDialog} 同款 16dp 圆角卡片，
 * 内容为一行 spinner + 文案，没有标题也没有按钮。
 * 宽度按内容自适应，不像 FshareDialog 那样占屏宽 86%。
 */
public class FshareLoadingDialog extends Dialog {

    private final CharSequence message;

    /**
     * 创建并立即展示。
     *
     * @param message    加载文案，为空时只显示 spinner
     * @param cancelable 是否允许返回键 / 点击外部取消
     */
    public static FshareLoadingDialog show(@NonNull Context context,
                                           @Nullable CharSequence message,
                                           boolean cancelable) {
        FshareLoadingDialog dialog = new FshareLoadingDialog(context, message);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        dialog.show();
        return dialog;
    }

    private FshareLoadingDialog(@NonNull Context context, @Nullable CharSequence message) {
        super(context, R.style.FshareDialog);
        this.message = message;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_dialog_loading);

        TextView messageView = findViewById(R.id.loadingMessage);
        if (TextUtils.isEmpty(message)) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        }
    }
}
```

- [ ] **Step 3: 逐个迁移 12 处加载框**

统一的替换规则 —— 把

```java
MaterialDialog dialog = new MaterialDialog.Builder(<ctx>)
        .content(<MSG>)
        .progress(true, <n>)
        [.cancelable(false)]
        .build();
dialog.show();
```

换成一行：

```java
FshareLoadingDialog dialog = FshareLoadingDialog.show(<ctx>, <MSG>, <CANCELABLE>);
```

变量名保持原样（多数叫 `dialog`），后续的 `dialog.dismiss()` 调用不用改。逐处参数如下：

| # | 文件 | 行 | `<MSG>` | `<CANCELABLE>` |
|---|---|---|---|---|
| 1 | `app/main/PCLoginActivity.java` | 55 | `"处理中"` | `true` |
| 2 | `app/login/SMSLoginActivity.java` | 95 | `"登录中..."` | `false` |
| 3 | `app/login/LoginActivity.java` | 87 | `"登录中..."` | `false` |
| 4 | `kit/group/AddGroupMemberActivity.java` | 94 | `"添加中..."` | `false` |
| 5 | `kit/group/SetGroupNameActivity.java` | 84 | `"请稍后..."` | `false` |
| 6 | `kit/group/GroupInfoActivity.java` | 102 | `null` | `true` |
| 7 | `kit/group/RemoveGroupMemberActivity.java` | 63 | `"删除中..."` | `false` |
| 8 | `kit/user/ChangeMyNameActivity.java` | 92 | `"修改中..."` | `true` |
| 9 | `kit/channel/ChannelInfoActivity.java` | 105 | `isFollowed ? "正在取消收听" : "正在收听"` | `false` |
| 10 | `kit/channel/CreateChannelActivity.java` | 107 | `"创建频道中..."` | `false` |
| 11 | `kit/conversation/CreateConversationActivity.java` | 42 | `"创建中..."` | `true` |
| 12 | `kit/conversation/forward/PickConversationTargetToForwardActivity.java` | 28 | `"创建中..."` | `true` |

`<CANCELABLE>` 是 `true` 的那几处对应原代码没写 `.cancelable(...)`（MaterialDialog 默认可取消），保持原行为。

两处需要额外注意：

- **#6 `GroupInfoActivity.java:99-106`** 用成员字段 + 计数做引用管理，只换类型，`actionCount` 逻辑一行不动：

```java
    private void showLoading() {
        actionCount++;
        if (dialog == null) {
            dialog = FshareLoadingDialog.show(this, null, true);
        }
    }
```

同时把该文件的字段声明 `private MaterialDialog dialog;` 改成 `private FshareLoadingDialog dialog;`。`hideLoading()` 里的 `dialog.dismiss()` 与置空逻辑不动。

- **#10 `CreateChannelActivity.java:107`** 原代码是链式 `.show()` 结尾（没有 `.build()`），同样替换成一行 `FshareLoadingDialog dialog = FshareLoadingDialog.show(this, "创建频道中...", false);`。

每处都要加 `import cn.wildfire.chat.kit.widget.dialog.FshareLoadingDialog;`，并删掉该文件里已经用不到的 `com.afollestad.materialdialogs.MaterialDialog` import。

- [ ] **Step 4: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

- [ ] **Step 5: 真机验证**

1. 退出登录 → 登录页输入手机号验证码点登录 → 圆角卡片 + spinner + 「登录中...」，按返回键不能关（`cancelable=false`）
2. 建群：会话列表右上角 + → 发起群聊 → 选 2 人 → 「创建中...」加载框出现并在建群完成后消失
3. 群设置 → 添加成员 → 「添加中...」
4. 群设置 → 修改群名 → 「请稍后...」
5. 我 → 改昵称 → 「修改中...」
6. 进群设置页时的无文案 spinner 出现并正常消失，反复进出几次不会出现卡住不消失的 loading（引用计数回归点）
7. 深色模式下卡片是 `surface` 深底，文字为浅色 `ink`，spinner 为 `cobalt`

- [ ] **Step 6: 暂存改动（不 commit）**

```bash
git add chat/src/main/res/layout/fshare_dialog_loading.xml \
        chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareLoadingDialog.java \
        chat/src/main/java/cn/wildfire/chat/app/main/PCLoginActivity.java \
        chat/src/main/java/cn/wildfire/chat/app/login/SMSLoginActivity.java \
        chat/src/main/java/cn/wildfire/chat/app/login/LoginActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/group/AddGroupMemberActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/group/SetGroupNameActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/group/GroupInfoActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/group/RemoveGroupMemberActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/user/ChangeMyNameActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/channel/ChannelInfoActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/channel/CreateChannelActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/CreateConversationActivity.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/forward/PickConversationTargetToForwardActivity.java
```

建议 commit message：`feat: 新增 FshareLoadingDialog 并迁移 12 处加载框`

---

### Task 5: `FshareBottomSheetDialog` + 迁移 3 处菜单

**Files:**
- Create: `chat/src/main/res/layout/fshare_bottom_sheet.xml`
- Create: `chat/src/main/res/layout/fshare_bottom_sheet_item.xml`
- Create: `chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareBottomSheetDialog.java`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListAdapter.java:255`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationMessageAdapter.java:354`
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversation/ext/core/ConversationExtension.java:64`

**Interfaces:**
- Consumes: `R.style.FshareBottomSheetDialog`、`R.drawable.bg_dialog_sheet`、`R.drawable.bg_dialog_list_item`（均来自 Task 1）
- Produces:
  - `cn.wildfire.chat.kit.widget.dialog.FshareBottomSheetDialog`
  - `interface FshareBottomSheetDialog.Callback { void onSelect(int position, CharSequence text); }`
  - 链式方法 `items(List<String>)`、`callback(Callback)`，以及继承自 `BottomSheetDialog` 的 `show()`

- [ ] **Step 1: 创建菜单项布局 `fshare_bottom_sheet_item.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="52dp"
    android:background="@drawable/bg_dialog_list_item"
    android:gravity="center"
    android:textColor="@color/ink"
    android:textSize="16sp" />
```

- [ ] **Step 2: 创建菜单容器布局 `fshare_bottom_sheet.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingBottom="8dp">

    <!--菜单项容器，由代码填充-->
    <LinearLayout
        android:id="@+id/sheetItemContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_dialog_sheet"
        android:orientation="vertical" />

    <View
        android:layout_width="match_parent"
        android:layout_height="8dp" />

    <TextView
        android:id="@+id/sheetCancel"
        android:layout_width="match_parent"
        android:layout_height="52dp"
        android:background="@drawable/bg_dialog_list_item"
        android:gravity="center"
        android:text="取消"
        android:textColor="@color/ink"
        android:textSize="16sp" />
</LinearLayout>
```

「取消」是固定文案，直接写在布局里，不需要进 `strings.xml`（这个仓库的现有弹窗按钮文案也是硬编码的，保持一致）。

- [ ] **Step 3: 创建 `FshareBottomSheetDialog.java`**

创建 `chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareBottomSheetDialog.java`：

```java
package cn.wildfire.chat.kit.widget.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

import cn.wildfirechat.chat.R;

/**
 * 微信式底部弹出菜单。基于 material 的 {@link BottomSheetDialog}，
 * 复用它的升起动画与下拉关闭手势，只把内容换成本项目样式的卡片：
 * 上两角 12dp 圆角、52dp 菜单行、fog 分割线，隔 8dp 后一个整行「取消」。
 */
public class FshareBottomSheetDialog extends BottomSheetDialog {

    public interface Callback {
        void onSelect(int position, CharSequence text);
    }

    private final List<CharSequence> items = new ArrayList<>();
    private Callback callback;

    public FshareBottomSheetDialog(@NonNull Context context) {
        super(context, R.style.FshareBottomSheetDialog);
    }

    public FshareBottomSheetDialog items(@NonNull List<String> items) {
        this.items.clear();
        this.items.addAll(items);
        return this;
    }

    public FshareBottomSheetDialog callback(@Nullable Callback callback) {
        this.callback = callback;
        return this;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fshare_bottom_sheet);

        // BottomSheetDialog 的 sheet 容器自带白底，会在圆角外露出直角，这里清成透明，
        // 圆角交给 bg_dialog_sheet。style 里也设了一遍，双保险。
        View sheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundResource(android.R.color.transparent);
        }

        LinearLayout container = findViewById(R.id.sheetItemContainer);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                container.addView(createDivider());
            }
            final int position = i;
            final CharSequence text = items.get(i);
            TextView item = (TextView) inflater.inflate(
                    R.layout.fshare_bottom_sheet_item, container, false);
            item.setText(text);
            item.setOnClickListener(v -> {
                // 先关菜单再回调，避免回调里弹二次确认时两个弹窗叠在一起
                dismiss();
                if (callback != null) {
                    callback.onSelect(position, text);
                }
            });
            container.addView(item);
        }

        findViewById(R.id.sheetCancel).setOnClickListener(v -> dismiss());
    }

    private View createDivider() {
        View divider = new View(getContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(getContext().getResources().getColor(R.color.fog));
        return divider;
    }
}
```

关于导航栏安全区：`BottomSheetDialog` 的窗口默认不延伸到导航栏下方，布局里 8dp 的底部 padding 就够。Step 5 的验证项 4 专门检查这一点，若真机上「取消」被导航栏压住，再在 `onCreate` 里给根布局补 `navigation_bar_height` 的额外 padding。

- [ ] **Step 4: 迁移 3 处菜单调用点**

**4a. `ConversationListAdapter.java:255` — 长按会话。** 把：

```java
                new MaterialDialog.Builder(fragment.getContext()).items(titles).itemsCallback(new MaterialDialog.ListCallback() {
                    @Override
                    public void onSelection(MaterialDialog dialog, View v, int position, CharSequence text) {
```

换成：

```java
                new FshareBottomSheetDialog(fragment.getContext()).items(titles).callback(new FshareBottomSheetDialog.Callback() {
                    @Override
                    public void onSelect(int position, CharSequence text) {
```

方法体内部一行不动（Task 2 已经把里面嵌套的确认框换成 `FshareDialog` 了）。末尾的 `}).show();` 不变。

**4b. `ConversationMessageAdapter.java:354` — 长按消息。** 把：

```java
                new MaterialDialog.Builder(mContext).items(titles).itemsCallback(new MaterialDialog.ListCallback() {
                    @Override
                    public void onSelection(MaterialDialog dialog, View v, int position, CharSequence text) {
```

换成：

```java
                new FshareBottomSheetDialog(mContext).items(titles).callback(new FshareBottomSheetDialog.Callback() {
                    @Override
                    public void onSelect(int position, CharSequence text) {
```

方法体内部一行不动，末尾的 `}).show();` 不变。

**4c. `ConversationExtension.java:64` — 会话扩展菜单。** 这处是 lambda 形式，把：

```java
                new MaterialDialog.Builder(activity).items(titles).itemsCallback((dialog, v, position, text) -> {
```

换成：

```java
                new FshareBottomSheetDialog(activity).items(titles).callback((position, text) -> {
```

方法体与末尾 `}).show();` 不变。

三处都要加 `import cn.wildfire.chat.kit.widget.dialog.FshareBottomSheetDialog;`。4a / 4b 的 `onSelection` 原来带 `View v` 参数，新接口没有，确认方法体里没有用到 `v`（现有代码都没用）；若用到了，改用 `itemView` 等已有变量。

- [ ] **Step 5: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

若报 `cannot find symbol: com.google.android.material.R.id.design_bottom_sheet`，说明 material 1.0.0-beta01 的资源 id 名不同，用
`unzip -l ~/.gradle/caches/modules-2/files-2.1/com.google.android.material/material/1.0.0-beta01/*/material-1.0.0-beta01.aar` 或直接在 IDE 里跳转确认实际 id 名。

- [ ] **Step 6: 真机验证**

1. 会话列表长按一条会话 → 菜单从底部升起，圆角只在上方两角，「取消」与菜单卡片之间有 8dp 间隔
2. 点菜单外区域 / 下拉手势 → 菜单关闭
3. 选「删除会话」→ 菜单先关闭，随后弹出居中确认卡片（两个弹窗不叠）
4. 「取消」按钮完全可见，没有被系统导航栏压住
5. 会话页长按一条消息 → 底部菜单，撤回/删除等选项正常触发
6. 会话页输入框右侧「+」→ 扩展菜单从底部升起，选一项能正常打开对应功能
7. 深色模式下卡片是 `surface` 深底、文字浅色、分割线 `fog`

- [ ] **Step 7: 暂存改动（不 commit）**

```bash
git add chat/src/main/res/layout/fshare_bottom_sheet.xml \
        chat/src/main/res/layout/fshare_bottom_sheet_item.xml \
        chat/src/main/java/cn/wildfire/chat/kit/widget/dialog/FshareBottomSheetDialog.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversationlist/ConversationListAdapter.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/ConversationMessageAdapter.java \
        chat/src/main/java/cn/wildfire/chat/kit/conversation/ext/core/ConversationExtension.java
```

建议 commit message：`feat: 长按会话/消息与扩展菜单改为底部弹出样式`

---

### Task 6: imagepicker 模块的确认弹窗

`chat` 依赖 `imagepicker`（`chat/build.gradle:127`），依赖方向单向，`imagepicker` 引用不到 `FshareDialog`。这里复制一份最小实现（只支持 title / content / 两按钮），不为一个弹窗新建 `uikit` module。

**Files:**
- Create: `imagepicker/src/main/res/values-night/colors.xml`
- Modify: `imagepicker/src/main/res/values/colors.xml`
- Modify: `imagepicker/src/main/res/values/styles.xml`
- Create: `imagepicker/src/main/res/drawable/bg_dialog_card.xml`
- Create: `imagepicker/src/main/res/drawable/bg_dialog_button_primary.xml`
- Create: `imagepicker/src/main/res/drawable/bg_dialog_button_secondary.xml`
- Create: `imagepicker/src/main/res/layout/simple_confirm_dialog.xml`
- Create: `imagepicker/src/main/java/com/lqr/imagepicker/widget/SimpleConfirmDialog.java`
- Modify: `imagepicker/src/main/java/com/lqr/imagepicker/ui/ImagePreviewDelActivity.java:46-67`

**Interfaces:**
- Consumes: 无（本模块自包含）
- Produces: `com.lqr.imagepicker.widget.SimpleConfirmDialog`，静态方法
  `public static void show(Context context, CharSequence title, CharSequence content, CharSequence secondaryText, CharSequence primaryText, Runnable onPrimary)`

- [ ] **Step 1: 补 imagepicker 的颜色 token**

在 `imagepicker/src/main/res/values/colors.xml` 的 `</resources>` 之前追加（数值与 `chat/src/main/res/values/colors.xml` 逐一对齐）：

```xml

    <!--以下 token 与 chat 模块 values/colors.xml 保持一致。
        imagepicker 不依赖 chat，无法共享资源，改样式时两边都要改。-->
    <color name="ink">#171A21</color>
    <color name="surface">#FFFFFF</color>
    <color name="paper">#F5F7FC</color>
    <color name="fog">#E9EDF6</color>
    <color name="cobalt">#3E64E4</color>
    <color name="cobalt_pressed">#2D4FD8</color>
    <color name="text_color_white">#FFFFFF</color>
```

新建 `imagepicker/src/main/res/values-night/colors.xml`（数值对齐 `chat/src/main/res/values-night/colors.xml`）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--与 chat 模块 values-night/colors.xml 保持一致，见 values/colors.xml 的说明。-->
    <color name="ink">#ECEEF4</color>
    <color name="surface">#171B23</color>
    <color name="paper">#0F1218</color>
    <color name="fog">#232936</color>
    <color name="cobalt">#6E8BFF</color>
    <color name="cobalt_pressed">#5A78F0</color>
    <color name="text_color_white">#FFFFFF</color>
</resources>
```

- [ ] **Step 2: 复制三个 drawable**

`imagepicker/src/main/res/drawable/bg_dialog_card.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="16dp" />
</shape>
```

`imagepicker/src/main/res/drawable/bg_dialog_button_primary.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="@color/cobalt_pressed">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/cobalt" />
            <corners android:radius="10dp" />
        </shape>
    </item>
</ripple>
```

`imagepicker/src/main/res/drawable/bg_dialog_button_secondary.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="@color/fog">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/paper" />
            <corners android:radius="10dp" />
        </shape>
    </item>
</ripple>
```

- [ ] **Step 3: 加 style**

在 `imagepicker/src/main/res/values/styles.xml` 的 `</resources>` 之前追加：

```xml

    <!--与 chat 模块的 FshareDialog 同款。父主题必须用 DayNight 版本，
        否则 Dialog(Context, themeResId) 会把深色主题属性强制盖到 Activity 主题上。-->
    <style name="SimpleConfirmDialog" parent="Theme.AppCompat.DayNight.Dialog">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:backgroundDimEnabled">true</item>
        <item name="colorAccent">@color/cobalt</item>
    </style>
```

- [ ] **Step 4: 创建布局 `simple_confirm_dialog.xml`**

创建 `imagepicker/src/main/res/layout/simple_confirm_dialog.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_dialog_card"
    android:orientation="vertical">

    <TextView
        android:id="@+id/dialogTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fontFamily="sans-serif-medium"
        android:paddingStart="20dp"
        android:paddingTop="20dp"
        android:paddingEnd="20dp"
        android:paddingBottom="16dp"
        android:textColor="@color/ink"
        android:textSize="18sp"
        android:visibility="gone" />

    <View
        android:id="@+id/titleDivider"
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/fog"
        android:visibility="gone" />

    <TextView
        android:id="@+id/dialogContent"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:paddingStart="20dp"
        android:paddingTop="4dp"
        android:paddingEnd="20dp"
        android:paddingBottom="20dp"
        android:textColor="@color/ink"
        android:textSize="15sp" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/fog" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp">

        <TextView
            android:id="@+id/secondaryButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:background="@drawable/bg_dialog_button_secondary"
            android:gravity="center"
            android:textColor="@color/ink"
            android:textSize="15sp" />

        <TextView
            android:id="@+id/primaryButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:background="@drawable/bg_dialog_button_primary"
            android:gravity="center"
            android:textColor="@color/text_color_white"
            android:textSize="15sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 5: 创建 `SimpleConfirmDialog.java`**

创建 `imagepicker/src/main/java/com/lqr/imagepicker/widget/SimpleConfirmDialog.java`：

```java
package com.lqr.imagepicker.widget;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.lqr.imagepicker.R;

/**
 * 与 chat 模块 FshareDialog 同款样式的确认弹窗，功能裁剪到只剩「标题 + 正文 + 两按钮」。
 *
 * <p>imagepicker 不依赖 chat（依赖方向是 chat → imagepicker），无法直接复用 FshareDialog，
 * 因此这里复制了一份最小实现。改弹窗样式时 chat 与 imagepicker 两边都要改。
 */
public class SimpleConfirmDialog extends Dialog {

    private static final float WIDTH_RATIO = 0.86f;

    private final CharSequence title;
    private final CharSequence content;
    private final CharSequence secondaryText;
    private final CharSequence primaryText;
    private final Runnable onPrimary;

    public static void show(@NonNull Context context,
                            @Nullable CharSequence title,
                            CharSequence content,
                            CharSequence secondaryText,
                            CharSequence primaryText,
                            @Nullable Runnable onPrimary) {
        new SimpleConfirmDialog(context, title, content, secondaryText, primaryText, onPrimary).show();
    }

    private SimpleConfirmDialog(@NonNull Context context,
                                @Nullable CharSequence title,
                                CharSequence content,
                                CharSequence secondaryText,
                                CharSequence primaryText,
                                @Nullable Runnable onPrimary) {
        super(context, R.style.SimpleConfirmDialog);
        this.title = title;
        this.content = content;
        this.secondaryText = secondaryText;
        this.primaryText = primaryText;
        this.onPrimary = onPrimary;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.simple_confirm_dialog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (getContext().getResources().getDisplayMetrics().widthPixels * WIDTH_RATIO),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView titleView = findViewById(R.id.dialogTitle);
        View titleDivider = findViewById(R.id.titleDivider);
        TextView contentView = findViewById(R.id.dialogContent);
        if (TextUtils.isEmpty(title)) {
            titleView.setVisibility(View.GONE);
            titleDivider.setVisibility(View.GONE);
            // 没有标题行撑顶部留白时，正文自己补上（布局里默认只留了 4dp）
            int padding = contentView.getPaddingLeft();
            contentView.setPadding(padding, padding, padding, padding);
        } else {
            titleView.setText(title);
            titleView.setVisibility(View.VISIBLE);
            titleDivider.setVisibility(View.VISIBLE);
        }
        contentView.setText(content);

        TextView secondary = findViewById(R.id.secondaryButton);
        secondary.setText(secondaryText);
        secondary.setOnClickListener(v -> dismiss());

        TextView primary = findViewById(R.id.primaryButton);
        primary.setText(primaryText);
        primary.setOnClickListener(v -> {
            if (onPrimary != null) {
                onPrimary.run();
            }
            dismiss();
        });
    }
}
```

- [ ] **Step 6: 迁移 `ImagePreviewDelActivity.java:47`**

把整个 `showDeleteDialog()` 方法：

```java
    /** 是否删除此张图片 */
    private void showDeleteDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("提示");
        builder.setMessage("要删除这张照片吗？");
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                //移除当前图片刷新界面
                mImageItems.remove(mCurrentPosition);
                if (mImageItems.size() > 0) {
                    mAdapter.setData(mImageItems);
                    mAdapter.notifyDataSetChanged();
                    mTitleCount.setText(getString(R.string.preview_image_count, mCurrentPosition + 1, mImageItems.size()));
                } else {
                    onBackPressed();
                }
            }
        });
        builder.show();
```

替换为：

```java
    /** 是否删除此张图片 */
    private void showDeleteDialog() {
        SimpleConfirmDialog.show(this, "提示", "要删除这张照片吗？", "取消", "确定", () -> {
            //移除当前图片刷新界面
            mImageItems.remove(mCurrentPosition);
            if (mImageItems.size() > 0) {
                mAdapter.setData(mImageItems);
                mAdapter.notifyDataSetChanged();
                mTitleCount.setText(getString(R.string.preview_image_count, mCurrentPosition + 1, mImageItems.size()));
            } else {
                onBackPressed();
            }
        });
```

（原方法体末尾的 `builder.show();` 已被上面这行取代，方法的闭合 `}` 保留。）

import 改动：删 `androidx.appcompat.app.AlertDialog` 和 `android.content.DialogInterface`（确认该文件其它地方没用到 `DialogInterface`），加 `import com.lqr.imagepicker.widget.SimpleConfirmDialog;`。

- [ ] **Step 7: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

若 imagepicker 的 `build.gradle` 没开 Java 8（lambda 需要），`./gradlew :imagepicker:compileDebugJavaWithJavac` 会报 lambda 语法错误；此时在 `imagepicker/build.gradle` 的 `android {}` 块里补：

```groovy
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
```

- [ ] **Step 8: 真机验证**

1. 会话页 → 「+」→ 相册 → 进入图片预览 → 点删除按钮 → 弹窗为圆角卡片，带「提示」标题，两个等宽按钮
2. 点「确定」→ 图片被移除，计数刷新；只剩一张时删除会退出预览页
3. 点「取消」→ 图片保留
4. 深色模式下卡片与 chat 模块的确认弹窗视觉一致（并排对比第 1 步和「修改个人昵称？」那个弹窗）

- [ ] **Step 9: 暂存改动（不 commit）**

```bash
git add imagepicker/src/main/res/values/colors.xml \
        imagepicker/src/main/res/values-night/colors.xml \
        imagepicker/src/main/res/values/styles.xml \
        imagepicker/src/main/res/drawable/bg_dialog_card.xml \
        imagepicker/src/main/res/drawable/bg_dialog_button_primary.xml \
        imagepicker/src/main/res/drawable/bg_dialog_button_secondary.xml \
        imagepicker/src/main/res/layout/simple_confirm_dialog.xml \
        imagepicker/src/main/java/com/lqr/imagepicker/widget/SimpleConfirmDialog.java \
        imagepicker/src/main/java/com/lqr/imagepicker/ui/ImagePreviewDelActivity.java \
        imagepicker/build.gradle
```

（若 Step 7 没改 `imagepicker/build.gradle`，从上面命令里去掉那一行。）

建议 commit message：`feat: imagepicker 删除照片弹窗改用统一卡片样式`

---

### Task 7: 移除 afollestad 依赖与死代码

**Files:**
- Modify: `chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationViewHolder.java`（删注释掉的死代码与 import）
- Modify: `chat/build.gradle:114`
- Modify: 任何仍残留 `com.afollestad` import 的文件

**Interfaces:**
- Consumes: Task 2-6 的全部迁移成果
- Produces: 无

- [ ] **Step 1: 删掉 `ConversationViewHolder` 里注释掉的死代码**

`chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationViewHolder.java` 约 160-195 行是整段注释掉的群成员校验逻辑，里面含一个注释掉的 `MaterialDialog.Builder`。把这整段注释删掉，并删掉该文件顶部的 `import com.afollestad.materialdialogs.MaterialDialog;` 等 afollestad import（若有）。

- [ ] **Step 2: 找出全部残留 import 并清理**

```bash
grep -rn "com.afollestad" --include="*.java" chat/src client/src push/src p2penginekit/src emojilibrary/src imagepicker/src
```

预期：无输出。若有输出，逐个打开文件删掉 import（此时对应的使用点应该都已在前面任务中迁移完毕；若发现还有未迁移的使用点，说明前面漏了，按对应 Task 的模式补迁移）。

- [ ] **Step 3: 移除 gradle 依赖**

编辑 `chat/build.gradle`，删掉第 114 行：

```groovy
    implementation 'com.afollestad.material-dialogs:core:0.9.6.0'
```

- [ ] **Step 4: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew clean :chat:assembleDebug
```

用 `clean` 是为了确保没有旧的增量产物掩盖未解析的符号。预期：BUILD SUCCESSFUL。若报 `cannot find symbol: MaterialDialog`，说明 Step 2 漏了一处，回去清理。

- [ ] **Step 5: 确认无残留**

```bash
grep -rn "MaterialDialog\|afollestad" --include="*.java" --include="*.gradle" chat client push p2penginekit emojilibrary imagepicker | grep -v "/build/"
```

预期：无输出。

- [ ] **Step 6: 冒烟回归**

装包后快速走一遍：登录 → 会话列表 → 长按会话 → 进入会话 → 长按消息 → 「+」扩展菜单 → 群设置 → 退出群组。确认没有任何路径崩溃或弹窗不出现。

- [ ] **Step 7: 暂存改动（不 commit）**

```bash
git add chat/build.gradle \
        chat/src/main/java/cn/wildfire/chat/kit/conversationlist/viewholder/ConversationViewHolder.java
```

建议 commit message：`chore: 移除 afollestad material-dialogs 依赖与相关死代码`

---

### Task 8: 底部 tab 选中填充图标

**Files:**
- Create: `chat/src/main/res/drawable/ic_nav_chat_filled.xml`
- Create: `chat/src/main/res/drawable/ic_nav_contacts_filled.xml`
- Create: `chat/src/main/res/drawable/ic_nav_me_filled.xml`
- Create: `chat/src/main/res/drawable/ic_nav_chat_state.xml`
- Create: `chat/src/main/res/drawable/ic_nav_contacts_state.xml`
- Create: `chat/src/main/res/drawable/ic_nav_me_state.xml`
- Modify: `chat/src/main/res/menu/main_bottom_navigation.xml`
- Delete: `chat/src/main/res/drawable/bottom_nav_message_selector.xml`
- Delete: `chat/src/main/res/drawable/bottom_nav_contacts_selector.xml`
- Delete: `chat/src/main/res/drawable/bottom_nav_me_selector.xml`
- Delete: `chat/src/main/res/drawable/bottom_nav_discovery_selector.xml`

**Interfaces:**
- Consumes: 无
- Produces: 无（纯资源层改动）

本任务与 Task 1-7 完全独立，可以先做也可以后做。

- [ ] **Step 1: 新增填充版图标**

`chat/src/main/res/drawable/ic_nav_chat_filled.xml`（Material `chat_bubble` 实心版，与描线版 `ic_nav_chat` 同一形状）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" />
</vector>
```

`chat/src/main/res/drawable/ic_nav_contacts_filled.xml`（Material `people` 实心版）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z" />
</vector>
```

`chat/src/main/res/drawable/ic_nav_me_filled.xml`（Material `person` 实心版）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z" />
</vector>
```

`fillColor` 写死黑色只是占位，实际颜色由 `app:itemIconTint="@color/bottom_nav_color"` 决定，与现有描线图标的做法一致。

- [ ] **Step 2: 新增三个 state selector**

`chat/src/main/res/drawable/ic_nav_chat_state.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_nav_chat_filled" android:state_checked="true" />
    <item android:drawable="@drawable/ic_nav_chat" />
</selector>
```

`chat/src/main/res/drawable/ic_nav_contacts_state.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_nav_contacts_filled" android:state_checked="true" />
    <item android:drawable="@drawable/ic_nav_contacts" />
</selector>
```

`chat/src/main/res/drawable/ic_nav_me_state.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_nav_me_filled" android:state_checked="true" />
    <item android:drawable="@drawable/ic_nav_me" />
</selector>
```

最后一项不写任何 state，作为兜底 —— selector 是按顺序匹配的，兜底项必须放在最后，否则 checked 状态永远匹配不到填充版。

- [ ] **Step 3: menu 改指向 selector**

把 `chat/src/main/res/menu/main_bottom_navigation.xml` 三个 item 的 `android:icon` 分别改成对应的 `_state`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <item
        android:id="@+id/conversation_list"
        android:icon="@drawable/ic_nav_chat_state"
        android:title="会话"
        app:showAsAction="never" />
    <item
        android:id="@+id/contact"
        android:icon="@drawable/ic_nav_contacts_state"
        android:title="联系人" />
    <item
        android:id="@+id/me"
        android:icon="@drawable/ic_nav_me_state"
        android:title="我" />

</menu>
```

`main_activity.xml:40` 的 `app:itemIconTint="@color/bottom_nav_color"` 保持不变 —— 选中 `cobalt`、未选中 `mist` 的配色继续生效。

- [ ] **Step 4: 删除 4 个废弃 selector**

这 4 个文件引用的是旧设计的 mipmap 资源，全项目零引用：

```bash
git rm chat/src/main/res/drawable/bottom_nav_message_selector.xml \
       chat/src/main/res/drawable/bottom_nav_contacts_selector.xml \
       chat/src/main/res/drawable/bottom_nav_me_selector.xml \
       chat/src/main/res/drawable/bottom_nav_discovery_selector.xml
```

删除前先确认确实无人引用：

```bash
grep -rn "bottom_nav_message_selector\|bottom_nav_contacts_selector\|bottom_nav_me_selector\|bottom_nav_discovery_selector" chat/src client/src imagepicker/src
```

预期：无输出。若有输出就不要删，先把引用改掉。

- [ ] **Step 5: 编译**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew :chat:assembleDebug
```

- [ ] **Step 6: 真机验证**

1. 进入首页 → 「会话」tab 选中，图标为**实心**气泡且为 `cobalt` 蓝
2. 切到「联系人」→ 联系人图标变实心蓝，会话图标变回**描线** `mist` 灰
3. 切到「我」→ 同上
4. 来回切三个 tab，图标填充/描线切换即时、无残留
5. 有未读消息时，会话 tab 右上角红色角标位置与改造前一致（`MainActivity.java:117` / `:155` / `:170` 的角标定位回归点）
6. 深色模式下，选中图标为深色主题的 `cobalt`（#6E8BFF），未选中为 `mist`

- [ ] **Step 7: 暂存改动（不 commit）**

```bash
git add chat/src/main/res/drawable/ic_nav_chat_filled.xml \
        chat/src/main/res/drawable/ic_nav_contacts_filled.xml \
        chat/src/main/res/drawable/ic_nav_me_filled.xml \
        chat/src/main/res/drawable/ic_nav_chat_state.xml \
        chat/src/main/res/drawable/ic_nav_contacts_state.xml \
        chat/src/main/res/drawable/ic_nav_me_state.xml \
        chat/src/main/res/menu/main_bottom_navigation.xml
```

（Step 4 的 `git rm` 已经自动 stage 了删除。）

建议 commit message：`feat: 底部 tab 选中态改用填充版图标`

---

## 完成检查

全部 8 个 Task 做完后，逐条对照确认：

- [ ] 26 处弹窗全部迁移（chat 25 + imagepicker 1）
- [ ] `grep -rn "MaterialDialog\|afollestad" --include="*.java" --include="*.gradle" chat client push p2penginekit emojilibrary imagepicker | grep -v "/build/"` 无输出
- [ ] `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.17.jdk/Contents/Home ./gradlew clean :chat:assembleDebug` 通过
- [ ] 设计文档 `docs/superpowers/specs/2026-07-31-unify-dialog-style-design.md` 的「验证」表里 13 项手动验证全部在浅色 + 深色下走过一遍
