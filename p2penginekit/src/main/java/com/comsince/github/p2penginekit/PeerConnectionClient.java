package com.comsince.github.p2penginekit;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler;
import org.webrtc.DataChannel.Buffer;
import org.webrtc.DataChannel.Init;
import org.webrtc.Logging.Severity;
import org.webrtc.MediaConstraints.KeyValuePair;
import org.webrtc.PeerConnection.BundlePolicy;
import org.webrtc.PeerConnection.ContinualGatheringPolicy;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnection.IceGatheringState;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.PeerConnection.KeyType;
import org.webrtc.PeerConnection.Observer;
import org.webrtc.PeerConnection.RTCConfiguration;
import org.webrtc.PeerConnection.RtcpMuxPolicy;
import org.webrtc.PeerConnection.SignalingState;
import org.webrtc.PeerConnection.TcpCandidatePolicy;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.PeerConnectionFactory.Options;
import org.webrtc.RtpParameters.Encoding;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;
import org.webrtc.voiceengine.WebRtcAudioUtils;
import org.webrtc.voiceengine.WebRtcAudioRecord.AudioRecordStartErrorCode;
import org.webrtc.voiceengine.WebRtcAudioRecord.WebRtcAudioRecordErrorCallback;
import org.webrtc.voiceengine.WebRtcAudioTrack.AudioTrackStartErrorCode;
import org.webrtc.voiceengine.WebRtcAudioTrack.ErrorCallback;

public class PeerConnectionClient {
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";
    public static final String VIDEO_TRACK_TYPE = "video";
    private static final String TAG = "PCRTCClient";
    private static final String VIDEO_CODEC_VP8 = "VP8";
    private static final String VIDEO_CODEC_VP9 = "VP9";
    private static final String VIDEO_CODEC_H264 = "H264";
    private static final String VIDEO_CODEC_H264_BASELINE = "H264 Baseline";
    private static final String VIDEO_CODEC_H264_HIGH = "H264 High";
    private static final String AUDIO_CODEC_OPUS = "opus";
    private static final String AUDIO_CODEC_ISAC = "ISAC";
    private static final String VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate";
    private static final String VIDEO_FLEXFEC_FIELDTRIAL = "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/";
    private static final String VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL = "WebRTC-IntelVP8/Enabled/";
    private static final String VIDEO_H264_HIGH_PROFILE_FIELDTRIAL = "WebRTC-H264HighProfile/Enabled/";
    private static final String DISABLE_WEBRTC_AGC_FIELDTRIAL = "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/";
    private static final String VIDEO_FRAME_EMIT_FIELDTRIAL = "VideoFrameEmit/Enabled/";
    private static final String AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate";
    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
    private static final int HD_VIDEO_WIDTH = 1280;
    private static final int HD_VIDEO_HEIGHT = 720;
    private static final int BPS_IN_KBPS = 1000;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PeerConnectionClient.PCObserver pcObserver = new PeerConnectionClient.PCObserver();
    private final PeerConnectionClient.SDPObserver sdpObserver = new PeerConnectionClient.SDPObserver();
    private final EglBase rootEglBase = EglBase.create();
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    Options options = null;
    private AudioSource audioSource;
    private VideoSource videoSource;
    private boolean videoCallEnabled;
    private boolean preferIsac;
    private String preferredVideoCodec;
    private boolean videoCapturerStopped;
    private boolean isError;
    private Timer statsTimer;
    public List<IceServer> iceServers;
    private int videoWidth;
    private int videoHeight;
    private int videoFps;
    private MediaConstraints audioConstraints;
    private MediaConstraints sdpMediaConstraints;
    private PeerConnectionClient.PeerConnectionParameters peerConnectionParameters;
    private List<IceCandidate> queuedRemoteCandidates;
    private PeerConnectionClient.PeerConnectionEvents events;
    private boolean isInitiator;
    private SessionDescription localSdp;
    private MediaStream mediaStream;
    private VideoCapturer videoCapturer;
    private boolean renderVideo;
    private VideoTrack localVideoTrack;
    private VideoTrack remoteVideoTrack;
    private RtpSender localVideoSender;
    private boolean enableAudio;
    private AudioTrack localAudioTrack;
    private DataChannel dataChannel;
    private boolean dataChannelEnabled;

    public PeerConnectionClient() {
    }

    public void setPeerConnectionFactoryOptions(Options options) {
        this.options = options;
    }

    public void createPeerConnectionFactory(Context context, PeerConnectionClient.PeerConnectionEvents events, boolean enableVideo, int videoWidth, int videoHeight, int videoFps, int maxBitrate) {
        this.peerConnectionParameters = new PeerConnectionClient.PeerConnectionParameters(enableVideo, false, videoWidth, videoHeight, videoFps, maxBitrate, "H264 Baseline", true, true, 0, "OPUS", false, false, false, true, true, true, false, false, (PeerConnectionClient.DataChannelParameters)null);
        this.events = events;
        this.videoCallEnabled = this.peerConnectionParameters.videoCallEnabled;
        this.dataChannelEnabled = this.peerConnectionParameters.dataChannelParameters != null;
        this.factory = null;
        this.peerConnection = null;
        this.preferIsac = false;
        this.videoCapturerStopped = false;
        this.isError = false;
        this.queuedRemoteCandidates = null;
        this.localSdp = null;
        this.mediaStream = null;
        this.videoCapturer = null;
        this.renderVideo = true;
        this.localVideoTrack = null;
        this.remoteVideoTrack = null;
        this.localVideoSender = null;
        this.enableAudio = true;
        this.localAudioTrack = null;
        this.statsTimer = new Timer();
        if (this.peerConnectionParameters.videoCallEnabled) {
            this.videoWidth = this.peerConnectionParameters.videoWidth;
            this.videoHeight = this.peerConnectionParameters.videoHeight;
            this.videoFps = this.peerConnectionParameters.videoFps;
            if (videoWidth == 0 || videoHeight == 0) {
                this.videoWidth = 1280;
                this.videoHeight = 720;
            }

            if (this.videoFps == 0) {
                this.videoFps = 30;
            }

            Logging.d("PCRTCClient", "Capturing format: " + videoWidth + "x" + videoHeight + "@" + videoFps);
        }

        this.createPeerConnectionFactoryInternal(context);
    }

    public void removeVideoTrack() {
        if (this.mediaStream != null && this.localVideoTrack != null) {
            this.mediaStream.removeTrack(this.localVideoTrack);
            this.localVideoTrack.dispose();
            this.localVideoTrack = null;
            this.videoCallEnabled = false;
            this.events.onRemoveLocalVideoTrack();
        }

    }

    public void createPeerConnection(VideoCapturer videoCapturer, List<IceServer> iceServers) {
        if (this.peerConnectionParameters == null) {
            Log.e("PCRTCClient", "Creating peer connection without initializing factory.");
        } else {
            this.videoCapturer = videoCapturer;
            this.iceServers = iceServers;
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        PeerConnectionClient.this.createMediaConstraintsInternal();
                        PeerConnectionClient.this.createPeerConnectionInternal();
                    } catch (Exception var2) {
                        PeerConnectionClient.this.reportError("Failed to create peer connection: " + var2.getMessage());
                        throw var2;
                    }
                }
            });
        }
    }

    public void close() {
        executor.execute(() -> {
            this.closeInternal();
        });
    }

    public boolean isVideoCallEnabled() {
        return this.videoCallEnabled;
    }

    private void createPeerConnectionFactoryInternal(Context context) {
        this.isError = false;
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
        if (this.videoCallEnabled && this.peerConnectionParameters.videoCodec != null) {
            String videoCodec = this.peerConnectionParameters.videoCodec;
            byte var4 = -1;
            switch(videoCodec) {
                case VIDEO_CODEC_H264_HIGH:
                    fieldTrials = fieldTrials + "WebRTC-H264HighProfile/Enabled/";
                    this.preferredVideoCodec = "H264";
                    break;
                case VIDEO_CODEC_H264_BASELINE:
                    this.preferredVideoCodec = "H264";
                    break;
                case VIDEO_CODEC_VP8:
                    this.preferredVideoCodec = "VP8";
                    break;
                case VIDEO_CODEC_VP9:
                    this.preferredVideoCodec = "VP9";
                    break;
                default:
                    this.preferredVideoCodec = "VP8";
                    break;
            }
        }

        Log.d("PCRTCClient", "Preferred video codec: " + this.preferredVideoCodec);
        Log.d("PCRTCClient", "Initialize WebRTC. Field trials: " + fieldTrials + " Enable video HW acceleration: " + this.peerConnectionParameters.videoCodecHwAcceleration);
        PeerConnectionFactory.initialize(InitializationOptions.builder(context).setFieldTrials(fieldTrials).setEnableVideoHwAcceleration(this.peerConnectionParameters.videoCodecHwAcceleration).setEnableInternalTracer(true).createInitializationOptions());
        if (this.peerConnectionParameters.tracing) {
            PeerConnectionFactory.startInternalTracingCapture(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "webrtc-trace.txt");
        }

        this.preferIsac = this.peerConnectionParameters.audioCodec != null && this.peerConnectionParameters.audioCodec.equals("ISAC");
        if (!this.peerConnectionParameters.useOpenSLES) {
            Log.d("PCRTCClient", "Disable OpenSL ES audio even if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
        } else {
            Log.d("PCRTCClient", "Allow OpenSL ES audio if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false);
        }

        if (this.peerConnectionParameters.disableBuiltInAEC) {
            Log.d("PCRTCClient", "Disable built-in AEC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
        } else {
            Log.d("PCRTCClient", "Enable built-in AEC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false);
        }

        if (this.peerConnectionParameters.disableBuiltInAGC) {
            Log.d("PCRTCClient", "Disable built-in AGC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
        } else {
            Log.d("PCRTCClient", "Enable built-in AGC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false);
        }

        if (this.peerConnectionParameters.disableBuiltInNS) {
            Log.d("PCRTCClient", "Disable built-in NS even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
        } else {
            Log.d("PCRTCClient", "Enable built-in NS if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false);
        }

        WebRtcAudioRecord.setErrorCallback(new WebRtcAudioRecordErrorCallback() {
            public void onWebRtcAudioRecordInitError(String errorMessage) {
                Log.e("PCRTCClient", "onWebRtcAudioRecordInitError: " + errorMessage);
                PeerConnectionClient.this.reportError(errorMessage);
            }

            public void onWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage) {
                Log.e("PCRTCClient", "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
                PeerConnectionClient.this.reportError(errorMessage);
            }

            public void onWebRtcAudioRecordError(String errorMessage) {
                Log.e("PCRTCClient", "onWebRtcAudioRecordError: " + errorMessage);
                PeerConnectionClient.this.reportError(errorMessage);
            }
        });
        WebRtcAudioTrack.setErrorCallback(new ErrorCallback() {
            public void onWebRtcAudioTrackInitError(String errorMessage) {
                Log.e("PCRTCClient", "onWebRtcAudioTrackInitError: " + errorMessage);
                PeerConnectionClient.this.reportError(errorMessage);
            }

            public void onWebRtcAudioTrackStartError(AudioTrackStartErrorCode errorCode, String errorMessage) {
                Log.e("PCRTCClient", "onWebRtcAudioTrackStartError: " + errorCode + ". " + errorMessage);
                PeerConnectionClient.this.reportError(errorMessage);
            }

            public void onWebRtcAudioTrackError(String errorMessage) {
                Log.e("PCRTCClient", "onWebRtcAudioTrackError: " + errorMessage);
                PeerConnectionClient.this.reportError(errorMessage);
            }
        });
        if (this.options != null) {
            Log.d("PCRTCClient", "Factory networkIgnoreMask option: " + this.options.networkIgnoreMask);
        }

        boolean enableH264HighProfile = "H264 High".equals(this.peerConnectionParameters.videoCodec);
        Object decoderFactory;
        Object encoderFactory;
        if (this.peerConnectionParameters.videoCodecHwAcceleration) {
            encoderFactory = new DefaultVideoEncoderFactory(this.rootEglBase.getEglBaseContext(), true, enableH264HighProfile);
            decoderFactory = new DefaultVideoDecoderFactory(this.rootEglBase.getEglBaseContext());
        } else {
            encoderFactory = new SoftwareVideoEncoderFactory();
            decoderFactory = new SoftwareVideoDecoderFactory();
        }

        this.factory = new PeerConnectionFactory(this.options, (VideoEncoderFactory)encoderFactory, (VideoDecoderFactory)decoderFactory);
        Log.d("PCRTCClient", "Peer connection factory created.");
    }

    private void createMediaConstraintsInternal() {
        if (this.videoCapturer == null) {
            Log.w("PCRTCClient", "No camera on device. Switch to audio only call.");
            this.videoCallEnabled = false;
        }

        if (this.videoCallEnabled) {
            this.videoWidth = this.peerConnectionParameters.videoWidth;
            this.videoHeight = this.peerConnectionParameters.videoHeight;
            this.videoFps = this.peerConnectionParameters.videoFps;
            if (this.videoWidth == 0 || this.videoHeight == 0) {
                this.videoWidth = 1280;
                this.videoHeight = 720;
            }

            if (this.videoFps == 0) {
                this.videoFps = 30;
            }

            Logging.d("PCRTCClient", "Capturing format: " + this.videoWidth + "x" + this.videoHeight + "@" + this.videoFps);
        }

        this.audioConstraints = new MediaConstraints();
        if (this.peerConnectionParameters.noAudioProcessing) {
            Log.d("PCRTCClient", "Disabling audio processing");
            this.audioConstraints.mandatory.add(new KeyValuePair("googEchoCancellation", "false"));
            this.audioConstraints.mandatory.add(new KeyValuePair("googAutoGainControl", "false"));
            this.audioConstraints.mandatory.add(new KeyValuePair("googHighpassFilter", "false"));
            this.audioConstraints.mandatory.add(new KeyValuePair("googNoiseSuppression", "false"));
        }

        if (this.peerConnectionParameters.enableLevelControl) {
            Log.d("PCRTCClient", "Enabling level control.");
            this.audioConstraints.mandatory.add(new KeyValuePair("levelControl", "true"));
        }

        this.sdpMediaConstraints = new MediaConstraints();
        this.sdpMediaConstraints.mandatory.add(new KeyValuePair("OfferToReceiveAudio", "true"));
        if (this.videoCallEnabled) {
            this.sdpMediaConstraints.mandatory.add(new KeyValuePair("OfferToReceiveVideo", "true"));
        } else {
            this.sdpMediaConstraints.mandatory.add(new KeyValuePair("OfferToReceiveVideo", "false"));
        }

    }

    private void createPeerConnectionInternal() {
        if (this.factory != null && !this.isError) {
            Log.d("PCRTCClient", "Create peer connection.");
            this.queuedRemoteCandidates = new ArrayList();
            if (this.videoCallEnabled) {
                this.factory.setVideoHwAccelerationOptions(this.rootEglBase.getEglBaseContext(), this.rootEglBase.getEglBaseContext());
            }

            RTCConfiguration rtcConfig = new RTCConfiguration(this.iceServers);
            rtcConfig.tcpCandidatePolicy = TcpCandidatePolicy.DISABLED;
            rtcConfig.bundlePolicy = BundlePolicy.MAXBUNDLE;
            rtcConfig.rtcpMuxPolicy = RtcpMuxPolicy.REQUIRE;
            rtcConfig.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY;
            rtcConfig.keyType = KeyType.ECDSA;
            rtcConfig.enableDtlsSrtp = true;
            this.peerConnection = this.factory.createPeerConnection(rtcConfig, this.pcObserver);
            if (this.dataChannelEnabled) {
                Init init = new Init();
                init.ordered = this.peerConnectionParameters.dataChannelParameters.ordered;
                init.negotiated = this.peerConnectionParameters.dataChannelParameters.negotiated;
                init.maxRetransmits = this.peerConnectionParameters.dataChannelParameters.maxRetransmits;
                init.maxRetransmitTimeMs = this.peerConnectionParameters.dataChannelParameters.maxRetransmitTimeMs;
                init.id = this.peerConnectionParameters.dataChannelParameters.id;
                init.protocol = this.peerConnectionParameters.dataChannelParameters.protocol;
                this.dataChannel = this.peerConnection.createDataChannel("AVEngineKit data", init);
            }

            this.isInitiator = false;
            Logging.enableLogToDebugOutput(Severity.LS_INFO);
            this.mediaStream = this.factory.createLocalMediaStream("ARDAMS");
            if (this.videoCallEnabled) {
                this.mediaStream.addTrack(this.createVideoTrack(this.videoCapturer));
            }

            this.mediaStream.addTrack(this.createAudioTrack());
            this.peerConnection.addStream(this.mediaStream);
            if (this.videoCallEnabled) {
                this.findVideoSender();
            }

            if (this.peerConnectionParameters.aecDump) {
                try {
                    ParcelFileDescriptor aecDumpFileDescriptor = ParcelFileDescriptor.open(new File(Environment.getExternalStorageDirectory().getPath() + File.separator + "Download/audio.aecdump"), 1006632960);
                    this.factory.startAecDump(aecDumpFileDescriptor.getFd(), -1);
                } catch (IOException var3) {
                    Log.e("PCRTCClient", "Can not open aecdump file", var3);
                }
            }

            Log.d("PCRTCClient", "Peer connection created.");
        } else {
            Log.e("PCRTCClient", "Peerconnection factory is not created");
        }
    }

    private void closeInternal() {
        if (this.factory != null && this.peerConnectionParameters.aecDump) {
            this.factory.stopAecDump();
        }

        Log.d("PCRTCClient", "Closing peer connection.");
        this.statsTimer.cancel();
        if (this.dataChannel != null) {
            this.dataChannel.dispose();
            this.dataChannel = null;
        }

        if (this.peerConnection != null) {
            this.peerConnection.dispose();
            this.peerConnection = null;
        }

        Log.d("PCRTCClient", "Closing audio source.");
        if (this.audioSource != null) {
            this.audioSource.dispose();
            this.audioSource = null;
        }

        Log.d("PCRTCClient", "Stopping capture.");
        if (this.videoCapturer != null) {
            this.videoCapturerStopped = true;
            this.videoCapturer = null;
        }

        Log.d("PCRTCClient", "Closing video source.");
        if (this.videoSource != null) {
            this.videoSource.dispose();
            this.videoSource = null;
        }

        Log.d("PCRTCClient", "Closing peer connection factory.");
        if (this.factory != null) {
            this.factory.dispose();
            this.factory = null;
        }

        this.options = null;
        this.rootEglBase.release();
        Log.d("PCRTCClient", "Closing peer connection done.");
        this.events.onPeerConnectionClosed();
        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
        this.events = null;
    }

    public boolean isHDVideo() {
        return this.videoCallEnabled && this.videoWidth * this.videoHeight >= 921600;
    }

    public org.webrtc.EglBase.Context getRenderContext() {
        return this.rootEglBase.getEglBaseContext();
    }

    private void getStats() {
        if (this.peerConnection != null && !this.isError) {
            boolean success = this.peerConnection.getStats(new StatsObserver() {
                public void onComplete(StatsReport[] reports) {
                    PeerConnectionClient.this.events.onPeerConnectionStatsReady(reports);
                }
            }, (MediaStreamTrack)null);
            if (!success) {
                Log.e("PCRTCClient", "getStats() returns false!");
            }

        }
    }

    public void enableStatsEvents(boolean enable, int periodMs) {
        if (enable) {
            try {
                this.statsTimer.schedule(new TimerTask() {
                    public void run() {
                        PeerConnectionClient.executor.execute(new Runnable() {
                            public void run() {
                                PeerConnectionClient.this.getStats();
                            }
                        });
                    }
                }, 0L, (long)periodMs);
            } catch (Exception var4) {
                Log.e("PCRTCClient", "Can not schedule statistics timer", var4);
            }
        } else {
            this.statsTimer.cancel();
        }

    }

    public void setAudioEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            public void run() {
                PeerConnectionClient.this.enableAudio = enable;
                if (PeerConnectionClient.this.localAudioTrack != null) {
                    PeerConnectionClient.this.localAudioTrack.setEnabled(PeerConnectionClient.this.enableAudio);
                }

            }
        });
    }

    public void setVideoEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            public void run() {
                PeerConnectionClient.this.renderVideo = enable;
                if (PeerConnectionClient.this.localVideoTrack != null) {
                    PeerConnectionClient.this.localVideoTrack.setEnabled(PeerConnectionClient.this.renderVideo);
                }

                if (PeerConnectionClient.this.remoteVideoTrack != null) {
                    PeerConnectionClient.this.remoteVideoTrack.setEnabled(PeerConnectionClient.this.renderVideo);
                }

            }
        });
    }

    public void createOffer() {
        executor.execute(() -> {
            if (this.peerConnection != null && !this.isError) {
                Log.d("PCRTCClient", "PC Create OFFER");
                this.isInitiator = true;
                this.peerConnection.createOffer(this.sdpObserver, this.sdpMediaConstraints);
            }

        });
    }

    public void createAnswer() {
        executor.execute(() -> {
            if (this.peerConnection != null && !this.isError) {
                Log.d("PCRTCClient", "PC create ANSWER");
                this.isInitiator = false;
                this.peerConnection.createAnswer(this.sdpObserver, this.sdpMediaConstraints);
            }

        });
    }

    public void addRemoteIceCandidate(IceCandidate candidate) {
        executor.execute(() -> {
            if (this.peerConnection != null && !this.isError) {
                if (this.queuedRemoteCandidates != null) {
                    this.queuedRemoteCandidates.add(candidate);
                } else {
                    this.peerConnection.addIceCandidate(candidate);
                }
            }

        });
    }

    public void removeRemoteIceCandidates(IceCandidate[] candidates) {
        executor.execute(() -> {
            if (this.peerConnection != null && !this.isError) {
                this.drainCandidates();
                this.peerConnection.removeIceCandidates(candidates);
            }
        });
    }

    public void setRemoteDescription(SessionDescription sdp) {
        executor.execute(() -> {
            if (this.peerConnection != null && !this.isError) {
                String sdpDescription = sdp.description;
                if (this.preferIsac) {
                    sdpDescription = preferCodec(sdpDescription, "ISAC", true);
                }

                if (this.videoCallEnabled) {
                    sdpDescription = preferCodec(sdpDescription, this.preferredVideoCodec, false);
                }

                if (this.peerConnectionParameters.audioStartBitrate > 0) {
                    sdpDescription = setStartBitrate("opus", false, sdpDescription, this.peerConnectionParameters.audioStartBitrate);
                }

                Log.d("PCRTCClient", "Set remote SDP.");
                SessionDescription sdpRemote = new SessionDescription(sdp.type, sdpDescription);
                this.peerConnection.setRemoteDescription(this.sdpObserver, sdpRemote);
            }
        });
    }

    public void stopVideoSource() {
        executor.execute(() -> {
            if (this.videoCapturer != null && !this.videoCapturerStopped) {
                Log.d("PCRTCClient", "Stop video source.");

                try {
                    this.videoCapturer.stopCapture();
                } catch (InterruptedException var2) {
                }

                this.videoCapturerStopped = true;
            }

        });
    }

    public void startVideoSource() {
        executor.execute(() -> {
            if (this.videoCapturer != null && this.videoCapturerStopped) {
                Log.d("PCRTCClient", "Restart video source.");
                this.videoCapturer.startCapture(this.videoWidth, this.videoHeight, this.videoFps);
                this.videoCapturerStopped = false;
            }

        });
    }

    public void setVideoMaxBitrate(Integer maxBitrateKbps) {
        executor.execute(() -> {
            if (this.peerConnection != null && this.localVideoSender != null && !this.isError) {
                Log.d("PCRTCClient", "Requested max video bitrate: " + maxBitrateKbps);
                if (this.localVideoSender == null) {
                    Log.w("PCRTCClient", "Sender is not ready.");
                } else {
                    RtpParameters parameters = this.localVideoSender.getParameters();
                    if (parameters.encodings.size() == 0) {
                        Log.w("PCRTCClient", "RtpParameters are not ready.");
                    } else {
                        Encoding encoding;
                        for(Iterator var3 = parameters.encodings.iterator(); var3.hasNext(); encoding.maxBitrateBps = maxBitrateKbps == null ? null : maxBitrateKbps * 1000) {
                            encoding = (Encoding)var3.next();
                        }

                        if (!this.localVideoSender.setParameters(parameters)) {
                            Log.e("PCRTCClient", "RtpSender.setParameters failed.");
                        }

                        Log.d("PCRTCClient", "Configured max video bitrate to: " + maxBitrateKbps);
                    }
                }
            }
        });
    }

    private void reportError(String errorMessage) {
        Log.e("PCRTCClient", "Peerconnection error: " + errorMessage);
        executor.execute(() -> {
            if (!this.isError) {
                this.events.onPeerConnectionError(errorMessage);
                this.isError = true;
            }

        });
    }

    private AudioTrack createAudioTrack() {
        this.audioSource = this.factory.createAudioSource(this.audioConstraints);
        this.localAudioTrack = this.factory.createAudioTrack("ARDAMSa0", this.audioSource);
        this.localAudioTrack.setEnabled(this.enableAudio);
        return this.localAudioTrack;
    }

    public VideoTrack createVideoTrack(VideoCapturer capturer) {
        this.videoCapturer = capturer;
        if (this.localVideoTrack != null) {
            return this.localVideoTrack;
        } else {
            this.videoSource = this.factory.createVideoSource(capturer);
            capturer.startCapture(this.videoWidth, this.videoHeight, this.videoFps);
            this.localVideoTrack = this.factory.createVideoTrack("ARDAMSv0", this.videoSource);
            this.localVideoTrack.setEnabled(this.renderVideo);
            this.events.onCreateLocalVideoTrack(this.localVideoTrack);
            return this.localVideoTrack;
        }
    }

    private void findVideoSender() {
        Iterator var1 = this.peerConnection.getSenders().iterator();

        while(var1.hasNext()) {
            RtpSender sender = (RtpSender)var1.next();
            if (sender.track() != null) {
                String trackType = sender.track().kind();
                if (trackType.equals("video")) {
                    Log.d("PCRTCClient", "Found video sender.");
                    this.localVideoSender = sender;
                }
            }
        }

    }

    private static String setStartBitrate(String codec, boolean isVideoCodec, String sdpDescription, int bitrateKbps) {
        String[] lines = sdpDescription.split("\r\n");
        int rtpmapLineIndex = -1;
        boolean sdpFormatUpdated = false;
        String codecRtpMap = null;
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);

        Matcher codecMatcher;
        for(int i = 0; i < lines.length; ++i) {
            codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
                rtpmapLineIndex = i;
                break;
            }
        }

        if (codecRtpMap == null) {
            Log.w("PCRTCClient", "No rtpmap for " + codec + " codec");
            return sdpDescription;
        } else {
            Log.d("PCRTCClient", "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex]);
            regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$";
            codecPattern = Pattern.compile(regex);

            for(int i = 0; i < lines.length; ++i) {
                codecMatcher = codecPattern.matcher(lines[i]);
                if (codecMatcher.matches()) {
                    Log.d("PCRTCClient", "Found " + codec + " " + lines[i]);
                    if (isVideoCodec) {
                        lines[i] = lines[i] + "; x-google-start-bitrate=" + bitrateKbps;
                    } else {
                        lines[i] = lines[i] + "; maxaveragebitrate=" + bitrateKbps * 1000;
                    }

                    Log.d("PCRTCClient", "Update remote SDP line: " + lines[i]);
                    sdpFormatUpdated = true;
                    break;
                }
            }

            StringBuilder newSdpDescription = new StringBuilder();

            for(int i = 0; i < lines.length; ++i) {
                newSdpDescription.append(lines[i]).append("\r\n");
                if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                    String bitrateSet;
                    if (isVideoCodec) {
                        bitrateSet = "a=fmtp:" + codecRtpMap + " " + "x-google-start-bitrate" + "=" + bitrateKbps;
                    } else {
                        bitrateSet = "a=fmtp:" + codecRtpMap + " " + "maxaveragebitrate" + "=" + bitrateKbps * 1000;
                    }

                    Log.d("PCRTCClient", "Add remote SDP line: " + bitrateSet);
                    newSdpDescription.append(bitrateSet).append("\r\n");
                }
            }

            return newSdpDescription.toString();
        }
    }

    private static int findMediaDescriptionLine(boolean isAudio, String[] sdpLines) {
        String mediaDescription = isAudio ? "m=audio " : "m=video ";

        for(int i = 0; i < sdpLines.length; ++i) {
            if (sdpLines[i].startsWith(mediaDescription)) {
                return i;
            }
        }

        return -1;
    }

    private static String joinString(Iterable<? extends CharSequence> s, String delimiter, boolean delimiterAtEnd) {
        Iterator<? extends CharSequence> iter = s.iterator();
        if (!iter.hasNext()) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder((CharSequence)iter.next());

            while(iter.hasNext()) {
                buffer.append(delimiter).append((CharSequence)iter.next());
            }

            if (delimiterAtEnd) {
                buffer.append(delimiter);
            }

            return buffer.toString();
        }
    }

    private static String movePayloadTypesToFront(List<String> preferredPayloadTypes, String mLine) {
        List<String> origLineParts = Arrays.asList(mLine.split(" "));
        if (origLineParts.size() <= 3) {
            Log.e("PCRTCClient", "Wrong SDP media description format: " + mLine);
            return null;
        } else {
            List<String> header = origLineParts.subList(0, 3);
            List<String> unpreferredPayloadTypes = new ArrayList(origLineParts.subList(3, origLineParts.size()));
            unpreferredPayloadTypes.removeAll(preferredPayloadTypes);
            List<String> newLineParts = new ArrayList();
            newLineParts.addAll(header);
            newLineParts.addAll(preferredPayloadTypes);
            newLineParts.addAll(unpreferredPayloadTypes);
            return joinString(newLineParts, " ", false);
        }
    }

    private static String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = findMediaDescriptionLine(isAudio, lines);
        if (mLineIndex == -1) {
            Log.w("PCRTCClient", "No mediaDescription line, so can't prefer " + codec);
            return sdpDescription;
        } else {
            List<String> codecPayloadTypes = new ArrayList();
            Pattern codecPattern = Pattern.compile("^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$");
            String[] var7 = lines;
            int var8 = lines.length;

            for(int var9 = 0; var9 < var8; ++var9) {
                String line = var7[var9];
                Matcher codecMatcher = codecPattern.matcher(line);
                if (codecMatcher.matches()) {
                    codecPayloadTypes.add(codecMatcher.group(1));
                }
            }

            if (codecPayloadTypes.isEmpty()) {
                Log.w("PCRTCClient", "No payload types with name " + codec);
                return sdpDescription;
            } else {
                String newMLine = movePayloadTypesToFront(codecPayloadTypes, lines[mLineIndex]);
                if (newMLine == null) {
                    return sdpDescription;
                } else {
                    Log.d("PCRTCClient", "Change media description from: " + lines[mLineIndex] + " to " + newMLine);
                    lines[mLineIndex] = newMLine;
                    return joinString(Arrays.asList(lines), "\r\n", true);
                }
            }
        }
    }

    private void drainCandidates() {
        if (this.queuedRemoteCandidates != null) {
            Log.d("PCRTCClient", "Add " + this.queuedRemoteCandidates.size() + " remote candidates");
            Iterator var1 = this.queuedRemoteCandidates.iterator();

            while(var1.hasNext()) {
                IceCandidate candidate = (IceCandidate)var1.next();
                this.peerConnection.addIceCandidate(candidate);
            }

            this.queuedRemoteCandidates = null;
        }

    }

    private void switchCameraInternal() {
        if (this.videoCapturer instanceof CameraVideoCapturer) {
            if (!this.videoCallEnabled || this.isError) {
                Log.e("PCRTCClient", "Failed to switch camera. Video: " + this.videoCallEnabled + ". Error : " + this.isError);
                return;
            }

            Log.d("PCRTCClient", "Switch camera");
            CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer)this.videoCapturer;
            cameraVideoCapturer.switchCamera((CameraSwitchHandler)null);
        } else {
            Log.d("PCRTCClient", "Will not switch camera, video caputurer is not a camera");
        }

    }

    public void switchCamera() {
        executor.execute(() -> {
            this.switchCameraInternal();
        });
    }

    public void changeCaptureFormat(int width, int height, int framerate) {
        executor.execute(() -> {
            this.changeCaptureFormatInternal(width, height, framerate);
        });
    }

    private void changeCaptureFormatInternal(int width, int height, int framerate) {
        if (this.videoCallEnabled && !this.isError && this.videoCapturer != null) {
            Log.d("PCRTCClient", "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
            this.videoSource.adaptOutputFormat(width, height, framerate);
        } else {
            Log.e("PCRTCClient", "Failed to change capture format. Video: " + this.videoCallEnabled + ". Error : " + this.isError);
        }
    }

    private class SDPObserver implements SdpObserver {
        private SDPObserver() {
        }

        public void onCreateSuccess(SessionDescription origSdp) {
            if (PeerConnectionClient.this.localSdp != null) {
                PeerConnectionClient.this.reportError("Multiple SDP create.");
            } else {
                String sdpDescription = origSdp.description;
                if (PeerConnectionClient.this.preferIsac) {
                    sdpDescription = PeerConnectionClient.preferCodec(sdpDescription, "ISAC", true);
                }

                if (PeerConnectionClient.this.videoCallEnabled) {
                    sdpDescription = PeerConnectionClient.preferCodec(sdpDescription, PeerConnectionClient.this.preferredVideoCodec, false);
                }

                final SessionDescription sdp = new SessionDescription(origSdp.type, sdpDescription);
                PeerConnectionClient.this.localSdp = sdp;
                PeerConnectionClient.executor.execute(new Runnable() {
                    public void run() {
                        if (PeerConnectionClient.this.peerConnection != null && !PeerConnectionClient.this.isError) {
                            Log.d("PCRTCClient", "Set local SDP from " + sdp.type);
                            PeerConnectionClient.this.peerConnection.setLocalDescription(PeerConnectionClient.this.sdpObserver, sdp);
                        }

                    }
                });
            }
        }

        public void onSetSuccess() {
            PeerConnectionClient.executor.execute(new Runnable() {
                public void run() {
                    if (PeerConnectionClient.this.peerConnection != null && !PeerConnectionClient.this.isError) {
                        if (PeerConnectionClient.this.isInitiator) {
                            if (PeerConnectionClient.this.peerConnection.getRemoteDescription() == null) {
                                Log.d("PCRTCClient", "Local SDP set succesfully");
                                PeerConnectionClient.this.events.onLocalDescription(PeerConnectionClient.this.localSdp);
                            } else {
                                Log.d("PCRTCClient", "Remote SDP set succesfully");
                                PeerConnectionClient.this.drainCandidates();
                            }
                        } else if (PeerConnectionClient.this.peerConnection.getLocalDescription() != null) {
                            Log.d("PCRTCClient", "Local SDP set succesfully");
                            PeerConnectionClient.this.events.onLocalDescription(PeerConnectionClient.this.localSdp);
                            PeerConnectionClient.this.drainCandidates();
                        } else {
                            Log.d("PCRTCClient", "Remote SDP set succesfully");
                        }

                    }
                }
            });
        }

        public void onCreateFailure(String error) {
            PeerConnectionClient.this.reportError("createSDP error: " + error);
        }

        public void onSetFailure(String error) {
            PeerConnectionClient.this.reportError("setSDP error: " + error);
        }
    }

    private class PCObserver implements Observer {
        private PCObserver() {
        }

        public void onIceCandidate(IceCandidate candidate) {
            PeerConnectionClient.executor.execute(() -> {
                PeerConnectionClient.this.events.onIceCandidate(candidate);
            });
        }

        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            PeerConnectionClient.executor.execute(() -> {
                PeerConnectionClient.this.events.onIceCandidatesRemoved(candidates);
            });
        }

        public void onSignalingChange(SignalingState newState) {
            Log.d("PCRTCClient", "SignalingState: " + newState);
        }

        public void onIceConnectionChange(IceConnectionState newState) {
            PeerConnectionClient.executor.execute(() -> {
                Log.d("PCRTCClient", "IceConnectionState: " + newState);
                if (newState == IceConnectionState.CONNECTED) {
                    if (PeerConnectionClient.this.events != null) {
                        PeerConnectionClient.this.events.onIceConnected();
                    }
                } else if (newState == IceConnectionState.DISCONNECTED) {
                    if (PeerConnectionClient.this.events != null) {
                        PeerConnectionClient.this.events.onIceDisconnected();
                    }
                } else if (newState == IceConnectionState.FAILED) {
                    PeerConnectionClient.this.reportError("ICE connection failed.");
                }

            });
        }

        public void onIceGatheringChange(IceGatheringState newState) {
            Log.d("PCRTCClient", "IceGatheringState: " + newState);
        }

        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d("PCRTCClient", "IceConnectionReceiving changed to " + receiving);
        }

        public void onAddStream(MediaStream stream) {
            PeerConnectionClient.executor.execute(() -> {
                if (PeerConnectionClient.this.peerConnection != null && !PeerConnectionClient.this.isError) {
                    if (stream.audioTracks.size() <= 1 && stream.videoTracks.size() <= 1) {
                        if (stream.videoTracks.size() == 1) {
                            PeerConnectionClient.this.remoteVideoTrack = (VideoTrack)stream.videoTracks.get(0);
                            PeerConnectionClient.this.remoteVideoTrack.setEnabled(PeerConnectionClient.this.renderVideo);
                            PeerConnectionClient.this.events.onAddRemoteVideoTrack(PeerConnectionClient.this.remoteVideoTrack);
                        }

                    } else {
                        PeerConnectionClient.this.reportError("Weird-looking stream: " + stream);
                    }
                }
            });
        }

        public void onRemoveStream(MediaStream stream) {
            PeerConnectionClient.executor.execute(() -> {
                if (PeerConnectionClient.this.events != null) {
                    PeerConnectionClient.this.events.onRemoveRemoteVideoTrack(PeerConnectionClient.this.remoteVideoTrack);
                }

                PeerConnectionClient.this.remoteVideoTrack = null;
            });
        }

        public void onDataChannel(final DataChannel dc) {
            Log.d("PCRTCClient", "New Data channel " + dc.label());
            if (PeerConnectionClient.this.dataChannelEnabled) {
                dc.registerObserver(new org.webrtc.DataChannel.Observer() {
                    public void onBufferedAmountChange(long previousAmount) {
                        Log.d("PCRTCClient", "Data channel buffered amount changed: " + dc.label() + ": " + dc.state());
                    }

                    public void onStateChange() {
                        Log.d("PCRTCClient", "Data channel state changed: " + dc.label() + ": " + dc.state());
                    }

                    public void onMessage(Buffer buffer) {
                        if (buffer.binary) {
                            Log.d("PCRTCClient", "Received binary msg over " + dc);
                        } else {
                            ByteBuffer data = buffer.data;
                            byte[] bytes = new byte[data.capacity()];
                            data.get(bytes);
                            String strData = new String(bytes, Charset.forName("UTF-8"));
                            Log.d("PCRTCClient", "Got msg: " + strData + " over " + dc);
                        }
                    }
                });
            }
        }

        public void onRenegotiationNeeded() {
        }

        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
        }
    }

    public interface PeerConnectionEvents {
        void onLocalDescription(SessionDescription var1);

        void onIceCandidate(IceCandidate var1);

        void onIceCandidatesRemoved(IceCandidate[] var1);

        void onIceConnected();

        void onIceDisconnected();

        void onAddRemoteVideoTrack(VideoTrack var1);

        void onRemoveRemoteVideoTrack(VideoTrack var1);

        void onCreateLocalVideoTrack(VideoTrack var1);

        void onRemoveLocalVideoTrack();

        void onPeerConnectionClosed();

        void onPeerConnectionStatsReady(StatsReport[] var1);

        void onPeerConnectionError(String var1);
    }

    private static class PeerConnectionParameters {
        public final boolean videoCallEnabled;
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
        private final PeerConnectionClient.DataChannelParameters dataChannelParameters;

        public PeerConnectionParameters(boolean videoCallEnabled, boolean tracing, int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate, String videoCodec, boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled, int audioStartBitrate, String audioCodec, boolean noAudioProcessing, boolean aecDump, boolean useOpenSLES, boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS, boolean enableLevelControl, boolean disableWebRtcAGCAndHPF) {
            this(videoCallEnabled, tracing, videoWidth, videoHeight, videoFps, videoMaxBitrate, videoCodec, videoCodecHwAcceleration, videoFlexfecEnabled, audioStartBitrate, audioCodec, noAudioProcessing, aecDump, useOpenSLES, disableBuiltInAEC, disableBuiltInAGC, disableBuiltInNS, enableLevelControl, disableWebRtcAGCAndHPF, (PeerConnectionClient.DataChannelParameters)null);
        }

        public PeerConnectionParameters(boolean videoCallEnabled, boolean tracing, int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate, String videoCodec, boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled, int audioStartBitrate, String audioCodec, boolean noAudioProcessing, boolean aecDump, boolean useOpenSLES, boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS, boolean enableLevelControl, boolean disableWebRtcAGCAndHPF, PeerConnectionClient.DataChannelParameters dataChannelParameters) {
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
            this.dataChannelParameters = dataChannelParameters;
        }
    }

    private static class DataChannelParameters {
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
}
