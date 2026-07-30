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
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** 状态 1 上一次展示/探测的 host，用于判断连接切换后是否需要重新探测延迟。 */
    private String lastRenderedHost;

    /** 「自动轮询」行的标识，不是真实 host。 */
    private static final String HOST_AUTO = "__auto__";

    private final Map<String, View> nodeRows = new LinkedHashMap<>();
    private String selectedHost;

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

    private void showSelectState() {
        cancelProbe();
        dialogTitle.setText(R.string.server_select_title);
        nodeListTip.setVisibility(View.VISIBLE);
        infoContainer.setVisibility(View.GONE);
        nodeContainer.setVisibility(View.VISIBLE);
        secondaryButton.setText(R.string.server_select_back);
        primaryButton.setText(R.string.server_select_confirm);
        secondaryButton.setOnClickListener(v -> showInfoState());
        primaryButton.setOnClickListener(v -> confirmSelection());

        selectedHost = ServerNodeManager.pinnedHost(getContext());
        buildNodeRows();
        probeAllNodes();
    }

    /** 构建「自动」+ 各节点的单选行。每次进入状态 2 重建，避免复用脏状态。 */
    private void buildNodeRows() {
        nodeContainer.removeAllViews();
        nodeRows.clear();
        LayoutInflater inflater = LayoutInflater.from(getContext());

        // HOST_AUTO 不是真实 host，仅作「自动轮询」这一行的标识
        addNodeRow(inflater, HOST_AUTO,
                getContext().getString(R.string.server_node_auto),
                getContext().getString(R.string.server_node_auto_host));
        for (ServerNode node : Config.IM_SERVER_NODES) {
            addNodeRow(inflater, node.host, node.name, node.host);
        }
        refreshRadios();
    }

    private void addNodeRow(LayoutInflater inflater, String host, String name, String subtitle) {
        View row = inflater.inflate(R.layout.server_node_item, nodeContainer, false);
        ((TextView) row.findViewById(R.id.nodeName)).setText(name);
        ((TextView) row.findViewById(R.id.nodeHost)).setText(subtitle);
        row.setOnClickListener(v -> {
            selectedHost = HOST_AUTO.equals(host) ? null : host;
            refreshRadios();
        });
        nodeContainer.addView(row);
        nodeRows.put(host, row);
    }

    private void refreshRadios() {
        for (Map.Entry<String, View> entry : nodeRows.entrySet()) {
            boolean checked = HOST_AUTO.equals(entry.getKey())
                    ? selectedHost == null
                    : entry.getKey().equals(selectedHost);
            ((RadioButton) entry.getValue().findViewById(R.id.nodeRadio)).setChecked(checked);
        }
    }

    private void probeAllNodes() {
        List<String> hosts = new ArrayList<>();
        for (ServerNode node : Config.IM_SERVER_NODES) {
            hosts.add(node.host);
            View row = nodeRows.get(node.host);
            if (row != null) {
                row.findViewById(R.id.nodeProgress).setVisibility(View.VISIBLE);
                ((TextView) row.findViewById(R.id.nodeLatency)).setText("");
            }
        }
        runningProbe = HostLatencyProbe.probeAll(hosts, Config.IM_SERVER_PORT,
                (host, latencyMs) -> {
                    View row = nodeRows.get(host);
                    if (row == null) {
                        return;
                    }
                    row.findViewById(R.id.nodeProgress).setVisibility(View.GONE);
                    ((TextView) row.findViewById(R.id.nodeLatency)).setText(latencyText(latencyMs));
                });
    }

    /**
     * 选中项与当前固定状态一致时不重连，避免无谓地断开一条好连接。
     * 固定单个节点传该 host，自动则传全部 host 恢复轮询。
     */
    private void confirmSelection() {
        String currentPinned = ServerNodeManager.pinnedHost(getContext());
        boolean unchanged = (selectedHost == null && currentPinned == null)
                || (selectedHost != null && selectedHost.equals(currentPinned));
        if (unchanged) {
            showInfoState();
            return;
        }

        String hostSpec;
        if (selectedHost == null) {
            ServerNodeManager.unpin(getContext());
            hostSpec = ServerHostSpec.joinAll(Config.IM_SERVER_NODES);
        } else {
            ServerNodeManager.pin(getContext(), selectedHost);
            hostSpec = selectedHost;
        }
        if (ChatManagerHolder.gChatManager != null) {
            ChatManagerHolder.gChatManager.switchServerHost(hostSpec);
        }
        showInfoState();
    }

    private void renderHost() {
        String host = displayHost();
        hostValue.setText(host);
        lastRenderedHost = host;
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
            String previousHost = lastRenderedHost;
            renderHost();
            // switchServerHost() 是 oneway AIDL，切换完成前 getConnectedHost() 仍返回旧 host，
            // 探测会测到旧节点的延迟。这里在展示 host 真的发生变化（例如新连接建立后）时才重新探测，
            // 避免连接状态频繁变化（connecting/connected 反复触发本方法）导致的无谓重复探测。
            if (!Objects.equals(previousHost, lastRenderedHost)) {
                probeCurrentHost();
            }
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
