package com.comsince.github.p2penginekit;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build.VERSION;
import android.util.Log;

import com.comsince.github.util.AVUtils;

import org.webrtc.ThreadUtils.ThreadChecker;

public class AVProximitySensor implements SensorEventListener {
    private static final String TAG = "AVProximitySensor";
    private final ThreadChecker threadChecker = new ThreadChecker();
    private final Runnable onSensorStateListener;
    private final SensorManager sensorManager;
    private Sensor proximitySensor = null;
    private boolean lastStateReportIsNear = false;

    static AVProximitySensor create(Context context, Runnable sensorStateListener) {
        return new AVProximitySensor(context, sensorStateListener);
    }

    private AVProximitySensor(Context context, Runnable sensorStateListener) {
        Log.d("AVProximitySensor", "AVProximitySensor" + AVUtils.getThreadInfo());
        this.onSensorStateListener = sensorStateListener;
        this.sensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
    }

    public boolean start() {
        this.threadChecker.checkIsOnValidThread();
        Log.d("AVProximitySensor", "start" + AVUtils.getThreadInfo());
        if (!this.initDefaultSensor()) {
            return false;
        } else {
            this.sensorManager.registerListener(this, this.proximitySensor, 3);
            return true;
        }
    }

    public void stop() {
        this.threadChecker.checkIsOnValidThread();
        Log.d("AVProximitySensor", "stop" + AVUtils.getThreadInfo());
        if (this.proximitySensor != null) {
            this.sensorManager.unregisterListener(this, this.proximitySensor);
        }
    }

    public boolean sensorReportsNearState() {
        this.threadChecker.checkIsOnValidThread();
        return this.lastStateReportIsNear;
    }

    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        this.threadChecker.checkIsOnValidThread();
        AVUtils.assertIsTrue(sensor.getType() == 8);
        if (accuracy == 0) {
            Log.e("AVProximitySensor", "The values returned by this sensor cannot be trusted");
        }

    }

    public final void onSensorChanged(SensorEvent event) {
        this.threadChecker.checkIsOnValidThread();
        AVUtils.assertIsTrue(event.sensor.getType() == 8);
        float distanceInCentimeters = event.values[0];
        if (distanceInCentimeters < Math.floor(this.proximitySensor.getMaximumRange())) {
            Log.d("AVProximitySensor", "Proximity sensor => NEAR state");
            this.lastStateReportIsNear = true;
        } else {
            Log.d("AVProximitySensor", "Proximity sensor => FAR state");
            this.lastStateReportIsNear = false;
        }

        if (this.onSensorStateListener != null) {
            this.onSensorStateListener.run();
        }

        Log.d("AVProximitySensor", "onSensorChanged" + AVUtils.getThreadInfo() + ": accuracy=" + event.accuracy + ", timestamp=" + event.timestamp + ", distance=" + event.values[0]
                +" maximumRange "+this.proximitySensor.getMaximumRange());
    }

    private boolean initDefaultSensor() {
        if (this.proximitySensor != null) {
            return true;
        } else {
            this.proximitySensor = this.sensorManager.getDefaultSensor(8);
            if (this.proximitySensor == null) {
                return false;
            } else {
                this.logProximitySensorInfo();
                return true;
            }
        }
    }

    private void logProximitySensorInfo() {
        if (this.proximitySensor != null) {
            StringBuilder info = new StringBuilder("Proximity sensor: ");
            info.append("name=").append(this.proximitySensor.getName());
            info.append(", vendor: ").append(this.proximitySensor.getVendor());
            info.append(", power: ").append(this.proximitySensor.getPower());
            info.append(", resolution: ").append(this.proximitySensor.getResolution());
            info.append(", max range: ").append(this.proximitySensor.getMaximumRange());
            info.append(", min delay: ").append(this.proximitySensor.getMinDelay());
            if (VERSION.SDK_INT >= 20) {
                info.append(", type: ").append(this.proximitySensor.getStringType());
            }

            if (VERSION.SDK_INT >= 21) {
                info.append(", max delay: ").append(this.proximitySensor.getMaxDelay());
                info.append(", reporting mode: ").append(this.proximitySensor.getReportingMode());
                info.append(", isWakeUpSensor: ").append(this.proximitySensor.isWakeUpSensor());
            }

            Log.d("AVProximitySensor", info.toString());
        }
    }
}
