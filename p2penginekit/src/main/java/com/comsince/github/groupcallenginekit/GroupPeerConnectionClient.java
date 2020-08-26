package com.comsince.github.groupcallenginekit;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler;
import org.webrtc.DataChannel;
import org.webrtc.DataChannel.Buffer;
import org.webrtc.DataChannel.Init;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.Logging.Severity;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaConstraints.KeyValuePair;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
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
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.Options;
import org.webrtc.RtpParameters;
import org.webrtc.RtpParameters.Encoding;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
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

public class GroupPeerConnectionClient {
    public static final String VIDEO_TRACK_TYPE = "video";
    private static final String TAG = "GroupWebRtcPClient";
    private static final int BPS_IN_KBPS = 1000;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private GroupPeerConnectionClient.PCObserver pcObserver = new GroupPeerConnectionClient.PCObserver();
    private GroupPeerConnectionClient.SDPObserver sdpObserver = new GroupPeerConnectionClient.SDPObserver();
    private EglBase rootEglBase;
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
    private DefaultPeerConnectionFactory.PeerConnectionParameters peerConnectionParameters;
    private List<IceCandidate> queuedRemoteCandidates;
    private GroupPeerConnectionClient.PeerConnectionEvents events;
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
    private SurfaceTextureHelper surfaceTextureHelper;
    private Context context;

    public GroupPeerConnectionClient(Context context){
        this.context = context;
    }

    public void setPeerConnectionFactoryOptions(Options options) {
        this.options = options;
    }

    public void init(DefaultPeerConnectionFactory defaultPeerConnectionFactory,GroupPeerConnectionClient.PeerConnectionEvents events,boolean audioOnly,StreamMode streamMode){
        this.peerConnectionParameters = new DefaultPeerConnectionFactory.PeerConnectionParameters(defaultPeerConnectionFactory.peerConnectionParameters.videoCallEnabled,
                defaultPeerConnectionFactory.peerConnectionParameters.tracing,defaultPeerConnectionFactory.peerConnectionParameters.videoWidth,
                defaultPeerConnectionFactory.peerConnectionParameters.videoHeight,defaultPeerConnectionFactory.peerConnectionParameters.videoFps,
                defaultPeerConnectionFactory.peerConnectionParameters.videoMaxBitrate,defaultPeerConnectionFactory.peerConnectionParameters.videoCodec,
                defaultPeerConnectionFactory.peerConnectionParameters.videoCodecHwAcceleration,defaultPeerConnectionFactory.peerConnectionParameters.videoFlexfecEnabled,
                defaultPeerConnectionFactory.peerConnectionParameters.audioStartBitrate,defaultPeerConnectionFactory.peerConnectionParameters.audioCodec,
                defaultPeerConnectionFactory.peerConnectionParameters.noAudioProcessing,defaultPeerConnectionFactory.peerConnectionParameters.aecDump,
                defaultPeerConnectionFactory.peerConnectionParameters.useOpenSLES,defaultPeerConnectionFactory.peerConnectionParameters.disableBuiltInAEC,
                defaultPeerConnectionFactory.peerConnectionParameters.disableBuiltInAGC,defaultPeerConnectionFactory.peerConnectionParameters.disableBuiltInNS,
                defaultPeerConnectionFactory.peerConnectionParameters.enableLevelControl,defaultPeerConnectionFactory.peerConnectionParameters.disableWebRtcAGCAndHPF,
                defaultPeerConnectionFactory.peerConnectionParameters.streamMode);
        this.peerConnectionParameters.streamMode = streamMode;
        this.peerConnectionParameters.videoCallEnabled = !audioOnly;
        this.rootEglBase = defaultPeerConnectionFactory.rootEglBase;
        this.factory = defaultPeerConnectionFactory.defaultPeerConnectionFactory;
        this.preferredVideoCodec = defaultPeerConnectionFactory.preferredVideoCodec;
        this.preferIsac = this.peerConnectionParameters.audioCodec != null && this.peerConnectionParameters.audioCodec.equals("ISAC");
        this.events = events;
        this.videoCallEnabled = this.peerConnectionParameters.videoCallEnabled;
        this.dataChannelEnabled = this.peerConnectionParameters.dataChannelParameters != null;
        this.renderVideo = true;
        this.enableAudio = true;
        this.isError = false;
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
                        Log.i(TAG,"createPeerConnection");
                        GroupPeerConnectionClient.this.createMediaConstraintsInternal();
                        GroupPeerConnectionClient.this.createPeerConnectionInternal();
                    } catch (Exception var2) {
                        GroupPeerConnectionClient.this.reportError("Failed to create peer connection: " + var2.getMessage());
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
        this.sdpMediaConstraints.mandatory.add(new KeyValuePair("OfferToReceiveAudio", peerConnectionParameters.streamMode == StreamMode.SEND_ONLY ? "false" : "true"));
        if (this.videoCallEnabled || peerConnectionParameters.streamMode == StreamMode.RECV_ONLY) {
            this.sdpMediaConstraints.mandatory.add(new KeyValuePair("OfferToReceiveVideo", peerConnectionParameters.streamMode == StreamMode.SEND_ONLY ? "false" : "true"));
        } else {
            this.sdpMediaConstraints.mandatory.add(new KeyValuePair("OfferToReceiveVideo", "false"));
        }

    }

    private void createPeerConnectionInternal() {
        if (this.factory != null && !this.isError) {
            Log.d("PCRTCClient", "Create peer connection.");
            this.queuedRemoteCandidates = new ArrayList();
            if (this.videoCallEnabled) {
               // this.factory.setVideoHwAccelerationOptions(this.rootEglBase.getEglBaseContext(), this.rootEglBase.getEglBaseContext());
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

            if (peerConnectionParameters.streamMode != StreamMode.RECV_ONLY){
                this.mediaStream = this.factory.createLocalMediaStream("ARDAMS");
                if (this.videoCallEnabled) {
                    this.mediaStream.addTrack(this.createVideoTrack(this.videoCapturer));
                }

                this.mediaStream.addTrack(this.createAudioTrack());
                this.peerConnection.addStream(this.mediaStream);
                if (this.videoCallEnabled) {
                    this.findVideoSender();
                }
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

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        this.options = null;
        this.rootEglBase = null;
        Log.d("PCRTCClient", "Closing peer connection done.");
        this.events.onPeerConnectionClosed();
        this.events = null;
    }

    public boolean isHDVideo() {
        return this.videoCallEnabled && this.videoWidth * this.videoHeight >= 921600;
    }

    public EglBase.Context getRenderContext() {
        return this.rootEglBase.getEglBaseContext();
    }

    private void getStats() {
        if (this.peerConnection != null && !this.isError) {
            boolean success = this.peerConnection.getStats(new StatsObserver() {
                public void onComplete(StatsReport[] reports) {
                    GroupPeerConnectionClient.this.events.onPeerConnectionStatsReady(reports);
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
                        executor.execute(new Runnable() {
                            public void run() {
                                GroupPeerConnectionClient.this.getStats();
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
                GroupPeerConnectionClient.this.enableAudio = enable;
                if (GroupPeerConnectionClient.this.localAudioTrack != null) {
                    GroupPeerConnectionClient.this.localAudioTrack.setEnabled(GroupPeerConnectionClient.this.enableAudio);
                }

            }
        });
    }

    public void setVideoEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            public void run() {
                GroupPeerConnectionClient.this.renderVideo = enable;
                if (GroupPeerConnectionClient.this.localVideoTrack != null) {
                    GroupPeerConnectionClient.this.localVideoTrack.setEnabled(GroupPeerConnectionClient.this.renderVideo);
                }

                if (GroupPeerConnectionClient.this.remoteVideoTrack != null) {
                    GroupPeerConnectionClient.this.remoteVideoTrack.setEnabled(GroupPeerConnectionClient.this.renderVideo);
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
                        for (Encoding encoding : parameters.encodings) {
                            // Null value means no limit.
                            encoding.maxBitrateBps = maxBitrateKbps == null ? null : maxBitrateKbps * BPS_IN_KBPS;
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

    public VideoTrack createVideoTrack(VideoCapturer capture) {
        this.videoCapturer = capture;
        if (this.localVideoTrack == null) {
            this.videoSource = this.factory.createVideoSource(capture.isScreencast());
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceTextureHelper,context,videoSource.getCapturerObserver());
            capture.startCapture(this.videoWidth, this.videoHeight, this.videoFps);
            this.localVideoTrack = this.factory.createVideoTrack("ARDAMSv0", this.videoSource);
            this.localVideoTrack.setEnabled(this.renderVideo);
            this.events.onCreateLocalVideoTrack(this.localVideoTrack);
        }
        return this.localVideoTrack;
    }

    private void findVideoSender() {
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() != null) {
                String trackType = sender.track().kind();
                if (trackType.equals(VIDEO_TRACK_TYPE)) {
                    Log.d(TAG, "Found video sender.");
                    localVideoSender = sender;
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

        for(int i = 0; i < lines.length; ++i) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
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
                Matcher codecMatcher = codecPattern.matcher(lines[i]);
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

            for (String line : lines) {
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
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                peerConnection.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
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

        public void onCreateSuccess(SessionDescription origSdp) {
            if (GroupPeerConnectionClient.this.localSdp != null) {
                GroupPeerConnectionClient.this.reportError("Multiple SDP create.");
            } else {
                String sdpDescription = origSdp.description;
                if (GroupPeerConnectionClient.this.preferIsac) {
                    sdpDescription = GroupPeerConnectionClient.preferCodec(sdpDescription, "ISAC", true);
                }

                if (GroupPeerConnectionClient.this.videoCallEnabled) {
                    sdpDescription = GroupPeerConnectionClient.preferCodec(sdpDescription, GroupPeerConnectionClient.this.preferredVideoCodec, false);
                }

                final SessionDescription sdp = new SessionDescription(origSdp.type, sdpDescription);
                GroupPeerConnectionClient.this.localSdp = sdp;
                executor.execute(new Runnable() {
                    public void run() {
                        if (GroupPeerConnectionClient.this.peerConnection != null && !GroupPeerConnectionClient.this.isError) {
                            Log.d("PCRTCClient", "Set local SDP from " + sdp.type);
                            GroupPeerConnectionClient.this.peerConnection.setLocalDescription(GroupPeerConnectionClient.this.sdpObserver, sdp);
                        }

                    }
                });
            }
        }

        public void onSetSuccess() {
            executor.execute(new Runnable() {
                public void run() {
                    if (GroupPeerConnectionClient.this.peerConnection != null && !GroupPeerConnectionClient.this.isError) {
                        if (GroupPeerConnectionClient.this.isInitiator) {
                            if (GroupPeerConnectionClient.this.peerConnection.getRemoteDescription() == null) {
                                Log.d("PCRTCClient", "Local SDP set succesfully");
                                GroupPeerConnectionClient.this.events.onLocalDescription(GroupPeerConnectionClient.this.localSdp);
                            } else {
                                Log.d("PCRTCClient", "Remote SDP set succesfully");
                                GroupPeerConnectionClient.this.drainCandidates();
                            }
                        } else if (GroupPeerConnectionClient.this.peerConnection.getLocalDescription() != null) {
                            Log.d("PCRTCClient", "Local SDP set succesfully");
                            GroupPeerConnectionClient.this.events.onLocalDescription(GroupPeerConnectionClient.this.localSdp);
                            GroupPeerConnectionClient.this.drainCandidates();
                        } else {
                            Log.d("PCRTCClient", "Remote SDP set succesfully");
                        }

                    }
                }
            });
        }

        public void onCreateFailure(String error) {
            GroupPeerConnectionClient.this.reportError("createSDP error: " + error);
        }

        public void onSetFailure(String error) {
            GroupPeerConnectionClient.this.reportError("setSDP error: " + error);
        }
    }

    private class PCObserver implements Observer {

        public void onIceCandidate(IceCandidate candidate) {
            executor.execute(() -> {
                GroupPeerConnectionClient.this.events.onIceCandidate(candidate);
            });
        }

        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            executor.execute(() -> {
                GroupPeerConnectionClient.this.events.onIceCandidatesRemoved(candidates);
            });
        }

        public void onSignalingChange(SignalingState newState) {
            Log.d("PCRTCClient", "SignalingState: " + newState);
        }

        public void onIceConnectionChange(IceConnectionState newState) {
            executor.execute(() -> {
                Log.d("PCRTCClient", "IceConnectionState: " + newState);
                if (newState == IceConnectionState.CONNECTED) {
                    if (GroupPeerConnectionClient.this.events != null) {
                        GroupPeerConnectionClient.this.events.onIceConnected();
                    }
                } else if (newState == IceConnectionState.DISCONNECTED) {
                    if (GroupPeerConnectionClient.this.events != null) {
                        GroupPeerConnectionClient.this.events.onIceDisconnected();
                    }
                } else if (newState == IceConnectionState.FAILED) {
                    GroupPeerConnectionClient.this.reportError("ICE connection failed.");
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
            executor.execute(() -> {
                if (GroupPeerConnectionClient.this.peerConnection != null && !GroupPeerConnectionClient.this.isError) {
                    if (stream.audioTracks.size() <= 1 && stream.videoTracks.size() <= 1) {
                        if (stream.videoTracks.size() == 1) {
                            GroupPeerConnectionClient.this.remoteVideoTrack = (VideoTrack)stream.videoTracks.get(0);
                            GroupPeerConnectionClient.this.remoteVideoTrack.setEnabled(GroupPeerConnectionClient.this.renderVideo);
                            GroupPeerConnectionClient.this.events.onAddRemoteVideoTrack(GroupPeerConnectionClient.this.remoteVideoTrack);
                        }

                    } else {
                        GroupPeerConnectionClient.this.reportError("Weird-looking stream: " + stream);
                    }
                }
            });
        }

        public void onRemoveStream(MediaStream stream) {
            executor.execute(() -> {
                if (GroupPeerConnectionClient.this.events != null) {
                    GroupPeerConnectionClient.this.events.onRemoveRemoteVideoTrack(GroupPeerConnectionClient.this.remoteVideoTrack);
                }

                GroupPeerConnectionClient.this.remoteVideoTrack = null;
            });
        }

        public void onDataChannel(final DataChannel dc) {
            Log.d("PCRTCClient", "New Data channel " + dc.label());
            if (GroupPeerConnectionClient.this.dataChannelEnabled) {
                dc.registerObserver(new DataChannel.Observer() {
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


}
