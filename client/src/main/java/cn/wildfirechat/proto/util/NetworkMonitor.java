package cn.wildfirechat.proto.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.telephony.TelephonyManager;

import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class NetworkMonitor {
    private static final Log log = LoggerFactory.getLogger(NetworkMonitor.class);
    private static final String TAG = "NetworkMonitor";
    private static final long DEBOUNCE_DELAY = 1000; // 1秒防抖

    private Context context;
    private ConnectivityManager connectivityManager;
    private NetworkCallback networkCallback;
    private NetworkBroadcastReceiver networkReceiver;
    private Map<String, NetworkStatusInfo> networkStatusMap; // key: networkIdentifier
    private NetworkStateListener listener;
    private boolean isMonitoring = false;
    private Handler handler;

    // 网络状态信息类
    private static class NetworkStatusInfo {
        String networkType;
        String networkIdentifier;
        int currentNetworkId; // 当前连接的networkId（会变化）
        boolean isConnected;
        long lastChangeTime;
        int changeCount; // 变更次数计数

        NetworkStatusInfo(String networkType, String networkIdentifier) {
            this.networkType = networkType;
            this.networkIdentifier = networkIdentifier;
            this.currentNetworkId = 0;
            this.isConnected = false;
            this.lastChangeTime = 0;
            this.changeCount = 0;
        }

        @Override
        public String toString() {
            return "NetworkStatusInfo{" +
                    "networkType='" + networkType + '\'' +
                    ", networkIdentifier='" + networkIdentifier + '\'' +
                    ", currentNetworkId=" + currentNetworkId +
                    ", isConnected=" + isConnected +
                    ", lastChangeTime=" + lastChangeTime +
                    ", changeCount=" + changeCount +
                    '}';
        }
    }

    // 网络状态监听接口
    public interface NetworkStateListener {
        void onNetworkConnected(String networkType, String networkIdentifier, int networkId);
        void onNetworkDisconnected(String networkType, String networkIdentifier, int networkId);
    }

    public NetworkMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(this.context.getMainLooper());
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.networkStatusMap = new HashMap<>();

        // 根据Android版本选择监听方式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            this.networkCallback = new NetworkCallback();
        } else {
            this.networkReceiver = new NetworkBroadcastReceiver();
        }
    }

    // 设置监听器
    public void setNetworkStateListener(NetworkStateListener listener) {
        this.listener = listener;
    }

    // 开始监听
    public void startMonitoring() {
        if (isMonitoring) {
            log.i(TAG, "Already monitoring");
            return;
        }

        if (connectivityManager == null) {
            log.e("ConnectivityManager is null");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 使用 NetworkCallback
                NetworkRequest.Builder builder = new NetworkRequest.Builder();
                builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                NetworkRequest request = builder.build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            } else {
                // Android 7.0 以下使用 BroadcastReceiver
                IntentFilter filter = new IntentFilter();
                filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
                context.registerReceiver(networkReceiver, filter);
            }

            isMonitoring = true;
            log.i(TAG, "Network monitoring started (API " + Build.VERSION.SDK_INT + ")");

        } catch (Exception e) {
            log.e( "Failed to start monitoring", e);
        }
    }

    // 停止监听
    public void stopMonitoring() {
        if (!isMonitoring) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } else if (networkReceiver != null) {
                context.unregisterReceiver(networkReceiver);
            }

            // 清除所有记录
            networkStatusMap.clear();
            isMonitoring = false;

            log.i(TAG, "Network monitoring stopped");

        } catch (Exception e) {
            log.e("Failed to stop monitoring", e);
        }
    }

    // 获取网络类型 (Android 6.0+)
    private String getNetworkType(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "WiFi";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Mobile";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        return "Unknown";
    }

    // 获取网络类型 (兼容低版本)
    private String getNetworkTypeLegacy(int type) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI:
                return "WiFi";
            case ConnectivityManager.TYPE_MOBILE:
                return "Mobile";
            case ConnectivityManager.TYPE_ETHERNET:
                return "Ethernet";
            case ConnectivityManager.TYPE_VPN:
                return "VPN";
            default:
                return "Unknown";
        }
    }

    // 检查网络是否可用 (Android 6.0+)
    private boolean isNetworkAvailable(NetworkCapabilities capabilities) {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // 获取网络唯一标识符
    private String getNetworkIdentifier(NetworkCapabilities capabilities, String networkType) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return getWifiIdentifier();
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return getMobileIdentifier();
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        return networkType + "_Unknown";
    }

    // 获取WiFi网络标识符
    private String getWifiIdentifier() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    String ssid = wifiInfo.getSSID();
                    if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("")) {
                        // 移除引号
                        ssid = ssid.replace("\"", "");
                        return "WiFi_" + ssid;
                    }
                }
            }
        } catch (Exception e) {
            log.e("Failed to get WiFi SSID: " + e.getMessage());
        }
        return "WiFi_Unknown";
    }

    // 获取移动网络标识符
    private String getMobileIdentifier() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                String operator = telephonyManager.getNetworkOperatorName();
                if (operator != null && !operator.isEmpty()) {
                    return "Mobile_" + operator;
                }
            }
        } catch (Exception e) {
            log.e("Failed to get mobile operator: " + e.getMessage());
        }
        return "Mobile_Unknown";
    }

    // 获取网络标识符（低版本兼容）
    private String getNetworkIdentifierLegacy(String networkType) {
        if ("WiFi".equals(networkType)) {
            return getWifiIdentifier();
        } else if ("Mobile".equals(networkType)) {
            return getMobileIdentifier();
        }
        return networkType + "_Legacy";
    }

    // 处理网络状态变化（使用网络标识符）
    private void handleNetworkChange(String networkType, String networkIdentifier, int networkId, boolean isConnected, boolean connectedFallback) {
        NetworkStatusInfo statusInfo = networkStatusMap.get(networkIdentifier);

        if (statusInfo == null) {
            statusInfo = new NetworkStatusInfo(networkType, networkIdentifier);
            networkStatusMap.put(networkIdentifier, statusInfo);
        }

        // 更新当前networkId（重连后会变化）
        statusInfo.currentNetworkId = networkId;

        long currentTime = System.currentTimeMillis();

        // 检查状态是否真的发生了变化
        if (statusInfo.isConnected == isConnected && !connectedFallback) {
            log.i(TAG, "No state change for " + networkIdentifier +
                    " (current ID: " + networkId + "), current state: " +
                    (isConnected ? "connected" : "disconnected"));
            return;
        }

        // 防抖检查：如果距离上次变更时间太短，忽略
        if (currentTime - statusInfo.lastChangeTime < DEBOUNCE_DELAY) {
            log.i(TAG, "Debounce: Ignoring rapid network change for " + networkIdentifier +
                    " (current ID: " + networkId + "), time diff: " +
                    (currentTime - statusInfo.lastChangeTime) + "ms");
            return;
        }

        // 更新状态信息
        statusInfo.isConnected = isConnected;
        statusInfo.lastChangeTime = currentTime;
        statusInfo.changeCount++;


        log.i(TAG, "Network state changed: " + networkIdentifier + " (current ID: " + networkId +
                ") -> " + (isConnected ? "connected" : "disconnected") +
                ", change count: " + statusInfo.changeCount);

        // 通知监听器
        if (listener != null) {
            if (isConnected) {
                listener.onNetworkConnected(networkType, networkIdentifier, networkId);
            } else {
                listener.onNetworkDisconnected(networkType, networkIdentifier, networkId);
            }
        }
    }

    // Android 7.0+ 网络回调
    private class NetworkCallback extends ConnectivityManager.NetworkCallback {

        @Override
        public void onAvailable(Network network) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null) {
                    String networkType = getNetworkType(capabilities);
                    String networkIdentifier = getNetworkIdentifier(capabilities, networkType);
                    int networkId = network.hashCode();
                    boolean isAvailable = isNetworkAvailable(capabilities);
                    log.i(TAG, "Network available: " + networkIdentifier + " (ID: " + networkId + ") Available: " + isAvailable);
                }
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            String networkType = getNetworkType(networkCapabilities);
            String networkIdentifier = getNetworkIdentifier(networkCapabilities, networkType);
            int networkId = network.hashCode();
            boolean isAvailable = isNetworkAvailable(networkCapabilities);

            log.i(TAG, "Network capabilities changed: " + networkIdentifier + " (ID: " + networkId + "), Available: " + isAvailable);

            handleNetworkChange(networkType, networkIdentifier, networkId, isAvailable, false);
        }

        @Override
        public void onLost(Network network) {
            int networkId = network.hashCode();
            boolean fallbackWIFI = false;
            // 查找对应的网络信息
            for (NetworkStatusInfo info : networkStatusMap.values()) {
                log.i(TAG, "All Network: " + info.networkIdentifier + " (ID: " + info.currentNetworkId + ")");
                if (info.currentNetworkId == networkId) {
                    log.i(TAG, "Network lost: " + info.networkIdentifier + " (ID: " + info.currentNetworkId + ")");
                    handleNetworkChange(info.networkType, info.networkIdentifier, networkId, false, false);
                    if(info.networkIdentifier.contains("WiFi")){
                        fallbackWIFI = true;
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                log.i(TAG, "Network lost: " + networkId);

                // 延迟检查，避免拿到 null
                boolean finalFallbackWIFI = fallbackWIFI;
                handler.postDelayed(() -> {
                    Network activeNetwork = connectivityManager.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
                        if (capabilities != null) {
                            String networkType = getNetworkType(capabilities);
                            String networkIdentifier = getNetworkIdentifier(capabilities, networkType);
                            int newNetworkId = activeNetwork.hashCode();
                            boolean isAvailable = isNetworkAvailable(capabilities);
                            log.i(TAG, "Fallback to active network: " + networkIdentifier + " (ID: " + newNetworkId + ") fallbackWiIFI: "+ finalFallbackWIFI);
                            if(finalFallbackWIFI && isAvailable){
                                handleNetworkChange(networkType, networkIdentifier, newNetworkId, true, true);
                            }
                        }
                    } else {
                        log.i(TAG, "No active network after loss");
                        // 可以选择这里发一个断开通知，也可以忽略（只关心 connected）
                    }
                }, 200); // 延迟 200ms，保证系统完成切换
            }

        }

        @Override
        public void onUnavailable() {
            log.i(TAG, "No network available");
        }
    }

    // 低版本广播接收器
    private class NetworkBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

                if (networkInfo != null) {
                    String networkType = getNetworkTypeLegacy(networkInfo.getType());
                    String networkIdentifier = getNetworkIdentifierLegacy(networkType);
                    int networkId = networkInfo.getType();
                    boolean isConnected = networkInfo.isConnected();

                    log.i(TAG, "Broadcast received: " + networkIdentifier + " (ID: " + networkId +
                            "), connected: " + isConnected);

                    handleNetworkChange(networkType, networkIdentifier, networkId, isConnected, false);
                } else {
                    // 所有网络都断开
                    log.i(TAG, "All networks disconnected");
                    // 将所有已记录的网络标记为断开
                    for (NetworkStatusInfo info : networkStatusMap.values()) {
                        if (info.isConnected) {
                            handleNetworkChange(info.networkType, info.networkIdentifier,
                                    info.currentNetworkId, false, false);
                        }
                    }
                }
            }
        }
    }
}
