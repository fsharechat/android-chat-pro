package com.comsince.github.p2penginekit;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.comsince.github.util.AVUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.webrtc.ThreadUtils;

public class AVBluetoothManager {
    private static final String TAG = "AVBluetoothManager";
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;
    private static final int MAX_SCO_CONNECTION_ATTEMPTS = 2;
    private final Context avContext;
    private final AVAudioManager avAudioManager;
    private final AudioManager audioManager;
    private final Handler handler;
    int scoConnectionAttempts;
    private AVBluetoothManager.State bluetoothState;
    private final ServiceListener bluetoothServiceListener;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;
    private BluetoothDevice bluetoothDevice;
    private final BroadcastReceiver bluetoothHeadsetReceiver;
    private final Runnable bluetoothTimeoutRunnable = new Runnable() {
        public void run() {
            AVBluetoothManager.this.bluetoothTimeout();
        }
    };

    static AVBluetoothManager create(Context context, AVAudioManager audioManager) {
        Log.d("AVBluetoothManager", "create" + AVUtils.getThreadInfo());
        return new AVBluetoothManager(context, audioManager);
    }

    protected AVBluetoothManager(Context context, AVAudioManager audioManager) {
        Log.d("AVBluetoothManager", "ctor");
        ThreadUtils.checkIsOnMainThread();
        this.avContext = context;
        this.avAudioManager = audioManager;
        this.audioManager = this.getAudioManager(context);
        this.bluetoothState = AVBluetoothManager.State.UNINITIALIZED;
        this.bluetoothServiceListener = new AVBluetoothManager.BluetoothServiceListener();
        this.bluetoothHeadsetReceiver = new AVBluetoothManager.BluetoothHeadsetBroadcastReceiver();
        this.handler = new Handler(Looper.getMainLooper());
    }

    public AVBluetoothManager.State getState() {
        ThreadUtils.checkIsOnMainThread();
        return this.bluetoothState;
    }

    public void start() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "start");
        if (!this.hasPermission(this.avContext, "android.permission.BLUETOOTH")) {
            Log.w("AVBluetoothManager", "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission");
        } else if (this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED) {
            Log.w("AVBluetoothManager", "Invalid BT state");
        } else {
            this.bluetoothHeadset = null;
            this.bluetoothDevice = null;
            this.scoConnectionAttempts = 0;
            this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (this.bluetoothAdapter == null) {
                Log.w("AVBluetoothManager", "Device does not support Bluetooth");
            } else if (!this.audioManager.isBluetoothScoAvailableOffCall()) {
                Log.e("AVBluetoothManager", "Bluetooth SCO audio is not available off call");
            } else {
                this.logBluetoothAdapterInfo(this.bluetoothAdapter);
                if (!this.getBluetoothProfileProxy(this.avContext, this.bluetoothServiceListener, 1)) {
                    Log.e("AVBluetoothManager", "BluetoothAdapter.getProfileProxy(HEADSET) failed");
                } else {
                    IntentFilter bluetoothHeadsetFilter = new IntentFilter();
                    bluetoothHeadsetFilter.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
                    bluetoothHeadsetFilter.addAction("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED");
                    this.registerReceiver(this.bluetoothHeadsetReceiver, bluetoothHeadsetFilter);
                    Log.d("AVBluetoothManager", "HEADSET profile state: " + this.stateToString(this.bluetoothAdapter.getProfileConnectionState(1)));
                    Log.d("AVBluetoothManager", "Bluetooth proxy for headset profile has started");
                    this.bluetoothState = AVBluetoothManager.State.HEADSET_UNAVAILABLE;
                    Log.d("AVBluetoothManager", "start done: BT state=" + this.bluetoothState);
                }
            }
        }
    }

    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "stop: BT state=" + this.bluetoothState);
        if (this.bluetoothAdapter != null) {
            this.stopScoAudio();
            if (this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED) {
                this.unregisterReceiver(this.bluetoothHeadsetReceiver);
                this.cancelTimer();
                if (this.bluetoothHeadset != null) {
                    this.bluetoothAdapter.closeProfileProxy(1, this.bluetoothHeadset);
                    this.bluetoothHeadset = null;
                }

                this.bluetoothAdapter = null;
                this.bluetoothDevice = null;
                this.bluetoothState = AVBluetoothManager.State.UNINITIALIZED;
                Log.d("AVBluetoothManager", "stop done: BT state=" + this.bluetoothState);
            }
        }
    }

    public boolean startScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "startSco: BT state=" + this.bluetoothState + ", attempts: " + this.scoConnectionAttempts + ", SCO is on: " + this.isScoOn());
        if (this.scoConnectionAttempts >= 2) {
            Log.e("AVBluetoothManager", "BT SCO connection fails - no more attempts");
            return false;
        } else if (this.bluetoothState != AVBluetoothManager.State.HEADSET_AVAILABLE) {
            Log.e("AVBluetoothManager", "BT SCO connection fails - no headset available");
            return false;
        } else {
            Log.d("AVBluetoothManager", "Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...");
            this.bluetoothState = AVBluetoothManager.State.SCO_CONNECTING;
            this.audioManager.startBluetoothSco();
            this.audioManager.setBluetoothScoOn(true);
            ++this.scoConnectionAttempts;
            this.startTimer();
            Log.d("AVBluetoothManager", "startScoAudio done: BT state=" + this.bluetoothState + ", SCO is on: " + this.isScoOn());
            return true;
        }
    }

    public void stopScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "stopScoAudio: BT state=" + this.bluetoothState + ", SCO is on: " + this.isScoOn());
        if (this.bluetoothState == AVBluetoothManager.State.SCO_CONNECTING || this.bluetoothState == AVBluetoothManager.State.SCO_CONNECTED) {
            this.cancelTimer();
            this.audioManager.stopBluetoothSco();
            this.audioManager.setBluetoothScoOn(false);
            this.bluetoothState = AVBluetoothManager.State.SCO_DISCONNECTING;
            Log.d("AVBluetoothManager", "stopScoAudio done: BT state=" + this.bluetoothState + ", SCO is on: " + this.isScoOn());
        }
    }

    public void updateDevice() {
        if (this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED && this.bluetoothHeadset != null) {
            Log.d("AVBluetoothManager", "updateDevice");
            List<BluetoothDevice> devices = this.bluetoothHeadset.getConnectedDevices();
            if (devices.isEmpty()) {
                this.bluetoothDevice = null;
                this.bluetoothState = AVBluetoothManager.State.HEADSET_UNAVAILABLE;
                Log.d("AVBluetoothManager", "No connected bluetooth headset");
            } else {
                this.bluetoothDevice = (BluetoothDevice)devices.get(0);
                this.bluetoothState = AVBluetoothManager.State.HEADSET_AVAILABLE;
                Log.d("AVBluetoothManager", "Connected bluetooth headset: name=" + this.bluetoothDevice.getName() + ", state=" + this.stateToString(this.bluetoothHeadset.getConnectionState(this.bluetoothDevice)) + ", SCO audio=" + this.bluetoothHeadset.isAudioConnected(this.bluetoothDevice));
            }

            Log.d("AVBluetoothManager", "updateDevice done: BT state=" + this.bluetoothState);
        }
    }

    protected AudioManager getAudioManager(Context context) {
        return (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    }

    protected void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        this.avContext.registerReceiver(receiver, filter);
    }

    protected void unregisterReceiver(BroadcastReceiver receiver) {
        this.avContext.unregisterReceiver(receiver);
    }

    protected boolean getBluetoothProfileProxy(Context context, ServiceListener listener, int profile) {
        return this.bluetoothAdapter.getProfileProxy(context, listener, profile);
    }

    protected boolean hasPermission(Context context, String permission) {
        return this.avContext.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint({"HardwareIds"})
    protected void logBluetoothAdapterInfo(BluetoothAdapter localAdapter) {
        Log.d("AVBluetoothManager", "BluetoothAdapter: enabled=" + localAdapter.isEnabled() + ", state=" + this.stateToString(localAdapter.getState()) + ", name=" + localAdapter.getName() + ", address=" + localAdapter.getAddress());
        Set<BluetoothDevice> pairedDevices = localAdapter.getBondedDevices();
        if (!pairedDevices.isEmpty()) {
            Log.d("AVBluetoothManager", "paired devices:");
            Iterator var3 = pairedDevices.iterator();

            while(var3.hasNext()) {
                BluetoothDevice device = (BluetoothDevice)var3.next();
                Log.d("AVBluetoothManager", " name=" + device.getName() + ", address=" + device.getAddress());
            }
        }

    }

    private void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "updateAudioDeviceState");
        this.avAudioManager.updateAudioDeviceState();
    }

    private void startTimer() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "startTimer");
        this.handler.postDelayed(this.bluetoothTimeoutRunnable, 4000L);
    }

    private void cancelTimer() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVBluetoothManager", "cancelTimer");
        this.handler.removeCallbacks(this.bluetoothTimeoutRunnable);
    }

    private void bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread();
        if (this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED && this.bluetoothHeadset != null) {
            Log.d("AVBluetoothManager", "bluetoothTimeout: BT state=" + this.bluetoothState + ", attempts: " + this.scoConnectionAttempts + ", SCO is on: " + this.isScoOn());
            if (this.bluetoothState == AVBluetoothManager.State.SCO_CONNECTING) {
                boolean scoConnected = false;
                List<BluetoothDevice> devices = this.bluetoothHeadset.getConnectedDevices();
                if (devices.size() > 0) {
                    this.bluetoothDevice = (BluetoothDevice)devices.get(0);
                    if (this.bluetoothHeadset.isAudioConnected(this.bluetoothDevice)) {
                        Log.d("AVBluetoothManager", "SCO connected with " + this.bluetoothDevice.getName());
                        scoConnected = true;
                    } else {
                        Log.d("AVBluetoothManager", "SCO is not connected with " + this.bluetoothDevice.getName());
                    }
                }

                if (scoConnected) {
                    this.bluetoothState = AVBluetoothManager.State.SCO_CONNECTED;
                    this.scoConnectionAttempts = 0;
                } else {
                    Log.w("AVBluetoothManager", "BT failed to connect after timeout");
                    this.stopScoAudio();
                }

                this.updateAudioDeviceState();
                Log.d("AVBluetoothManager", "bluetoothTimeout done: BT state=" + this.bluetoothState);
            }
        }
    }

    private boolean isScoOn() {
        return this.audioManager.isBluetoothScoOn();
    }

    private String stateToString(int state) {
        switch(state) {
            case 0:
                return "DISCONNECTED";
            case 1:
                return "CONNECTING";
            case 2:
                return "CONNECTED";
            case 3:
                return "DISCONNECTING";
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
            default:
                return "INVALID";
            case 10:
                return "OFF";
            case 11:
                return "TURNING_ON";
            case 12:
                return "ON";
            case 13:
                return "TURNING_OFF";
        }
    }

    private class BluetoothHeadsetBroadcastReceiver extends BroadcastReceiver {
        private BluetoothHeadsetBroadcastReceiver() {
        }

        public void onReceive(Context context, Intent intent) {
            if (AVBluetoothManager.this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED) {
                String action = intent.getAction();
                int state;
                if (action.equals("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")) {
                    state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", 0);
                    Log.d("AVBluetoothManager", "BluetoothHeadsetBroadcastReceiver.onReceive: a=ACTION_CONNECTION_STATE_CHANGED, s=" + AVBluetoothManager.this.stateToString(state) + ", sb=" + this.isInitialStickyBroadcast() + ", BT state: " + AVBluetoothManager.this.bluetoothState);
                    if (state == 2) {
                        AVBluetoothManager.this.scoConnectionAttempts = 0;
                        AVBluetoothManager.this.updateAudioDeviceState();
                    } else if (state != 1 && state != 3 && state == 0) {
                        AVBluetoothManager.this.stopScoAudio();
                        AVBluetoothManager.this.updateAudioDeviceState();
                    }
                } else if (action.equals("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED")) {
                    state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", 10);
                    Log.d("AVBluetoothManager", "BluetoothHeadsetBroadcastReceiver.onReceive: a=ACTION_AUDIO_STATE_CHANGED, s=" + AVBluetoothManager.this.stateToString(state) + ", sb=" + this.isInitialStickyBroadcast() + ", BT state: " + AVBluetoothManager.this.bluetoothState);
                    if (state == 12) {
                        AVBluetoothManager.this.cancelTimer();
                        if (AVBluetoothManager.this.bluetoothState == AVBluetoothManager.State.SCO_CONNECTING) {
                            Log.d("AVBluetoothManager", "+++ Bluetooth audio SCO is now connected");
                            AVBluetoothManager.this.bluetoothState = AVBluetoothManager.State.SCO_CONNECTED;
                            AVBluetoothManager.this.scoConnectionAttempts = 0;
                            AVBluetoothManager.this.updateAudioDeviceState();
                        } else {
                            Log.w("AVBluetoothManager", "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED");
                        }
                    } else if (state == 11) {
                        Log.d("AVBluetoothManager", "+++ Bluetooth audio SCO is now connecting...");
                    } else if (state == 10) {
                        Log.d("AVBluetoothManager", "+++ Bluetooth audio SCO is now disconnected");
                        if (this.isInitialStickyBroadcast()) {
                            Log.d("AVBluetoothManager", "Ignore STATE_AUDIO_DISCONNECTED initial sticky broadcast.");
                            return;
                        }

                        AVBluetoothManager.this.updateAudioDeviceState();
                    }
                }

                Log.d("AVBluetoothManager", "onReceive done: BT state=" + AVBluetoothManager.this.bluetoothState);
            }
        }
    }

    private class BluetoothServiceListener implements ServiceListener {
        private BluetoothServiceListener() {
        }

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == 1 && AVBluetoothManager.this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED) {
                Log.d("AVBluetoothManager", "BluetoothServiceListener.onServiceConnected: BT state=" + AVBluetoothManager.this.bluetoothState);
                AVBluetoothManager.this.bluetoothHeadset = (BluetoothHeadset)proxy;
                AVBluetoothManager.this.updateAudioDeviceState();
                Log.d("AVBluetoothManager", "onServiceConnected done: BT state=" + AVBluetoothManager.this.bluetoothState);
            }
        }

        public void onServiceDisconnected(int profile) {
            if (profile == 1 && AVBluetoothManager.this.bluetoothState != AVBluetoothManager.State.UNINITIALIZED) {
                Log.d("AVBluetoothManager", "BluetoothServiceListener.onServiceDisconnected: BT state=" + AVBluetoothManager.this.bluetoothState);
                AVBluetoothManager.this.stopScoAudio();
                AVBluetoothManager.this.bluetoothHeadset = null;
                AVBluetoothManager.this.bluetoothDevice = null;
                AVBluetoothManager.this.bluetoothState = AVBluetoothManager.State.HEADSET_UNAVAILABLE;
                AVBluetoothManager.this.updateAudioDeviceState();
                Log.d("AVBluetoothManager", "onServiceDisconnected done: BT state=" + AVBluetoothManager.this.bluetoothState);
            }
        }
    }

    public static enum State {
        UNINITIALIZED,
        ERROR,
        HEADSET_UNAVAILABLE,
        HEADSET_AVAILABLE,
        SCO_DISCONNECTING,
        SCO_CONNECTING,
        SCO_CONNECTED;
    }
}