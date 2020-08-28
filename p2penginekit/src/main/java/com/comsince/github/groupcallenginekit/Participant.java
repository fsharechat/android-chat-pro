package com.comsince.github.groupcallenginekit;

import android.content.Context;
import android.util.Log;
import com.comsince.github.message.SignalMessage;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.FileVideoCapturer;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoTrack;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

public class Participant implements GroupPeerConnectionClient.PeerConnectionEvents {
    private static final String TAG = "Participant";
    private String target;
    private String sender;
    private Context mContent;
    protected GroupPeerConnectionClient groupPeerConnectionClient;
    private AVGroupEngineKit avGroupEngineKit;
    private boolean audioOnly;
    private VideoCapturer videoCapturer;
    private VideoTrack localVideoTrack;
    private ExecutorService executor;

    public Participant(Context context,String target, String sender, AVGroupEngineKit avGroupEngineKit,boolean audioOnly){
        this.mContent = context;
        this.target = target;
        this.sender = sender;
        this.avGroupEngineKit = avGroupEngineKit;
        this.audioOnly = audioOnly;
        this.executor = avGroupEngineKit.executor;
    }

    public void newWebRtcSendOnlyClient(DefaultPeerConnectionFactory defaultPeerConnectionFactory){
        if(this.groupPeerConnectionClient == null){
           this.groupPeerConnectionClient = new GroupPeerConnectionClient(mContent);
           this.groupPeerConnectionClient.init(defaultPeerConnectionFactory,this,this.audioOnly,StreamMode.SEND_ONLY);
        }
        startMedia(StreamMode.SEND_ONLY);
    }

    public void newWebRtcReceiveOnlyClient(DefaultPeerConnectionFactory defaultPeerConnectionFactory){
        if(this.groupPeerConnectionClient == null){
            this.groupPeerConnectionClient = new GroupPeerConnectionClient(mContent);
            this.groupPeerConnectionClient.init(defaultPeerConnectionFactory,this,this.audioOnly,StreamMode.RECV_ONLY);
        }
        startMedia(StreamMode.RECV_ONLY);
    }

    public void newWebRtcSendRecClient(DefaultPeerConnectionFactory defaultPeerConnectionFactory){
        if(this.groupPeerConnectionClient == null){
            this.groupPeerConnectionClient = new GroupPeerConnectionClient(mContent);
            this.groupPeerConnectionClient.init(defaultPeerConnectionFactory,this,this.audioOnly,StreamMode.SENDRECV);
        }
        startMedia(StreamMode.SENDRECV);
    }

    private void startMedia(StreamMode streamMode){
        if(this.sender.equals(avGroupEngineKit.currentUserId)){
            avGroupEngineKit.getCurrentSession().setState(AVGroupEngineKit.CallState.Connecting);
        }
        if (this.videoCapturer == null && !this.avGroupEngineKit.getCurrentSession().isAudioOnly() && streamMode != StreamMode.RECV_ONLY) {
            this.videoCapturer = this.createVideoCapture();
        }
        Log.i(TAG,"startMedia "+streamMode);
        this.groupPeerConnectionClient.createPeerConnection(this.videoCapturer, this.avGroupEngineKit.getIceServers());
        this.groupPeerConnectionClient.createOffer();
    }

    public void processAnswer(SessionDescription sessionDescription){
        this.executor.submit(()-> {
            if (this.groupPeerConnectionClient == null) {
                Log.e("CallRTCClient", "Received remote SDP for non-initilized peer connection.");
            } else {
                Log.d(TAG,this.sender+ " processAnswer "+sessionDescription);
                this.groupPeerConnectionClient.setRemoteDescription(sessionDescription);
            }
        });
    }

    public void addIceCandidate(IceCandidate iceCandidate){
        this.executor.submit(() -> {
            if (this.groupPeerConnectionClient == null) {
                Log.e("CallRTCClient", "Received ICE candidate for a non-initialized peer connection.");
            } else {
                Log.d(TAG,this.sender+" addIceCandidate "+iceCandidate);
                this.groupPeerConnectionClient.addRemoteIceCandidate(iceCandidate);
            }
        });
    }

    public EglBase.Context getEglBaseContext(){
        if(groupPeerConnectionClient != null){
            return groupPeerConnectionClient.getRenderContext();
        }
        return null;
    }

    public void dispose(){
        if(this.groupPeerConnectionClient != null){
            this.groupPeerConnectionClient.close();
            this.groupPeerConnectionClient = null;
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
    }



    private VideoCapturer createVideoCapture() {
        String videoFileAsCamera = null;
        VideoCapturer videoCapturer;
        if (videoFileAsCamera != null) {
            try {
                videoCapturer = new FileVideoCapturer((String)videoFileAsCamera);
            } catch (IOException var4) {
                return null;
            }
        } else {
            Logging.d(TAG, "Creating capturer using camera1 API.");
            videoCapturer = this.createCameraCapture(new Camera1Enumerator(this.captureToTexture()));
        }

        if (videoCapturer == null) {
            this.executor.submit(() -> {
                if (this.avGroupEngineKit.getCurrentSession() != null) {
                    this.avGroupEngineKit.getCurrentSession().getCallback().didError("Failure open camera");
                    this.avGroupEngineKit.getCurrentSession().endCall(AVGroupEngineKit.CallEndReason.OpenCameraFailure);
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

    private VideoCapturer createCameraCapture(CameraEnumerator enumerator) {
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

    @Override
    public void onLocalDescription(SessionDescription sessionDescription) {
        SignalMessage signalMessage = new SignalMessage(avGroupEngineKit.getCurrentSession().callId);
        JSONObject json = new JSONObject();
        jsonPut(json, "sdpOffer", sessionDescription.description);
        jsonPut(json, "type", "receiveVideoFrom");
        jsonPut(json,"sender",this.sender);
        signalMessage.setPayload(json.toString().getBytes());
        Log.d(TAG,this.sender+ " send local description "+json.toString());
        avGroupEngineKit.onOfferMessage(signalMessage, target, new String[0],true);
    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate) {
        SignalMessage signalMessage = new SignalMessage(avGroupEngineKit.getCurrentSession().callId);
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "onIceCandidate");
        jsonPut(json,"name",this.sender);
        JSONObject candidate = new JSONObject();
        jsonPut(candidate,"sdpMid",iceCandidate.sdpMid);
        jsonPut(candidate,"sdpMLineIndex",iceCandidate.sdpMLineIndex);
        jsonPut(candidate,"candidate",iceCandidate.toString());
        jsonPut(json,"candidate",candidate);
        signalMessage.setPayload(json.toString().getBytes());
        avGroupEngineKit.onOfferMessage(signalMessage, avGroupEngineKit.getCurrentSession().clientId, new String[0],false);
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

    }

    @Override
    public void onIceConnected() {
        executor.submit(() -> {
            if (avGroupEngineKit != null && avGroupEngineKit.getCurrentSession() != null
                    && avGroupEngineKit.currentUserId.equals(sender)
                    && avGroupEngineKit.getCurrentSession().getState() != AVGroupEngineKit.CallState.Idle) {
                Log.d(TAG,this.sender + " onIceConnected ");
                avGroupEngineKit.getCurrentSession().connectedTime = System.currentTimeMillis();
                groupPeerConnectionClient.enableStatsEvents(true, 1000);
                avGroupEngineKit.getCurrentSession().setState(AVGroupEngineKit.CallState.Connected);
            } else {
                Log.w("CallRTCClient", "Call is connected in closed or error state");
            }
        });
    }

    @Override
    public void onIceDisconnected() {
        executor.submit(() -> {
            if (avGroupEngineKit.getCurrentSession() != null
                    && avGroupEngineKit.currentUserId.equals(sender)
                    && avGroupEngineKit.getCurrentSession().getState() != AVGroupEngineKit.CallState.Idle) {
                avGroupEngineKit.getCurrentSession().endCall(AVGroupEngineKit.CallEndReason.MediaError);
            }

        });
    }

    @Override
    public void onAddRemoteVideoTrack(VideoTrack videoTrack) {
        executor.submit(() -> {
            avGroupEngineKit.getCurrentSession().callback.didReceiveRemoteVideoTrack(this.sender,videoTrack);
        });
    }

    @Override
    public void onRemoveRemoteVideoTrack(VideoTrack videoTrack) {

    }

    @Override
    public void onCreateLocalVideoTrack(VideoTrack videoTrack) {
        executor.submit(() -> {
            localVideoTrack = videoTrack;
            avGroupEngineKit.getCurrentSession().callback.didCreateLocalVideoTrack(this.sender,videoTrack);
        });
    }

    @Override
    public void onRemoveLocalVideoTrack() {
        executor.submit(() -> {
            avGroupEngineKit.getCurrentSession().callback.didRemoveVideoTrack(this.sender);
        });
    }

    @Override
    public void onPeerConnectionClosed() {

    }

    @Override
    public void onPeerConnectionStatsReady(StatsReport[] statsReports) {

    }

    @Override
    public void onPeerConnectionError(String error) {

    }

    private void jsonPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException var4) {
            throw new RuntimeException(var4);
        }
    }
}
