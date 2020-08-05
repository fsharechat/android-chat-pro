package com.comsince.github.p2penginekit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.os.Build.VERSION;
import android.preference.PreferenceManager;
import android.util.Log;
import com.comsince.github.p2penginekit.AVBluetoothManager.State;
import com.comsince.github.util.AVUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.webrtc.ThreadUtils;

public class AVAudioManager {
    private static final String TAG = "AVAudioManager";
    private static final String SPEAKERPHONE_AUTO = "auto";
    private static final String SPEAKERPHONE_TRUE = "true";
    private static final String SPEAKERPHONE_FALSE = "false";
    private final Context avContext;
    private AudioManager audioManager;
    private AVAudioManager.AudioManagerEvents audioManagerEvents;
    private AVAudioManager.AudioManagerState amState;
    private int savedAudioMode = -2;
    private boolean savedIsSpeakerPhoneOn = false;
    private boolean savedIsMicrophoneMute = false;
    private boolean hasWiredHeadset = false;
    private AVAudioManager.AudioDevice defaultAudioDevice;
    private AVAudioManager.AudioDevice selectedAudioDevice;
    private AVAudioManager.AudioDevice userSelectedAudioDevice;
    private final String useSpeakerphone;
    private AVProximitySensor proximitySensor = null;
    private final AVBluetoothManager bluetoothManager;
    private Set<AVAudioManager.AudioDevice> audioDevices = new HashSet();
    private BroadcastReceiver wiredHeadsetReceiver;
    private OnAudioFocusChangeListener audioFocusChangeListener;

    private void onProximitySensorChangedState() {
        if (this.useSpeakerphone.equals("auto")) {
            if (this.audioDevices.size() == 2 && this.audioDevices.contains(AVAudioManager.AudioDevice.EARPIECE) && this.audioDevices.contains(AVAudioManager.AudioDevice.SPEAKER_PHONE)) {
                if (this.proximitySensor.sensorReportsNearState()) {
                    this.setAudioDeviceInternal(AVAudioManager.AudioDevice.EARPIECE);
                } else {
                    this.setAudioDeviceInternal(AVAudioManager.AudioDevice.SPEAKER_PHONE);
                }
            }

        }
    }

    static AVAudioManager create(Context context) {
        return new AVAudioManager(context);
    }

    private AVAudioManager(Context context) {
        Log.d("AVAudioManager", "ctor");
        ThreadUtils.checkIsOnMainThread();
        this.avContext = context;
        this.audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        this.bluetoothManager = AVBluetoothManager.create(context, this);
        this.wiredHeadsetReceiver = new AVAudioManager.WiredHeadsetReceiver();
        this.amState = AVAudioManager.AudioManagerState.UNINITIALIZED;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.useSpeakerphone = sharedPreferences.getString("speakerphone_preference", "auto");
        Log.i("AVAudioManager", "useSpeakerphone: " + this.useSpeakerphone);
        if (this.useSpeakerphone.equals("false")) {
            this.defaultAudioDevice = AVAudioManager.AudioDevice.EARPIECE;
        } else {
            this.defaultAudioDevice = AVAudioManager.AudioDevice.SPEAKER_PHONE;
        }

        this.proximitySensor = AVProximitySensor.create(context, this::onProximitySensorChangedState);
        Log.d("AVAudioManager", "defaultAudioDevice: " + this.defaultAudioDevice);
        AVUtils.logDeviceInfo("AVAudioManager");
    }

    public void start(AVAudioManager.AudioManagerEvents audioManagerEvents) {
        Log.d("AVAudioManager", "start");
        ThreadUtils.checkIsOnMainThread();
        if (this.amState == AVAudioManager.AudioManagerState.RUNNING) {
            Log.e("AVAudioManager", "AudioManager is already active");
        } else {
            Log.d("AVAudioManager", "AudioManager starts...");
            this.audioManagerEvents = audioManagerEvents;
            this.amState = AVAudioManager.AudioManagerState.RUNNING;
            this.savedAudioMode = this.audioManager.getMode();
            this.savedIsSpeakerPhoneOn = this.audioManager.isSpeakerphoneOn();
            this.savedIsMicrophoneMute = this.audioManager.isMicrophoneMute();
            this.hasWiredHeadset = this.hasWiredHeadset();
            this.audioFocusChangeListener = new OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int focusChange) {
                    String typeOfChange;
                    switch(focusChange) {
                        case -3:
                            typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
                            break;
                        case -2:
                            typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT";
                            break;
                        case -1:
                            typeOfChange = "AUDIOFOCUS_LOSS";
                            break;
                        case 0:
                        default:
                            typeOfChange = "AUDIOFOCUS_INVALID";
                            break;
                        case 1:
                            typeOfChange = "AUDIOFOCUS_GAIN";
                            break;
                        case 2:
                            typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT";
                            break;
                        case 3:
                            typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
                            break;
                        case 4:
                            typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
                    }

                    Log.d("AVAudioManager", "onAudioFocusChange: " + typeOfChange);
                }
            };
            int result = this.audioManager.requestAudioFocus(this.audioFocusChangeListener, 0, 2);
            if (result == 1) {
                Log.d("AVAudioManager", "Audio focus request granted for VOICE_CALL streams");
            } else {
                Log.e("AVAudioManager", "Audio focus request failed");
            }

            this.audioManager.setMode(AudioManager.MODE_NORMAL);
            this.audioManager.setSpeakerphoneOn(true);
            this.setMicrophoneMute(false);
            this.userSelectedAudioDevice = AVAudioManager.AudioDevice.NONE;
            this.selectedAudioDevice = AVAudioManager.AudioDevice.NONE;
            this.audioDevices.clear();
            this.bluetoothManager.start();
            this.updateAudioDeviceState();
            this.registerReceiver(this.wiredHeadsetReceiver, new IntentFilter("android.intent.action.HEADSET_PLUG"));
            Log.d("AVAudioManager", "AudioManager started");
            this.proximitySensor.start();
        }
    }

    public void stop() {
        Log.d("AVAudioManager", "stop");
        ThreadUtils.checkIsOnMainThread();
        if (this.amState != AVAudioManager.AudioManagerState.RUNNING) {
            Log.e("AVAudioManager", "Trying to stop AudioManager in incorrect state: " + this.amState);
        } else {
            this.amState = AVAudioManager.AudioManagerState.UNINITIALIZED;
            this.unregisterReceiver(this.wiredHeadsetReceiver);
            this.bluetoothManager.stop();
            this.setSpeakerphoneOn(this.savedIsSpeakerPhoneOn);
            this.setMicrophoneMute(this.savedIsMicrophoneMute);
            this.audioManager.setMode(this.savedAudioMode);
            this.audioManager.abandonAudioFocus(this.audioFocusChangeListener);
            this.audioFocusChangeListener = null;
            Log.d("AVAudioManager", "Abandoned audio focus for VOICE_CALL streams");
            if (this.proximitySensor != null) {
                this.proximitySensor.stop();
                this.proximitySensor = null;
            }

            this.audioManagerEvents = null;
            Log.d("AVAudioManager", "AudioManager stopped");
        }
    }

    private void setAudioDeviceInternal(AVAudioManager.AudioDevice device) {
        Log.d("AVAudioManager", "setAudioDeviceInternal(device=" + device + ")");
        AVUtils.assertIsTrue(this.audioDevices.contains(device));
        switch(device) {
            case SPEAKER_PHONE:
                this.setSpeakerphoneOn(true);
                break;
            case EARPIECE:
                this.setSpeakerphoneOn(false);
                break;
            case WIRED_HEADSET:
                this.setSpeakerphoneOn(false);
                break;
            case BLUETOOTH:
                this.setSpeakerphoneOn(false);
                break;
            default:
                Log.e("AVAudioManager", "Invalid audio device selection");
        }

        this.selectedAudioDevice = device;
    }

    public void setDefaultAudioDevice(AVAudioManager.AudioDevice defaultDevice) {
        ThreadUtils.checkIsOnMainThread();
        switch(defaultDevice) {
            case SPEAKER_PHONE:
                this.defaultAudioDevice = defaultDevice;
                break;
            case EARPIECE:
                if (this.hasEarpiece()) {
                    this.defaultAudioDevice = defaultDevice;
                } else {
                    this.defaultAudioDevice = AVAudioManager.AudioDevice.SPEAKER_PHONE;
                }
                break;
            default:
                Log.e("AVAudioManager", "Invalid default audio device selection");
        }

        Log.d("AVAudioManager", "setDefaultAudioDevice(device=" + this.defaultAudioDevice + ")");
        this.updateAudioDeviceState();
    }

    public void selectAudioDevice(AVAudioManager.AudioDevice device) {
        ThreadUtils.checkIsOnMainThread();
        if (!this.audioDevices.contains(device)) {
            Log.e("AVAudioManager", "Can not select " + device + " from available " + this.audioDevices);
        }

        this.userSelectedAudioDevice = device;
        this.updateAudioDeviceState();
    }

    public Set<AVAudioManager.AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableSet(new HashSet(this.audioDevices));
    }

    public AVAudioManager.AudioDevice getSelectedAudioDevice() {
        ThreadUtils.checkIsOnMainThread();
        return this.selectedAudioDevice;
    }

    private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        this.avContext.registerReceiver(receiver, filter);
    }

    private void unregisterReceiver(BroadcastReceiver receiver) {
        this.avContext.unregisterReceiver(receiver);
    }

    private void setSpeakerphoneOn(boolean on) {
        boolean wasOn = this.audioManager.isSpeakerphoneOn();
        if (wasOn != on) {
            this.audioManager.setSpeakerphoneOn(on);
        }
    }

    private void setMicrophoneMute(boolean on) {
        boolean wasMuted = this.audioManager.isMicrophoneMute();
        if (wasMuted != on) {
            this.audioManager.setMicrophoneMute(on);
        }
    }

    private boolean hasEarpiece() {
        return this.avContext.getPackageManager().hasSystemFeature("android.hardware.telephony");
    }

    /** @deprecated */
    @Deprecated
    private boolean hasWiredHeadset() {
        if (VERSION.SDK_INT < 23) {
            return this.audioManager.isWiredHeadsetOn();
        } else {
            AudioDeviceInfo[] devices = this.audioManager.getDevices(3);
            AudioDeviceInfo[] var2 = devices;
            int var3 = devices.length;

            for(int var4 = 0; var4 < var3; ++var4) {
                AudioDeviceInfo device = var2[var4];
                int type = device.getType();
                if (type == 3) {
                    Log.d("AVAudioManager", "hasWiredHeadset: found wired headset");
                    return true;
                }

                if (type == 11) {
                    Log.d("AVAudioManager", "hasWiredHeadset: found USB audio device");
                    return true;
                }
            }

            return false;
        }
    }

    public void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        Log.d("AVAudioManager", "--- updateAudioDeviceState: wired headset=" + this.hasWiredHeadset + ", BT state=" + this.bluetoothManager.getState());
        Log.d("AVAudioManager", "Device status: available=" + this.audioDevices + ", selected=" + this.selectedAudioDevice + ", user selected=" + this.userSelectedAudioDevice);
        if (this.bluetoothManager.getState() == State.HEADSET_AVAILABLE || this.bluetoothManager.getState() == State.HEADSET_UNAVAILABLE || this.bluetoothManager.getState() == State.SCO_DISCONNECTING) {
            this.bluetoothManager.updateDevice();
        }

        Set<AVAudioManager.AudioDevice> newAudioDevices = new HashSet();
        if (this.bluetoothManager.getState() == State.SCO_CONNECTED || this.bluetoothManager.getState() == State.SCO_CONNECTING || this.bluetoothManager.getState() == State.HEADSET_AVAILABLE) {
            newAudioDevices.add(AVAudioManager.AudioDevice.BLUETOOTH);
        }

        if (this.hasWiredHeadset) {
            newAudioDevices.add(AVAudioManager.AudioDevice.WIRED_HEADSET);
        } else {
            newAudioDevices.add(AVAudioManager.AudioDevice.SPEAKER_PHONE);
            if (this.hasEarpiece()) {
                newAudioDevices.add(AVAudioManager.AudioDevice.EARPIECE);
            }
        }

        boolean audioDeviceSetUpdated = !this.audioDevices.equals(newAudioDevices);
        this.audioDevices = newAudioDevices;
        if (this.bluetoothManager.getState() == State.HEADSET_UNAVAILABLE && this.userSelectedAudioDevice == AVAudioManager.AudioDevice.BLUETOOTH) {
            this.userSelectedAudioDevice = AVAudioManager.AudioDevice.NONE;
        }

        if (this.hasWiredHeadset && this.userSelectedAudioDevice == AVAudioManager.AudioDevice.SPEAKER_PHONE) {
            this.userSelectedAudioDevice = AVAudioManager.AudioDevice.WIRED_HEADSET;
        }

        if (!this.hasWiredHeadset && this.userSelectedAudioDevice == AVAudioManager.AudioDevice.WIRED_HEADSET) {
            this.userSelectedAudioDevice = AVAudioManager.AudioDevice.SPEAKER_PHONE;
        }

        boolean needBluetoothAudioStart = this.bluetoothManager.getState() == State.HEADSET_AVAILABLE && (this.userSelectedAudioDevice == AVAudioManager.AudioDevice.NONE || this.userSelectedAudioDevice == AVAudioManager.AudioDevice.BLUETOOTH);
        boolean needBluetoothAudioStop = (this.bluetoothManager.getState() == State.SCO_CONNECTED || this.bluetoothManager.getState() == State.SCO_CONNECTING) && this.userSelectedAudioDevice != AVAudioManager.AudioDevice.NONE && this.userSelectedAudioDevice != AVAudioManager.AudioDevice.BLUETOOTH;
        if (this.bluetoothManager.getState() == State.HEADSET_AVAILABLE || this.bluetoothManager.getState() == State.SCO_CONNECTING || this.bluetoothManager.getState() == State.SCO_CONNECTED) {
            Log.d("AVAudioManager", "Need BT audio: start=" + needBluetoothAudioStart + ", stop=" + needBluetoothAudioStop + ", BT state=" + this.bluetoothManager.getState());
        }

        if (needBluetoothAudioStop) {
            this.bluetoothManager.stopScoAudio();
            this.bluetoothManager.updateDevice();
        }

        if (needBluetoothAudioStart && !needBluetoothAudioStop && !this.bluetoothManager.startScoAudio()) {
            this.audioDevices.remove(AVAudioManager.AudioDevice.BLUETOOTH);
            audioDeviceSetUpdated = true;
        }

        AVAudioManager.AudioDevice newAudioDevice;
        if (this.bluetoothManager.getState() == State.SCO_CONNECTED) {
            newAudioDevice = AVAudioManager.AudioDevice.BLUETOOTH;
        } else if (this.hasWiredHeadset) {
            newAudioDevice = AVAudioManager.AudioDevice.WIRED_HEADSET;
        } else {
            newAudioDevice = this.defaultAudioDevice;
        }

        if (newAudioDevice != this.selectedAudioDevice || audioDeviceSetUpdated) {
            this.setAudioDeviceInternal(newAudioDevice);
            Log.i("AVAudioManager", "New device status: available=" + this.audioDevices + ", selected=" + newAudioDevice);
            if (this.audioManagerEvents != null) {
                this.audioManagerEvents.onAudioDeviceChanged(this.selectedAudioDevice, this.audioDevices);
            }
        }

        Log.d("AVAudioManager", "--- updateAudioDeviceState done");
    }

    private class WiredHeadsetReceiver extends BroadcastReceiver {
        private static final int STATE_UNPLUGGED = 0;
        private static final int STATE_PLUGGED = 1;
        private static final int HAS_NO_MIC = 0;
        private static final int HAS_MIC = 1;

        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", 0);
            int microphone = intent.getIntExtra("microphone", 0);
            String name = intent.getStringExtra("name");
            Log.d("AVAudioManager", "WiredHeadsetReceiver.onReceive" + AVUtils.getThreadInfo() + ": a=" + intent.getAction() + ", s=" + (state == 0 ? "unplugged" : "plugged") + ", m=" + (microphone == 1 ? "mic" : "no mic") + ", n=" + name + ", sb=" + this.isInitialStickyBroadcast());
            AVAudioManager.this.hasWiredHeadset = state == 1;
            AVAudioManager.this.updateAudioDeviceState();
        }
    }

    public interface AudioManagerEvents {
        void onAudioDeviceChanged(AVAudioManager.AudioDevice var1, Set<AVAudioManager.AudioDevice> var2);
    }

    public enum AudioManagerState {
        UNINITIALIZED,
        PREINITIALIZED,
        RUNNING;
    }

    public enum AudioDevice {
        SPEAKER_PHONE,
        WIRED_HEADSET,
        EARPIECE,
        BLUETOOTH,
        NONE;
    }
}
