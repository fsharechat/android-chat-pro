package com.comsince.github.groupcallenginekit;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.comsince.github.p2penginekit.VideoProfile;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class DefaultPeerConnectionFactory {
    private static String TAG = "DefaultPeerConnectionFactory";
    protected PeerConnectionFactory defaultPeerConnectionFactory;
    protected PeerConnectionParameters peerConnectionParameters;
    protected EglBase rootEglBase = EglBase.create();
    private PeerConnectionFactory.Options options = null;
    private Map<String,GroupPeerConnectionClient.PeerConnectionEvents> peerConnectionEventsMap = new HashMap<>();
    protected String preferredVideoCodec;
    private static final String VIDEO_CODEC_VP8 = "VP8";
    private static final String VIDEO_CODEC_VP9 = "VP9";
    private static final String VIDEO_CODEC_H264 = "H264";
    private static final String VIDEO_CODEC_H264_BASELINE = "H264 Baseline";
    private static final String VIDEO_CODEC_H264_HIGH = "H264 High";

    public DefaultPeerConnectionFactory(Context mContent){
        VideoProfile videoProfile = VideoProfile.getVideoProfile(20, false);
        createPeerConnectionFactory(mContent,  false,
                videoProfile.width, videoProfile.height, videoProfile.fps, videoProfile.bitrate, StreamMode.SENDRECV);
    }

    private void createPeerConnectionFactory(Context context,boolean enableVideo, int videoWidth, int videoHeight,
                                            int videoFps, int maxBitrate, StreamMode streamMode) {
        this.peerConnectionParameters = new DefaultPeerConnectionFactory.PeerConnectionParameters(enableVideo, false, videoWidth, videoHeight, videoFps, maxBitrate,
                "H264 Baseline", true, true, 0, "OPUS", false, false,
                false, true, true, true, false, false, streamMode,null);

        createPeerConnectionFactoryInternal(context);
    }

    private void createPeerConnectionFactoryInternal(Context context) {
        String fieldTrials = "";
        if (this.peerConnectionParameters.videoFlexfecEnabled) {
            fieldTrials = fieldTrials + "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/";
            Log.d("PCRTCClient", "Enable FlexFEC field trial.");
        }

        fieldTrials = fieldTrials + "WebRTC-IntelVP8/Enabled/";
        if (this.peerConnectionParameters.disableWebRtcAGCAndHPF) {
            fieldTrials = fieldTrials + "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/";
            Log.d("PCRTCClient", "Disable WebRTC AGC field trial.");
        }

        fieldTrials = fieldTrials + "VideoFrameEmit/Enabled/";
        this.preferredVideoCodec = "VP8";
        if (this.peerConnectionParameters.videoCallEnabled && this.peerConnectionParameters.videoCodec != null) {
            String videoCodec = this.peerConnectionParameters.videoCodec;
            switch(videoCodec) {
                case VIDEO_CODEC_H264_HIGH:
                    fieldTrials = fieldTrials + "WebRTC-H264HighProfile/Enabled/";
                    this.preferredVideoCodec = "H264";
                    break;
                case VIDEO_CODEC_H264_BASELINE:
                    this.preferredVideoCodec = "H264";
                    break;
                case VIDEO_CODEC_VP9:
                    this.preferredVideoCodec = "VP9";
                    break;
                default:
                    this.preferredVideoCodec = "VP8";
                    break;
            }
        }

        Log.d(TAG, "Preferred video codec: " + this.preferredVideoCodec);
        Log.d(TAG, "Initialize WebRTC. Field trials: " + fieldTrials + " Enable video HW acceleration: " + this.peerConnectionParameters.videoCodecHwAcceleration);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setFieldTrials(fieldTrials).setEnableInternalTracer(true).createInitializationOptions());
        if (this.peerConnectionParameters.tracing) {
            PeerConnectionFactory.startInternalTracingCapture(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "webrtc-trace.txt");
        }

        if (!this.peerConnectionParameters.useOpenSLES) {
            Log.d(TAG, "Disable OpenSL ES audio even if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
        } else {
            Log.d(TAG, "Allow OpenSL ES audio if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false);
        }

        if (this.peerConnectionParameters.disableBuiltInAEC) {
            Log.d(TAG, "Disable built-in AEC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
        } else {
            Log.d(TAG, "Enable built-in AEC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false);
        }

        if (this.peerConnectionParameters.disableBuiltInAGC) {
            Log.d(TAG, "Disable built-in AGC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
        } else {
            Log.d(TAG, "Enable built-in AGC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false);
        }

        if (this.peerConnectionParameters.disableBuiltInNS) {
            Log.d(TAG, "Disable built-in NS even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
        } else {
            Log.d(TAG, "Enable built-in NS if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false);
        }

        WebRtcAudioRecord.setErrorCallback(new WebRtcAudioRecord.WebRtcAudioRecordErrorCallback() {
            public void onWebRtcAudioRecordInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
                DefaultPeerConnectionFactory.this.reportError(errorMessage);
            }

            public void onWebRtcAudioRecordStartError(WebRtcAudioRecord.AudioRecordStartErrorCode errorCode, String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
                DefaultPeerConnectionFactory.this.reportError(errorMessage);
            }

            public void onWebRtcAudioRecordError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
                DefaultPeerConnectionFactory.this.reportError(errorMessage);
            }
        });
        WebRtcAudioTrack.setErrorCallback(new WebRtcAudioTrack.ErrorCallback() {
            public void onWebRtcAudioTrackInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: " + errorMessage);
                DefaultPeerConnectionFactory.this.reportError(errorMessage);
            }

            public void onWebRtcAudioTrackStartError(WebRtcAudioTrack.AudioTrackStartErrorCode errorCode, String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: " + errorCode + ". " + errorMessage);
                DefaultPeerConnectionFactory.this.reportError(errorMessage);
            }

            public void onWebRtcAudioTrackError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackError: " + errorMessage);
                DefaultPeerConnectionFactory.this.reportError(errorMessage);
            }
        });
        if (this.options != null) {
            Log.d(TAG, "Factory networkIgnoreMask option: " + this.options.networkIgnoreMask);
        }

        boolean enableH264HighProfile = "H264 High".equals(this.peerConnectionParameters.videoCodec);
        VideoDecoderFactory decoderFactory;
        VideoEncoderFactory encoderFactory;
        if (this.peerConnectionParameters.videoCodecHwAcceleration) {
            encoderFactory = new DefaultVideoEncoderFactory(this.rootEglBase.getEglBaseContext(), true, enableH264HighProfile);
            decoderFactory = new DefaultVideoDecoderFactory(this.rootEglBase.getEglBaseContext());
        } else {
            encoderFactory = new SoftwareVideoEncoderFactory();
            decoderFactory = new SoftwareVideoDecoderFactory();
        }

        this.defaultPeerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
        Log.d(TAG, "Peer connection factory created.");
    }

    public void dispose(){
        if (this.defaultPeerConnectionFactory != null && this.peerConnectionParameters.aecDump) {
            this.defaultPeerConnectionFactory.stopAecDump();
        }
        if (this.defaultPeerConnectionFactory != null) {
            this.defaultPeerConnectionFactory.dispose();
            this.defaultPeerConnectionFactory = null;
        }

        this.options = null;
        this.rootEglBase.release();
        Log.d("PCRTCClient", "Closing peer connection done.");
        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
    }


    public static class PeerConnectionParameters {
        public boolean videoCallEnabled;
        public final boolean tracing;
        public final int videoWidth;
        public final int videoHeight;
        public final int videoFps;
        public final int videoMaxBitrate;
        public final String videoCodec;
        public final boolean videoCodecHwAcceleration;
        public final boolean videoFlexfecEnabled;
        public final int audioStartBitrate;
        public final String audioCodec;
        public final boolean noAudioProcessing;
        public final boolean aecDump;
        public final boolean useOpenSLES;
        public final boolean disableBuiltInAEC;
        public final boolean disableBuiltInAGC;
        public final boolean disableBuiltInNS;
        public final boolean enableLevelControl;
        public final boolean disableWebRtcAGCAndHPF;
        public StreamMode streamMode;
        public final DataChannelParameters dataChannelParameters;

        public PeerConnectionParameters(boolean videoCallEnabled, boolean tracing, int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate, String videoCodec,
                                        boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled, int audioStartBitrate, String audioCodec, boolean noAudioProcessing,
                                        boolean aecDump, boolean useOpenSLES, boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS, boolean enableLevelControl,
                                        boolean disableWebRtcAGCAndHPF,StreamMode streamMode) {
            this(videoCallEnabled, tracing, videoWidth, videoHeight, videoFps, videoMaxBitrate, videoCodec, videoCodecHwAcceleration, videoFlexfecEnabled, audioStartBitrate, audioCodec,
                    noAudioProcessing, aecDump, useOpenSLES, disableBuiltInAEC, disableBuiltInAGC, disableBuiltInNS, enableLevelControl, disableWebRtcAGCAndHPF, streamMode,null);
        }

        public PeerConnectionParameters(boolean videoCallEnabled, boolean tracing, int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate, String videoCodec,
                                        boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled, int audioStartBitrate, String audioCodec, boolean noAudioProcessing,
                                        boolean aecDump, boolean useOpenSLES, boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS, boolean enableLevelControl,
                                        boolean disableWebRtcAGCAndHPF, StreamMode streamMode,DataChannelParameters dataChannelParameters) {
            this.videoCallEnabled = videoCallEnabled;
            this.tracing = tracing;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.videoFps = videoFps;
            this.videoMaxBitrate = videoMaxBitrate;
            this.videoCodec = videoCodec;
            this.videoFlexfecEnabled = videoFlexfecEnabled;
            this.videoCodecHwAcceleration = videoCodecHwAcceleration;
            this.audioStartBitrate = audioStartBitrate;
            this.audioCodec = audioCodec;
            this.noAudioProcessing = noAudioProcessing;
            this.aecDump = aecDump;
            this.useOpenSLES = useOpenSLES;
            this.disableBuiltInAEC = disableBuiltInAEC;
            this.disableBuiltInAGC = disableBuiltInAGC;
            this.disableBuiltInNS = disableBuiltInNS;
            this.enableLevelControl = enableLevelControl;
            this.disableWebRtcAGCAndHPF = disableWebRtcAGCAndHPF;
            this.streamMode = streamMode;
            this.dataChannelParameters = dataChannelParameters;
        }
    }

    public static class DataChannelParameters {
        public final boolean ordered;
        public final int maxRetransmitTimeMs;
        public final int maxRetransmits;
        public final String protocol;
        public final boolean negotiated;
        public final int id;

        public DataChannelParameters(boolean ordered, int maxRetransmitTimeMs, int maxRetransmits, String protocol, boolean negotiated, int id) {
            this.ordered = ordered;
            this.maxRetransmitTimeMs = maxRetransmitTimeMs;
            this.maxRetransmits = maxRetransmits;
            this.protocol = protocol;
            this.negotiated = negotiated;
            this.id = id;
        }
    }

    private void reportError(String errorMessage){

    }
}
