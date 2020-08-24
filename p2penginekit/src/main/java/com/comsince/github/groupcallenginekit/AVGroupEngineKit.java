package com.comsince.github.groupcallenginekit;

import android.content.Context;
import android.util.Log;
import com.comsince.github.message.AnswerMessage;
import com.comsince.github.message.ByeMessage;
import com.comsince.github.message.ModifyMessage;
import com.comsince.github.message.SignalMessage;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.VideoTrack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import cn.wildfirechat.client.NotInitializedExecption;
import cn.wildfirechat.message.CallStartMessageContent;
import cn.wildfirechat.message.Message;
import cn.wildfirechat.message.MessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.model.Conversation;
import cn.wildfirechat.remote.ChatManager;
import cn.wildfirechat.remote.OnReceiveMessageListener;
import cn.wildfirechat.remote.SendMessageCallback;

public class AVGroupEngineKit implements OnReceiveMessageListener {
    private static String TAG = "AVGroupEngineKit";
    private static AVGroupEngineKit Instance;
    private Context mContent;
    private String currentUserId;
    //是否为群组聊天发起者
    private boolean isInitiator = false;
    private Map<String,Participant> participants;
    private AVGroupEngineKit.CallSession currentSession;
    private AVGroupEngineKit.AVEngineCallback engineCallback;
    protected ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<PeerConnection.IceServer> iceServers = new ArrayList();

    public static AVGroupEngineKit Instance() throws NotInitializedExecption {
        if (Instance == null) {
            throw new NotInitializedExecption();
        } else {
            return Instance;
        }
    }

    public static void init(Context context, String currentUserId, AVGroupEngineKit.AVEngineCallback callback) {
        if (Instance == null) {
            Instance = new AVGroupEngineKit();
            Instance.mContent = context;
            Instance.engineCallback = callback;
            Instance.currentUserId = currentUserId;
            Instance.participants = new HashMap<>();
            ChatManager.Instance().addOnReceiveMessageListener(Instance);
        }
    }

    public void addIceServer(String address, String userName, String password) {
        PeerConnection.IceServer iceServer = PeerConnection.IceServer.builder(address).setUsername(userName).setPassword(password).createIceServer();
        this.iceServers.add(iceServer);
    }

    public List<PeerConnection.IceServer> getIceServers(){
        return iceServers;
    }

    /**
     * 开始群组音视频通话
     *      * @param target 群组会话target
     *      * @param tos 邀请的用户target 数组列表
     *      * @param isAudioOnly 是否仅仅是音频聊天
     * */
    public AVGroupEngineKit.CallSession startCall(String targetId, boolean audioOnly, String[] tos,AVGroupEngineKit.CallSessionCallback callback){
        Log.i(TAG,"start call targetId "+targetId+" tos "+tos);
        this.isInitiator = true;
        CountDownLatch latch = new CountDownLatch(1);
        Future future = this.executor.submit(() -> {
            AVGroupEngineKit.CallSession callSession;
            try {
                AVGroupEngineKit.CallSession session = this.newSession(targetId, audioOnly, targetId + System.currentTimeMillis());
                session.callback = callback;
                if (this.currentSession != null && this.currentSession.state != AVGroupEngineKit.CallState.Idle) {
                    session.callback.didCallEndWithReason(AVGroupEngineKit.CallEndReason.Busy);
                    return session;
                }

                this.currentSession = session;
                this.currentSession.setState(AVGroupEngineKit.CallState.Outgoing);
                CallStartMessageContent invite = new CallStartMessageContent(session.callId, targetId, audioOnly);
                this.onOfferMessage(invite, targetId, tos, true);
                callSession = session;
            } finally {
                latch.countDown();
            }

            return callSession;
        });
        try {
            latch.await();
            return (AVGroupEngineKit.CallSession)future.get();
        } catch (InterruptedException interruptedException) {
            interruptedException.printStackTrace();
        } catch (ExecutionException executionException) {
            executionException.printStackTrace();
        }

        return null;
    }

    private AVGroupEngineKit.CallSession newSession(String clientId, boolean audioOnly, String callId) {
        AVGroupEngineKit.CallSession session = new AVGroupEngineKit.CallSession();
        session.clientId = clientId;
        session.callId = callId;
        session.audioOnly = audioOnly;
        return session;
    }

    public AVGroupEngineKit.CallSession getCurrentSession() {
        return this.currentSession;
    }

    public EglBase.Context getEglBaseContextByUserId(String userId){
        return participants.get(userId).getEglBaseContext();
    }

    protected void onOfferMessage(final MessageContent message, final String clientId,final boolean isKeyMsg){
        onOfferMessage(message,clientId,new String[0],isKeyMsg);
    }

    protected void onOfferMessage(final MessageContent message, final String clientId, String[] tos,final boolean isKeyMsg) {
        final Message msg = new Message();
        msg.toUsers = tos;
        msg.content = message;
        msg.conversation = new Conversation(Conversation.ConversationType.Group, clientId);

        try {
            ChatManager.Instance().sendMessage(msg, new SendMessageCallback() {
                public void onSuccess(long messageUid, long timestamp) {
                    Log.d("CallRTCClient", "send message success");
                    if (message instanceof CallStartMessageContent && AVGroupEngineKit.this.currentSession != null && clientId.equals(AVGroupEngineKit.this.currentSession.clientId)) {
                        AVGroupEngineKit.this.currentSession.startMsgId = msg.messageId;
                    }

                }

                public void onFail(int errorCode) {
                    if (isKeyMsg) {
                        AVGroupEngineKit.this.executor.submit(() -> {
                            if (AVGroupEngineKit.this.currentSession != null && AVGroupEngineKit.this.currentSession.getState() != AVGroupEngineKit.CallState.Idle) {
                                AVGroupEngineKit.this.currentSession.callback.didError("Signal error");
                                AVGroupEngineKit.this.currentSession.endCall(AVGroupEngineKit.CallEndReason.SignalError);
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

    @Override
    public void onReceiveMessage(List<Message> messages, boolean hasMore) {
        for(Message message : messages){
            onReceiveCallMessage(message);
        }
    }

    private boolean onReceiveCallMessage(Message message) {
        long deltaTime = 0L;

        try {
            deltaTime = ChatManager.Instance().getServerDeltaTime();
        } catch (NotInitializedExecption notInitializedExecption) {
            notInitializedExecption.printStackTrace();
        }

        //过滤非群组消息信令
        if(message.conversation.type != Conversation.ConversationType.Group){
            return false;
        }

        if (!(message.content instanceof SignalMessage) && !(message.content instanceof CallStartMessageContent) && !(message.content instanceof AnswerMessage)
                && !(message.content instanceof ByeMessage) && !(message.content instanceof ModifyMessage)) {
            return false;
        } else {
            if (System.currentTimeMillis() - (message.serverTime - deltaTime) < 90000L && (message.direction == MessageDirection.Receive || message.content instanceof AnswerMessage)) {
                this.executor.submit(() -> {
                    if (message.content instanceof SignalMessage) {
                        if (this.currentSession != null && this.currentSession.state != AVGroupEngineKit.CallState.Idle) {
                            SignalMessage signal = (SignalMessage)message.content;
                            try {
                                this.onReceiveData(signal.getPayload());
                            } catch (JSONException jsonException) {
                                jsonException.printStackTrace();
                            }
                        }
                    } else if (message.content instanceof CallStartMessageContent) {
                        CallStartMessageContent invite = (CallStartMessageContent)message.content;
                        if (this.currentSession != null && this.currentSession.state != AVGroupEngineKit.CallState.Idle) {
                            this.rejectOtherCall(invite.getCallId(), message.sender);
                        } else {
                            //注意这里的clientId是值当前群组会话的targetId
                            AVGroupEngineKit.CallSession newSession = this.newSession(message.conversation.target, invite.isAudioOnly(), invite.getCallId());
                            newSession.setState(AVGroupEngineKit.CallState.Incoming);
                            newSession.startMsgId = message.messageId;
                            this.currentSession = newSession;
                            //获取当前群聊用户列表

                            this.engineCallback.onReceiveCall(newSession);
                        }
                    } else {
                        if (message.content instanceof ByeMessage) {
                            if (this.currentSession == null || this.currentSession.state == AVGroupEngineKit.CallState.Idle) {
                                return;
                            }

                            this.currentSession.endCall(AVGroupEngineKit.CallEndReason.RemoteHangup);
                        }
                    }
                });
            }

            return message.content instanceof SignalMessage || message.content instanceof AnswerMessage || message.content instanceof ByeMessage;
        }
    }

    private void rejectOtherCall(String callId, String clientId) {
        ByeMessage bye = new ByeMessage(callId);
        this.onOfferMessage(bye, clientId, false);
    }

    private void onReceiveData(byte[] data) throws JSONException {
        String jsonStr = new String(data);
        Log.i(TAG,"onReceiveData "+jsonStr);
        JSONObject json = new JSONObject(jsonStr);
        String type = json.optString("type");
        switch (type){
            case "newParticipantArrived":
                onNewParticipant(json.getString("name"));
                break;
            case "existingParticipants":
                JSONArray participantsArr = (JSONArray) json.get("data");
                onExistingParticipants(participantsArr);
                break;
            case "participantLeft":
                onParticipantLeft(json.getString("name"));
                break;
            case "receiveVideoAnswer":
                onReceiverAnswer(json.getString("name"),json.getString("sdpAnswer"));
                break;
            case "iceCandidate":
                this.onRemoteIceCandidate(json.getString("name"),json.getJSONObject("candidate"));
                break;
            default:
                break;
        }
    }

    private void onExistingParticipants(JSONArray participantArray) throws JSONException {
        Participant participant = new Participant(this.mContent,this.currentSession.clientId,this.currentUserId,this,this.currentSession.audioOnly);
        participant.newWebRtcSendOnlyClient();
        this.participants.put(this.currentUserId,participant);
        for(int i = 0 ;i < participantArray.length(); i++){
            String userId = participantArray.getString(i);
            Log.i(TAG,"onExistingParticipants "+userId);
            onProcessReceiveParticipant(userId);
        }
    }

    private void onNewParticipant(String userId){
        onProcessReceiveParticipant(userId);
    }

    private void onParticipantLeft(String userId){
        Participant participant = participants.remove(userId);
        participant.dispose();
    }

    private void onProcessReceiveParticipant(String userId){
        Participant participant = new Participant(this.mContent,this.currentSession.clientId,userId,this,this.currentSession.audioOnly);
        participant.newWebRtcReceiveOnlyClient();
        this.participants.put(userId,participant);
    }

    private void onReceiverAnswer(String userId,String sdpAnswer){
        Log.i(TAG,userId+ " receive answer "+sdpAnswer);
        SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.fromCanonicalForm("answer"), sdpAnswer);
        this.participants.get(userId).processAnswer(sessionDescription);
    }

    private void onRemoteIceCandidate(String userId,JSONObject iceCandidateJson) throws JSONException {
        IceCandidate candidate = toJavaCandidate(iceCandidateJson);
        participants.get(userId).addIceCandidate(candidate);
    }

    IceCandidate toJavaCandidate(JSONObject json) throws JSONException {
        return new IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"));
    }

    public class CallSession {
        protected String callId;
        private AVGroupEngineKit.CallState state;
        protected String clientId;
        private boolean audioOnly;
        protected AVGroupEngineKit.CallSessionCallback callback;
        private long startMsgId;
        private long startTime;
        protected long connectedTime;
        private long endTime;

        private CallSession() {
            this.startTime = System.currentTimeMillis();
        }

        public boolean isAudioOnly() {
            return this.audioOnly;
        }

        public void setCallback(AVGroupEngineKit.CallSessionCallback callback) {
            this.callback = callback;
        }

        public String getClientId(){
            return clientId;
        }

        public CallSessionCallback getCallback(){
            return callback;
        }

        public void setState(AVGroupEngineKit.CallState state){
            this.state = state;
            if (this.callback != null) {
                this.callback.didChangeState(state);
            }
        }
        public AVGroupEngineKit.CallState getState() {
            return this.state;
        }

        public void answerCall(boolean audioOnly){
            AVGroupEngineKit.this.executor.submit(() -> {
                if (this.state != AVGroupEngineKit.CallState.Incoming) {
                    Log.d("CallRTCClient", "can not answer call in state " + this.state);
                } else {
                    this.setState(AVGroupEngineKit.CallState.Connecting);
                    boolean ao = this.isAudioOnly() ? true : audioOnly;
                    AnswerMessage answerx = new AnswerMessage(this.callId, ao);
                    AVGroupEngineKit.this.onOfferMessage(answerx, this.clientId,true);
                }
            });
        }

        public void endCall() {
            this.endCall(AVGroupEngineKit.CallEndReason.Hangup);
        }

        public void endCall(AVGroupEngineKit.CallEndReason reason) {
            AVGroupEngineKit.this.executor.submit(() -> {
                if (this.state != AVGroupEngineKit.CallState.Idle) {
                    this.endTime = System.currentTimeMillis();
                    if (reason != AVGroupEngineKit.CallEndReason.AcceptByOtherClient) {
                        ByeMessage bye = new ByeMessage(this.callId);
                        AVGroupEngineKit.this.onOfferMessage(bye, this.clientId, false);
                    }

                    for(String userId : participants.keySet()){
                        Participant participant = participants.get(userId);
                        if(participant != null){
                            participant.dispose();
                        }
                    }
                    participants.clear();

                    this.clientId = null;
                    this.callId = null;
                    this.setState(AVGroupEngineKit.CallState.Idle);
                    this.callback.didCallEndWithReason(reason);
                    AVGroupEngineKit.this.currentSession = null;


                }
            });
        }

        public void switchCamera() {
            executor.submit(() -> {
                Participant participant = participants.get(AVGroupEngineKit.this.currentUserId);
                if (participant.groupPeerConnectionClient != null) {
                    participant.groupPeerConnectionClient.switchCamera();
                }

            });
        }

        public boolean muteAudio(boolean muted) {
            Future future = executor.submit(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    Participant participant = participants.get(AVGroupEngineKit.this.currentUserId);
                    if (participant.groupPeerConnectionClient != null) {
                        participant.groupPeerConnectionClient.setAudioEnabled(!muted);
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
    }

    public interface AVEngineCallback {
        void onReceiveCall(AVGroupEngineKit.CallSession callSession);

        void shouldStartRing(boolean shouldStartRing);

        void shouldSopRing();
    }

    public interface CallSessionCallback {
        void didCallEndWithReason(AVGroupEngineKit.CallEndReason callEndReason);

        void didChangeState(AVGroupEngineKit.CallState callState);

        void didChangeMode(boolean changeMode);

        void didCreateLocalVideoTrack(String userId, VideoTrack videoTrack);

        void didReceiveRemoteVideoTrack(String userId,VideoTrack remoteVideoTrack);

        void didRemoveVideoTrack(String userId);

        void didError(String error);

        void didGetStats(StatsReport[] statsReports);
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
