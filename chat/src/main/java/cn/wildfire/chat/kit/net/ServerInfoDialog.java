package cn.wildfire.chat.kit.net;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.wildfire.chat.app.Config;
import cn.wildfire.chat.kit.ChatManagerHolder;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.client.ConnectionStatus;

/**
 * 「当前连接服务」弹窗。两个状态共用一个 Dialog 实例：
 * 状态 1 展示当前连接信息，状态 2 选择节点。切换只改标题、容器可见性与按钮文案。
 */
public class ServerInfoDialog extends Dialog {

    private final LifecycleOwner lifecycleOwner;
    private final LiveData<Integer> connectionStatus;

    private TextView dialogTitle;
    private TextView nodeListTip;
    private View statusDot;
    private LinearLayout infoContainer;
    private LinearLayout nodeContainer;
    private TextView secondaryButton;
    private TextView primaryButton;

    private TextView hostValue;
    private TextView statusValue;
    private TextView latencyValue;

    private HostLatencyProbe.Cancelable runningProbe;
    private Observer<Integer> statusObserver;

    public ServerInfoDialog(@NonNull Activity activity,
                            @NonNull LifecycleOwner lifecycleOwner,
                            @NonNull LiveData<Integer> connectionStatus) {
        super(activity, R.style.ServerInfoDialog);
        this.lifecycleOwner = lifecycleOwner;
        this.connectionStatus = connectionStatus;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.server_info_dialog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout((int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dialogTitle = findViewById(R.id.dialogTitle);
        nodeListTip = findViewById(R.id.nodeListTip);
        statusDot = findViewById(R.id.statusDot);
        infoContainer = findViewById(R.id.infoContainer);
        nodeContainer = findViewById(R.id.nodeContainer);
        secondaryButton = findViewById(R.id.secondaryButton);
        primaryButton = findViewById(R.id.primaryButton);

        buildInfoRows();
        showInfoState();

        statusObserver = new Observer<Integer>() {
            @Override
            public void onChanged(Integer status) {
                renderConnectionStatus(status);
            }
        };
        connectionStatus.observe(lifecycleOwner, statusObserver);
    }

    /** 状态 1 的 5 行信息。行顺序即截图顺序。 */
    private void buildInfoRows() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        addRow(inflater, R.string.server_info_name_label,
                getContext().getString(R.string.server_info_name_value), false);
        hostValue = addRow(inflater, R.string.server_info_host_label, "", true);
        addRow(inflater, R.string.server_info_port_label,
                String.valueOf(Config.IM_SERVER_PORT), true);
        statusValue = addRow(inflater, R.string.server_info_status_label, "", true);
        latencyValue = addRow(inflater, R.string.server_info_latency_label,
                getContext().getString(R.string.server_latency_unknown), true);
    }

    private TextView addRow(LayoutInflater inflater, int labelRes, String value, boolean withDivider) {
        if (withDivider) {
            View divider = new View(getContext());
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(getContext().getResources().getColor(R.color.fog));
            infoContainer.addView(divider);
        }
        View row = inflater.inflate(R.layout.server_info_row, infoContainer, false);
        ((TextView) row.findViewById(R.id.rowLabel)).setText(labelRes);
        TextView valueView = row.findViewById(R.id.rowValue);
        valueView.setText(value);
        infoContainer.addView(row);
        return valueView;
    }

    private void showInfoState() {
        dialogTitle.setText(R.string.server_info_title);
        nodeListTip.setVisibility(View.GONE);
        infoContainer.setVisibility(View.VISIBLE);
        nodeContainer.setVisibility(View.GONE);
        secondaryButton.setText(R.string.server_info_switch);
        primaryButton.setText(R.string.server_info_close);
        primaryButton.setOnClickListener(v -> dismiss());
        secondaryButton.setOnClickListener(v -> showSelectState());

        renderHost();
        renderConnectionStatus(connectionStatus.getValue());
        probeCurrentHost();
    }

    /** Task 9 实现，此处先留空跳转以便状态 1 可独立验证。 */
    private void showSelectState() {
    }

    private void renderHost() {
        hostValue.setText(displayHost());
    }

    /**
     * 优先展示真正连通的 host；取不到时退回用户固定的 host，
     * 都没有则说明处于自动轮询且尚未连上。
     */
    private String displayHost() {
        String connected = ChatManagerHolder.gChatManager == null
                ? null : ChatManagerHolder.gChatManager.getConnectedHost();
        if (connected != null && !connected.trim().isEmpty()) {
            return connected;
        }
        String pinned = ServerNodeManager.pinnedHost(getContext());
        if (pinned != null) {
            return pinned;
        }
        return getContext().getString(R.string.server_node_auto);
    }

    private void renderConnectionStatus(Integer status) {
        int textRes;
        int colorRes;
        if (status != null && status == ConnectionStatus.ConnectionStatusConnected) {
            textRes = R.string.server_status_connected;
            colorRes = R.color.leaf;
        } else if (status != null && status == ConnectionStatus.ConnectionStatusConnecting) {
            textRes = R.string.server_status_connecting;
            colorRes = R.color.cobalt;
        } else {
            textRes = R.string.server_status_unconnected;
            colorRes = R.color.coral;
        }
        int color = getContext().getResources().getColor(colorRes);
        statusValue.setText(textRes);
        statusValue.setTextColor(color);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));

        if (infoContainer.getVisibility() == View.VISIBLE) {
            renderHost();
        }
    }

    private void probeCurrentHost() {
        cancelProbe();
        String host = probeTargetHost();
        if (host == null) {
            latencyValue.setText(R.string.server_latency_unknown);
            return;
        }
        latencyValue.setText(R.string.server_latency_unknown);
        runningProbe = HostLatencyProbe.probeAll(
                Collections.singletonList(host),
                Config.IM_SERVER_PORT,
                (probedHost, latencyMs) -> latencyValue.setText(latencyText(latencyMs)));
    }

    /** 探测目标：实连 host 优先，其次固定 host，最后节点表首项。 */
    private String probeTargetHost() {
        String connected = ChatManagerHolder.gChatManager == null
                ? null : ChatManagerHolder.gChatManager.getConnectedHost();
        if (connected != null && !connected.trim().isEmpty()) {
            return connected;
        }
        String pinned = ServerNodeManager.pinnedHost(getContext());
        if (pinned != null) {
            return pinned;
        }
        return Config.IM_SERVER_NODES.isEmpty() ? null : Config.IM_SERVER_NODES.get(0).host;
    }

    private String latencyText(long latencyMs) {
        if (latencyMs >= 0) {
            return getContext().getString(R.string.server_latency_ms, latencyMs);
        }
        if (latencyMs == HostLatencyProbe.RESULT_TIMEOUT) {
            return getContext().getString(R.string.server_latency_timeout);
        }
        if (latencyMs == HostLatencyProbe.RESULT_UNRESOLVED) {
            return getContext().getString(R.string.server_latency_unresolved);
        }
        return getContext().getString(R.string.server_latency_unreachable);
    }

    private void cancelProbe() {
        if (runningProbe != null) {
            runningProbe.cancel();
            runningProbe = null;
        }
    }

    @Override
    public void dismiss() {
        cancelProbe();
        if (statusObserver != null) {
            connectionStatus.removeObserver(statusObserver);
            statusObserver = null;
        }
        super.dismiss();
    }
}
