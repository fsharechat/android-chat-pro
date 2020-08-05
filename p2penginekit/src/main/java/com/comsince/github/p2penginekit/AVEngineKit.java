package com.comsince.github.p2penginekit;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.view.SurfaceView;
import com.comsince.github.p2penginekit.AVAudioManager.AudioDevice;
import com.comsince.github.p2penginekit.PeerConnectionClient.PeerConnectionEvents;
import com.comsince.github.message.AnswerMessage;
import com.comsince.github.message.AnswerTMessage;
import com.comsince.github.message.ByeMessage;
import com.comsince.github.message.ModifyMessage;
import com.comsince.github.message.SignalMessage;
import cn.wildfirechat.client.NotInitializedExecption;
import cn.wildfirechat.message.CallStartMessageContent;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.model.Conversation;
import cn.wildfirechat.model.Conversation.ConversationType;
import cn.wildfirechat.remote.ChatManager;
import cn.wildfirechat.remote.OnReceiveMessageListener;
import cn.wildfirechat.remote.SendMessageCallback;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.FileVideoCapturer;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoTrack;
import org.webrtc.CameraVideoCapturer.CameraEventsHandler;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.RendererCommon.RendererEvents;
import org.webrtc.RendererCommon.ScalingType;
import org.webrtc.SessionDescription.Type;

public class AVEngineKit implements OnReceiveMessageListener {
    private PeerConnectionEvents peerConnectionEventHandler = new PeerConnectionEvents() {
        public void onLocalDescription(SessionDescription sdp) {
            SignalMessage signalMessage = new SignalMessage(AVEngineKit.this.currentSession.callId);
            JSONObject json = new JSONObject();
            AVEngineKit.jsonPut(json, "sdp", sdp.description);
            if (AVEngineKit.this.isInitiator) {
                AVEngineKit.jsonPut(json, "type", "offer");
            } else {
                AVEngineKit.jsonPut(json, "type", "answer");
            }

            signalMessage.setPayload(json.toString().getBytes());
            AVEngineKit.this.onOfferMessage(signalMessage, AVEngineKit.this.currentSession.clientId, true);
            VideoProfile videoProfile = VideoProfile.getVideoProfile(AVEngineKit.this.mVideoProfile, AVEngineKit.this.mSwapWidthHeight);
            Log.d("CallRTCClient", "Set video maximum bitrate: " + videoProfile.bitrate);
            AVEngineKit.this.peerConnectionClient.setVideoMaxBitrate(videoProfile.bitrate);
        }

        public void onIceCandidate(IceCandidate candidate) {
            SignalMessage signalMessage = new SignalMessage(AVEngineKit.this.currentSession.callId);
            JSONObject json = new JSONObject();
            AVEngineKit.jsonPut(json, "type", "candidate");
            AVEngineKit.jsonPut(json, "label", candidate.sdpMLineIndex);
            AVEngineKit.jsonPut(json, "id", candidate.sdpMid);
            AVEngineKit.jsonPut(json, "candidate", candidate.sdp);
            signalMessage.setPayload(json.toString().getBytes());
            AVEngineKit.this.onOfferMessage(signalMessage, AVEngineKit.this.currentSession.clientId, false);
        }

        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            JSONObject json = new JSONObject();
            AVEngineKit.jsonPut(json, "type", "remove-candidates");
            JSONArray jsonArray = new JSONArray();
            IceCandidate[] var4 = candidates;
            int var5 = candidates.length;

            for(int var6 = 0; var6 < var5; ++var6) {
                IceCandidate candidate = var4[var6];
                jsonArray.put(AVEngineKit.toJsonCandidate(candidate));
            }

            AVEngineKit.jsonPut(json, "candidates", jsonArray);
            SignalMessage signalMessage = new SignalMessage(AVEngineKit.this.currentSession.callId);
            signalMessage.setPayload(json.toString().getBytes());
            AVEngineKit.this.onOfferMessage(signalMessage, AVEngineKit.this.currentSession.clientId, false);
        }

        public void onIceConnected() {
            AVEngineKit.this.executor.submit(() -> {
                if (AVEngineKit.this.peerConnectionClient != null && AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.getState() != AVEngineKit.CallState.Idle) {
                    AVEngineKit.this.currentSession.connectedTime = System.currentTimeMillis();
                    AVEngineKit.this.peerConnectionClient.enableStatsEvents(true, 1000);
                    AVEngineKit.this.currentSession.setState(AVEngineKit.CallState.Connected);
                } else {
                    Log.w("CallRTCClient", "Call is connected in closed or error state");
                }
            });
        }

        public void onIceDisconnected() {
            AVEngineKit.this.executor.submit(() -> {
                if (AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.getState() != AVEngineKit.CallState.Idle) {
                    AVEngineKit.this.currentSession.endCall(AVEngineKit.CallEndReason.MediaError);
                }

            });
        }

        public void onAddRemoteVideoTrack(VideoTrack rvt) {
            AVEngineKit.this.executor.submit(() -> {
                AVEngineKit.this.remoteVideoTrack = rvt;
                AVEngineKit.this.currentSession.callback.didReceiveRemoteVideoTrack();
            });
        }

        public void onRemoveRemoteVideoTrack(VideoTrack rvt) {
            AVEngineKit.this.executor.submit(() -> {
                AVEngineKit.this.remoteVideoTrack = null;
                if (AVEngineKit.this.currentSession != null) {
                    AVEngineKit.this.currentSession.remoteRenderer = null;
                }

            });
        }

        public void onCreateLocalVideoTrack(VideoTrack lvt) {
            AVEngineKit.this.executor.submit(() -> {
                AVEngineKit.this.localVideoTrack = lvt;
                AVEngineKit.this.currentSession.callback.didCreateLocalVideoTrack();
            });
        }

        public void onRemoveLocalVideoTrack() {
            AVEngineKit.this.executor.submit(() -> {
                AVEngineKit.this.localVideoTrack = null;
            });
        }

        public void onPeerConnectionClosed() {
        }

        public void onPeerConnectionStatsReady(StatsReport[] reports) {
            AVEngineKit.this.currentSession.callback.didGetStats(reports);
        }

        public void onPeerConnectionError(String description) {
            AVEngineKit.this.executor.submit(() -> {
                AVEngineKit.this.currentSession.callback.didError(description);
                AVEngineKit.this.currentSession.endCall(AVEngineKit.CallEndReason.MediaError);
            });
        }
    };
    private VideoCapturer videoCapturer;
    private static final String TAG = "CallRTCClient";
    private static AVEngineKit Instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AVEngineKit.CallSession currentSession;
    private AVEngineKit.AVEngineCallback engineCallback;
    private Context mContent;
    private AVAudioManager audioManager = null;
    private boolean isInitiator;
    private PeerConnectionClient peerConnectionClient;
    private VideoTrack localVideoTrack;
    private VideoTrack remoteVideoTrack;
    private List<IceServer> iceServers = new ArrayList();
    private int mVideoProfile = 30;
    private boolean mSwapWidthHeight = false;

    private static void jsonPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException var4) {
            throw new RuntimeException(var4);
        }
    }

    public AVEngineKit.CallSession getCurrentSession() {
        return this.currentSession;
    }

    public void onReceiveMessage(List<Message> messages, boolean hasMore) {
        Iterator var3 = messages.iterator();

        while(var3.hasNext()) {
            Message message = (Message)var3.next();
            this.onReceiveCallMessage(message);
        }

    }

    private AVEngineKit.CallSession newSession(String clientId, boolean audioOnly, String callId) {
        AVEngineKit.CallSession session = new AVEngineKit.CallSession();
        session.clientId = clientId;
        session.callId = callId;
        session.audioOnly = audioOnly;
        return session;
    }

    private AVEngineKit() {
    }

    public static AVEngineKit Instance() throws NotInitializedExecption {
        if (Instance == null) {
            throw new NotInitializedExecption();
        } else {
            return Instance;
        }
    }

    public static void init(Context context, AVEngineKit.AVEngineCallback callback) {
        if (Instance == null) {
            Instance = new AVEngineKit();
            Instance.mContent = context;
            Instance.engineCallback = callback;

            try {
                ChatManager.Instance().registerMessageContent(AnswerMessage.class);
                ChatManager.Instance().registerMessageContent(AnswerTMessage.class);
                ChatManager.Instance().registerMessageContent(ByeMessage.class);
                ChatManager.Instance().registerMessageContent(SignalMessage.class);
                ChatManager.Instance().registerMessageContent(ModifyMessage.class);
            } catch (NotInitializedExecption var3) {
                var3.printStackTrace();
            }

            ChatManager.Instance().addOnReceiveMessageListener(Instance);
        }
    }

    public AVEngineKit.CallSession startCall(String clientId, boolean audioOnly, AVEngineKit.CallSessionCallback callback) {
        CountDownLatch latch = new CountDownLatch(1);
        Future future = this.executor.submit(() -> {
            AVEngineKit.CallSession callSession;
            try {
                AVEngineKit.CallSession session = this.newSession(clientId, audioOnly, clientId + System.currentTimeMillis());
                session.callback = callback;
                if (this.currentSession != null && this.currentSession.state != AVEngineKit.CallState.Idle) {
                    session.callback.didCallEndWithReason(AVEngineKit.CallEndReason.Busy);
                    return session;
                }

                this.currentSession = session;
                this.currentSession.setState(AVEngineKit.CallState.Outgoing);
                CallStartMessageContent invite = new CallStartMessageContent(session.callId, clientId, audioOnly);
                this.onOfferMessage(invite, clientId, true);
                callSession = session;
            } finally {
                latch.countDown();
            }

            return callSession;
        });

        try {
            latch.await();
            return (AVEngineKit.CallSession)future.get();
        } catch (InterruptedException var7) {
            var7.printStackTrace();
        } catch (ExecutionException var8) {
            var8.printStackTrace();
        }

        return null;
    }

    private void onOfferMessage(final MessageContent message, final String clientId, final boolean isKeyMsg) {
        final Message msg = new Message();
        msg.content = message;
        msg.conversation = new Conversation(ConversationType.Single, clientId);

        try {
            ChatManager.Instance().sendMessage(msg, new SendMessageCallback() {
                public void onSuccess(long messageUid, long timestamp) {
                    Log.d("CallRTCClient", "send message success");
                    if (message instanceof CallStartMessageContent && AVEngineKit.this.currentSession != null && clientId.equals(AVEngineKit.this.currentSession.clientId)) {
                        AVEngineKit.this.currentSession.startMsgId = msg.messageId;
                    }

                }

                public void onFail(int errorCode) {
                    if (isKeyMsg) {
                        AVEngineKit.this.executor.submit(() -> {
                            if (AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.getState() != AVEngineKit.CallState.Idle) {
                                AVEngineKit.this.currentSession.callback.didError("Signal error");
                                AVEngineKit.this.currentSession.endCall(AVEngineKit.CallEndReason.SignalError);
                            }

                        });
                    } else {
                        try {
                            ChatManager.Instance().sendMessage(msg, new SendMessageCallback() {
                                public void onSuccess(long l, long l1) {
                                    if (msg.messageId > 0L) {
                                    }

                                }

                                public void onFail(int i) {
                                }

                                public void onPrepare(long l, long l1) {
                                }

                                public void onMediaUpload(String s) {
                                }
                            });
                        } catch (NotInitializedExecption var3) {
                            var3.printStackTrace();
                        }
                    }

                }

                public void onPrepare(long messageId, long savedTime) {
                    Log.d("CallRTCClient", "send message prepared");
                }

                public void onMediaUpload(String remoteUrl) {
                }
            });
        } catch (NotInitializedExecption var6) {
            var6.printStackTrace();
        }

    }

    public boolean onReceiveCallMessage(Message message) {
        long deltaTime = 0L;

        try {
            deltaTime = ChatManager.Instance().getServerDeltaTime();
        } catch (NotInitializedExecption notInitializedExecption) {
            notInitializedExecption.printStackTrace();
        }

        if (!(message.content instanceof SignalMessage) && !(message.content instanceof CallStartMessageContent) && !(message.content instanceof AnswerMessage) && !(message.content instanceof ByeMessage) && !(message.content instanceof ModifyMessage)) {
            return false;
        } else {
            if (System.currentTimeMillis() - (message.serverTime - deltaTime) < 90000L && (message.direction == MessageDirection.Receive || message.content instanceof AnswerMessage)) {
                this.executor.submit(() -> {
                    if (message.content instanceof SignalMessage) {
                        if (this.currentSession != null && this.currentSession.state != AVEngineKit.CallState.Idle) {
                            SignalMessage signal = (SignalMessage)message.content;
                            if (message.sender.equals(this.currentSession.clientId) && signal.getCallId().equals(this.currentSession.callId)) {
                                if (this.currentSession.state == AVEngineKit.CallState.Connected || this.currentSession.state == AVEngineKit.CallState.Connecting) {
                                    try {
                                        this.onReceiveData(signal.getPayload());
                                    } catch (JSONException var4) {
                                        var4.printStackTrace();
                                    }
                                }

                            } else {
                                this.rejectOtherCall(signal.getCallId(), message.sender);
                            }
                        }
                    } else if (message.content instanceof CallStartMessageContent) {
                        CallStartMessageContent invite = (CallStartMessageContent)message.content;
                        if (this.currentSession != null && this.currentSession.state != AVEngineKit.CallState.Idle) {
                            this.rejectOtherCall(invite.getCallId(), message.sender);
                        } else {
                            AVEngineKit.CallSession newSession = this.newSession(message.sender, invite.isAudioOnly(), invite.getCallId());
                            newSession.setState(AVEngineKit.CallState.Incoming);
                            newSession.startMsgId = message.messageId;
                            this.currentSession = newSession;
                            this.engineCallback.onReceiveCall(newSession);
                        }
                    } else {
                        if (message.content instanceof AnswerMessage) {
                            AnswerMessage answer = (AnswerMessage)message.content;
                            if (this.currentSession != null && this.currentSession.state != AVEngineKit.CallState.Idle) {
                                if (message.sender.equals(this.currentSession.clientId) && answer.getCallId().equals(this.currentSession.callId)) {
                                    if (this.currentSession.state != AVEngineKit.CallState.Connecting && this.currentSession.state != AVEngineKit.CallState.Connected) {
                                        if (this.currentSession.state != AVEngineKit.CallState.Outgoing) {
                                            this.rejectOtherCall(answer.getCallId(), message.sender);
                                            return;
                                        }

                                        this.currentSession.setState(AVEngineKit.CallState.Connecting);
                                        this.currentSession.setAudioOnly(answer.isAudioOnly());
                                        this.startMedia(false);
                                        return;
                                    }

                                    return;
                                }

                                if (message.direction == MessageDirection.Receive) {
                                    this.rejectOtherCall(answer.getCallId(), message.sender);
                                } else if (this.currentSession.state == AVEngineKit.CallState.Incoming) {
                                    this.currentSession.endCall(AVEngineKit.CallEndReason.AcceptByOtherClient);
                                }

                                return;
                            }
                        } else if (message.content instanceof ByeMessage) {
                            ByeMessage bye = (ByeMessage)message.content;
                            if (this.currentSession == null || this.currentSession.state == AVEngineKit.CallState.Idle || !this.currentSession.callId.equals(bye.getCallId()) || !this.currentSession.clientId.equals(message.sender)) {
                                return;
                            }

                            this.currentSession.endCall(AVEngineKit.CallEndReason.RemoteHangup);
                        } else if (message.content instanceof ModifyMessage) {
                            ModifyMessage modifyMessage = (ModifyMessage)message.content;
                            if (this.currentSession != null && this.currentSession.state == AVEngineKit.CallState.Connected && this.currentSession.callId.equals(modifyMessage.getCallId()) && this.currentSession.clientId.equals(message.sender)) {
                                this.currentSession.setAudioOnly(modifyMessage.isAudioOnly());
                            }
                        }

                    }
                });
            }

            return message.content instanceof SignalMessage || message.content instanceof AnswerMessage || message.content instanceof ByeMessage;
        }
    }

    public void startPreview() {
        if (!this.currentSession.isAudioOnly()) {
            if (this.videoCapturer == null) {
                this.videoCapturer = this.createVideoCapturer();
            }

            this.createPeerConnectionClient();
            this.peerConnectionClient.createVideoTrack(this.videoCapturer);
        }
    }

    private void createPeerConnectionClient() {
        if (this.peerConnectionClient == null) {
            this.peerConnectionClient = new PeerConnectionClient();
            VideoProfile videoProfile = VideoProfile.getVideoProfile(this.mVideoProfile, this.mSwapWidthHeight);
            this.peerConnectionClient.createPeerConnectionFactory(this.mContent, this.peerConnectionEventHandler, !this.currentSession.audioOnly, videoProfile.width, videoProfile.height, videoProfile.fps, videoProfile.bitrate);
        }

    }

    private void rejectOtherCall(String callId, String clientId) {
        ByeMessage bye = new ByeMessage(callId);
        this.onOfferMessage(bye, clientId, false);
    }

    private void onReceiveData(byte[] data) throws JSONException {
        String jsonStr = new String(data);
        JSONObject json = new JSONObject(jsonStr);
        String type = json.optString("type");
        if (type.equals("candidate")) {
            this.onRemoteIceCandidate(this.toJavaCandidate(json));
        } else if (type.equals("remove-candidates")) {
            JSONArray candidateArray = json.getJSONArray("candidates");
            IceCandidate[] candidates = new IceCandidate[candidateArray.length()];

            for(int i = 0; i < candidateArray.length(); ++i) {
                candidates[i] = this.toJavaCandidate(candidateArray.getJSONObject(i));
            }

            this.onRemoteIceCandidatesRemoved(candidates);
        } else {
            SessionDescription sdp;
            if (type.equals("answer")) {
                if (this.isInitiator) {
                    sdp = new SessionDescription(Type.fromCanonicalForm(type), json.getString("sdp"));
                    this.onRemoteDescription(sdp);
                }
            } else if (type.equals("offer") && !this.isInitiator) {
                sdp = new SessionDescription(Type.fromCanonicalForm(type), json.getString("sdp"));
                this.onRemoteDescription(sdp);
            }
        }

    }

    IceCandidate toJavaCandidate(JSONObject json) throws JSONException {
        return new IceCandidate(json.getString("id"), json.getInt("label"), json.getString("candidate"));
    }

    private void onRemoteDescription(SessionDescription sdp) {
        this.executor.submit(() -> {
            if (this.peerConnectionClient == null) {
                Log.e("CallRTCClient", "Received remote SDP for non-initilized peer connection.");
            } else {
                this.peerConnectionClient.setRemoteDescription(sdp);
                if (!this.isInitiator) {
                    this.peerConnectionClient.createAnswer();
                }

            }
        });
    }

    private void onRemoteIceCandidate(IceCandidate candidate) {
        this.executor.submit(() -> {
            if (this.peerConnectionClient == null) {
                Log.e("CallRTCClient", "Received ICE candidate for a non-initialized peer connection.");
            } else {
                this.peerConnectionClient.addRemoteIceCandidate(candidate);
            }
        });
    }

    private void onRemoteIceCandidatesRemoved(IceCandidate[] candidates) {
        this.executor.submit(() -> {
            if (this.peerConnectionClient == null) {
                Log.e("CallRTCClient", "Received ICE candidate removals for a non-initialized peer connection.");
            } else {
                this.peerConnectionClient.removeRemoteIceCandidates(candidates);
            }
        });
    }

    private void stopMedia() {
        if (this.peerConnectionClient != null) {
            this.peerConnectionClient.close();
            this.peerConnectionClient = null;
        }

        Log.d("CallRTCClient", "Stopping capture.");
        if (this.videoCapturer != null) {
            try {
                this.videoCapturer.stopCapture();
            } catch (InterruptedException var2) {
                throw new RuntimeException(var2);
            }

            this.videoCapturer.dispose();
            this.videoCapturer = null;
        }

        this.localVideoTrack = null;
        this.remoteVideoTrack = null;
        if (this.audioManager != null) {
            (new AVEngineKit.BackgroundTask((Runnable)null, () -> {
                this.audioManager.stop();
                this.audioManager = null;
            })).execute(new Void[0]);
        }

    }

    private void startMedia(boolean isInitiator) {
        this.isInitiator = isInitiator;
        this.currentSession.setState(AVEngineKit.CallState.Connecting);
        this.createPeerConnectionClient();
        (new AVEngineKit.BackgroundTask((Runnable)null, () -> {
            this.audioManager = AVAudioManager.create(this.mContent);
            this.audioManager.start((audioDevice, availableAudioDevices) -> {
                this.onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
            });
        })).execute(new Void[0]);
        if (this.videoCapturer == null && !this.currentSession.isAudioOnly()) {
            this.videoCapturer = this.createVideoCapturer();
        }

        if (this.currentSession.isAudioOnly() && this.videoCapturer != null) {
            try {
                this.videoCapturer.stopCapture();
            } catch (InterruptedException exception) {
                throw new RuntimeException(exception);
            }

            this.videoCapturer.dispose();
            this.videoCapturer = null;
            this.peerConnectionClient.removeVideoTrack();
        }

        this.peerConnectionClient.createPeerConnection(this.videoCapturer, this.iceServers);
        if (isInitiator) {
            this.peerConnectionClient.createOffer();
        }

    }

    public void addIceServer(String address, String userName, String password) {
        IceServer iceServer = IceServer.builder(address).setUsername(userName).setPassword(password).createIceServer();
        this.iceServers.add(iceServer);
    }

    private void onAudioManagerDevicesChanged(AudioDevice device, Set<AudioDevice> availableDevices) {
        Log.d("ddd", "onAudioManagerDevicesChanged: " + availableDevices + ", selected: " + device);
    }

    private VideoCapturer createVideoCapturer() {
        String videoFileAsCamera = null;
        VideoCapturer videoCapturer;
        if (videoFileAsCamera != null) {
            try {
                videoCapturer = new FileVideoCapturer((String)videoFileAsCamera);
            } catch (IOException var4) {
                return null;
            }
        } else {
            Logging.d("CallRTCClient", "Creating capturer using camera1 API.");
            videoCapturer = this.createCameraCapturer(new Camera1Enumerator(this.captureToTexture()));
        }

        if (videoCapturer == null) {
            this.executor.submit(() -> {
                if (this.currentSession != null) {
                    this.currentSession.callback.didError("Failure open camera");
                    this.currentSession.endCall(AVEngineKit.CallEndReason.OpenCameraFailure);
                }

            });
            return null;
        } else {
            return videoCapturer;
        }
    }

    private boolean captureToTexture() {
        return false;
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        String[] deviceNames = enumerator.getDeviceNames();
        Logging.d("CallRTCClient", "Looking for front facing cameras.");
        for(String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d("CallRTCClient", "Creating front facing camera capturer.");
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        Logging.d("CallRTCClient", "Looking for other cameras.");

        for(String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d("CallRTCClient", "Creating other camera capturer.");
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private static JSONObject toJsonCandidate(IceCandidate candidate) {
        JSONObject json = new JSONObject();
        jsonPut(json, "label", candidate.sdpMLineIndex);
        jsonPut(json, "id", candidate.sdpMid);
        jsonPut(json, "candidate", candidate.sdp);
        return json;
    }

    public void setVideoProfile(int videoProfile, boolean swapWidthHeight) {
        this.mVideoProfile = videoProfile;
        this.mSwapWidthHeight = swapWidthHeight;
    }

    private static class BackgroundTask extends AsyncTask<Void, Void, Void> {
        private final Runnable bgRunnable;
        private final Runnable fgRunnable;

        public BackgroundTask(Runnable bgRunnable, Runnable fgRunnable) {
            this.bgRunnable = bgRunnable;
            this.fgRunnable = fgRunnable;
        }

        protected void onPostExecute(Void aVoid) {
            if (this.fgRunnable != null) {
                this.fgRunnable.run();
            }

        }

        protected Void doInBackground(Void... voids) {
            if (this.bgRunnable != null) {
                this.bgRunnable.run();
            }

            return null;
        }
    }

    public class CallSession {
        private String callId;
        private AVEngineKit.CallState state;
        private String clientId;
        private boolean audioOnly;
        private AVEngineKit.CallSessionCallback callback;
        private List<SurfaceViewRenderer> surfaceViewRenderers;
        private VideoRenderer localRenderer;
        private VideoRenderer remoteRenderer;
        private long startTime;
        private long connectedTime;
        private long endTime;
        private Timer waitTimer;
        private long startMsgId;

        public boolean isAudioOnly() {
            return this.audioOnly;
        }

        public void setAudioOnly(boolean audioOnly) {
            if (this.audioOnly != audioOnly) {
                this.audioOnly = audioOnly;
                this.callback.didChangeMode(audioOnly);
                AVEngineKit.this.executor.submit(() -> {
                    if (AVEngineKit.this.currentSession.getState() == AVEngineKit.CallState.Connected) {
                        ModifyMessage modifyMessage = new ModifyMessage(this.callId, audioOnly);
                        AVEngineKit.this.onOfferMessage(modifyMessage, this.clientId, true);
                    }

                    if (audioOnly && AVEngineKit.this.peerConnectionClient != null) {
                        AVEngineKit.this.peerConnectionClient.removeVideoTrack();
                    }

                });
            }
        }

        public void switchCamera() {
            AVEngineKit.this.executor.submit(() -> {
                if (AVEngineKit.this.peerConnectionClient != null) {
                    AVEngineKit.this.peerConnectionClient.switchCamera();
                }

            });
        }

        public boolean muteAudio(final boolean muted) {
            Future future = AVEngineKit.this.executor.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    if (AVEngineKit.this.peerConnectionClient != null) {
                        AVEngineKit.this.peerConnectionClient.setAudioEnabled(!muted);
                        return true;
                    } else {
                        return false;
                    }
                }
            });

            try {
                return (Boolean)future.get();
            } catch (InterruptedException var4) {
                var4.printStackTrace();
            } catch (ExecutionException var5) {
                var5.printStackTrace();
            }

            return false;
        }

        public boolean muteVideo(final boolean muted) {
            Future future = AVEngineKit.this.executor.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    if (AVEngineKit.this.peerConnectionClient != null) {
                        AVEngineKit.this.peerConnectionClient.setVideoEnabled(!muted);
                        return true;
                    } else {
                        return false;
                    }
                }
            });

            try {
                return (Boolean)future.get();
            } catch (InterruptedException var4) {
                var4.printStackTrace();
            } catch (ExecutionException var5) {
                var5.printStackTrace();
            }

            return false;
        }

        private void setState(AVEngineKit.CallState state) {
            if (this.state != state) {
                AVEngineKit.CallState previousState = this.state;
                this.state = state;
                if (state != AVEngineKit.CallState.Incoming && state != AVEngineKit.CallState.Outgoing) {
                    if (state != AVEngineKit.CallState.Idle && state != AVEngineKit.CallState.Connected) {
                        if (state == AVEngineKit.CallState.Connecting) {
                            AVEngineKit.this.engineCallback.shouldSopRing();
                            if (this.waitTimer != null) {
                                this.waitTimer.cancel();
                            }

                            this.waitTimer = new Timer();
                            this.waitTimer.schedule(new TimerTask() {
                                public void run() {
                                    if (AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.state != AVEngineKit.CallState.Connected) {
                                        AVEngineKit.this.executor.submit(() -> {
                                            if (AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.state != AVEngineKit.CallState.Connected) {
                                                AVEngineKit.this.currentSession.callback.didError("Connect timeout");
                                                AVEngineKit.this.currentSession.endCall(AVEngineKit.CallEndReason.Timeout);
                                            }

                                        });
                                    }

                                }
                            }, 60000L);
                        }
                    } else {
                        if (state == AVEngineKit.CallState.Idle && (previousState == AVEngineKit.CallState.Incoming || previousState == AVEngineKit.CallState.Outgoing)) {
                            AVEngineKit.this.engineCallback.shouldSopRing();
                        }

                        if (this.waitTimer != null) {
                            this.waitTimer.cancel();
                        }

                        this.waitTimer = null;
                        if (this.startMsgId > 0L) {
                            try {
                                Message msg = ChatManager.Instance().getMessage(this.startMsgId);
                                if (msg.content instanceof CallStartMessageContent) {
                                    CallStartMessageContent startMessageContent = (CallStartMessageContent)msg.content;
                                    if (state == AVEngineKit.CallState.Connected) {
                                        startMessageContent.setConnectTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(1);
                                    } else {
                                        startMessageContent.setEndTime(System.currentTimeMillis());
                                        startMessageContent.setStatus(2);
                                    }

                                    ChatManager.Instance().updateMessage(this.startMsgId, startMessageContent);
                                }
                            } catch (NotInitializedExecption var5) {
                                var5.printStackTrace();
                            }
                        }
                    }
                } else {
                    AVEngineKit.this.engineCallback.shouldStartRing(state == AVEngineKit.CallState.Incoming);
                    if (this.waitTimer != null) {
                        this.waitTimer.cancel();
                    }

                    this.waitTimer = new Timer();
                    this.waitTimer.schedule(new TimerTask() {
                        public void run() {
                            if (AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.state != AVEngineKit.CallState.Connected) {
                                AVEngineKit.this.executor.submit(() -> {
                                    if (AVEngineKit.this.currentSession != null && AVEngineKit.this.currentSession.state != AVEngineKit.CallState.Connected) {
                                        if (AVEngineKit.this.currentSession.callback != null) {
                                            AVEngineKit.this.currentSession.callback.didError("Wait anwser timeout");
                                        }

                                        AVEngineKit.this.currentSession.endCall(AVEngineKit.CallEndReason.Timeout);
                                    }

                                });
                            }

                        }
                    }, 60000L);
                }

                if (this.callback != null) {
                    this.callback.didChangeState(state);
                }

            }
        }

        public AVEngineKit.CallState getState() {
            return this.state;
        }

        public SurfaceView createRendererView() {
            if (AVEngineKit.this.peerConnectionClient != null) {
                try {
                    SurfaceViewRenderer pipRenderer = new SurfaceViewRenderer(AVEngineKit.this.mContent);
                    pipRenderer.init(AVEngineKit.this.peerConnectionClient.getRenderContext(), (RendererEvents)null);
                    this.surfaceViewRenderers.add(pipRenderer);
                    return pipRenderer;
                } catch (Exception var2) {
                    var2.printStackTrace();
                    return null;
                }
            } else {
                return null;
            }
        }

        public void setVideoScalingType(SurfaceView rendererView, ScalingType scalingType) {
            if (rendererView instanceof SurfaceViewRenderer) {
                ((SurfaceViewRenderer)rendererView).setScalingType(scalingType);
            }

        }

        public void setupLocalVideo(SurfaceView rendererView, ScalingType scalingType) {
            if (rendererView != null) {
                ((SurfaceViewRenderer)rendererView).setScalingType(scalingType);
            }

            AVEngineKit.this.executor.submit(() -> {
                if (this.localRenderer != null) {
                    AVEngineKit.this.localVideoTrack.removeRenderer(this.localRenderer);
                }

                if (rendererView != null && AVEngineKit.this.localVideoTrack != null) {
                    this.localRenderer = new VideoRenderer((SurfaceViewRenderer)rendererView);
                    AVEngineKit.this.localVideoTrack.addRenderer(this.localRenderer);
                }

            });
        }

        public void setupRemoteVideo(SurfaceView rendererView, ScalingType scalingType) {
            if (rendererView != null) {
                ((SurfaceViewRenderer)rendererView).setScalingType(scalingType);
            }

            AVEngineKit.this.executor.submit(() -> {
                if (this.remoteRenderer != null) {
                    AVEngineKit.this.remoteVideoTrack.removeRenderer(this.remoteRenderer);
                }

                if (rendererView != null && AVEngineKit.this.remoteVideoTrack != null) {
                    this.remoteRenderer = new VideoRenderer((SurfaceViewRenderer)rendererView);
                    AVEngineKit.this.remoteVideoTrack.addRenderer(this.remoteRenderer);
                }

            });
        }

        public void answerCall(boolean audioOnly) {
            AVEngineKit.this.executor.submit(() -> {
                if (this.state != AVEngineKit.CallState.Incoming) {
                    Log.d("CallRTCClient", "can not answer call in state " + this.state);
                } else {
                    this.setState(AVEngineKit.CallState.Connecting);
                    boolean ao = this.isAudioOnly() ? true : audioOnly;
                    this.setAudioOnly(ao);
                    AnswerMessage answer = new AnswerTMessage(this.callId, ao);
                    AVEngineKit.this.onOfferMessage(answer, this.clientId, false);
                    AnswerMessage answerx = new AnswerMessage(this.callId, ao);
                    AVEngineKit.this.onOfferMessage(answerx, this.clientId, true);
                    AVEngineKit.this.startMedia(true);
                }
            });
        }

        public void endCall() {
            this.endCall(AVEngineKit.CallEndReason.Hangup);
        }

        private void endCall(AVEngineKit.CallEndReason reason) {
            AVEngineKit.this.executor.submit(() -> {
                if (this.state != AVEngineKit.CallState.Idle) {
                    this.endTime = System.currentTimeMillis();
                    if (reason != AVEngineKit.CallEndReason.AcceptByOtherClient) {
                        ByeMessage bye = new ByeMessage(this.callId);
                        AVEngineKit.this.onOfferMessage(bye, this.clientId, false);
                    }

                    this.clientId = null;
                    this.callId = null;
                    this.setState(AVEngineKit.CallState.Idle);
                    this.localRenderer = null;
                    this.remoteRenderer = null;
                    Iterator var4 = this.surfaceViewRenderers.iterator();

                    while(var4.hasNext()) {
                        SurfaceViewRenderer renderer = (SurfaceViewRenderer)var4.next();
                        renderer.release();
                    }

                    this.surfaceViewRenderers.clear();
                    this.callback.didCallEndWithReason(reason);
                    AVEngineKit.this.currentSession = null;
                    AVEngineKit.this.stopMedia();
                }
            });
        }

        private CallSession() {
            this.surfaceViewRenderers = new ArrayList();
            this.startTime = System.currentTimeMillis();
        }

        public void setCallback(AVEngineKit.CallSessionCallback callback) {
            this.callback = callback;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void stopVideoSource() {
            AVEngineKit.this.executor.submit(() -> {
                if (this.state != AVEngineKit.CallState.Idle && this.state != AVEngineKit.CallState.Incoming && AVEngineKit.this.peerConnectionClient != null) {
                    AVEngineKit.this.peerConnectionClient.stopVideoSource();
                }

            });
        }

        public void startVideoSource() {
            AVEngineKit.this.executor.submit(() -> {
                if (this.state != AVEngineKit.CallState.Idle && this.state != AVEngineKit.CallState.Incoming && AVEngineKit.this.peerConnectionClient != null) {
                    AVEngineKit.this.peerConnectionClient.startVideoSource();
                }

            });
        }

        public long getStartTime() {
            return this.startTime;
        }

        public long getConnectedTime() {
            return this.connectedTime;
        }

        public long getEndTime() {
            return this.endTime;
        }
    }

    public interface CallSessionCallback {
        void didCallEndWithReason(AVEngineKit.CallEndReason callEndReason);

        void didChangeState(AVEngineKit.CallState callState);

        void didChangeMode(boolean changeMode);

        void didCreateLocalVideoTrack();

        void didReceiveRemoteVideoTrack();

        void didError(String error);

        void didGetStats(StatsReport[] statsReports);
    }

    public interface AVEngineCallback {
        void onReceiveCall(AVEngineKit.CallSession callSession);

        void shouldStartRing(boolean shouldStartRing);

        void shouldSopRing();
    }

    public enum CallEndReason {
        Busy,
        SignalError,
        Hangup,
        MediaError,
        RemoteHangup,
        OpenCameraFailure,
        Timeout,
        AcceptByOtherClient;
    }

    public enum CallState {
        Idle,
        Outgoing,
        Incoming,
        Connecting,
        Connected;
    }
}
