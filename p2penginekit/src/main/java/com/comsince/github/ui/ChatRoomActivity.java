package com.comsince.github.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.comsince.github.R;
import com.comsince.github.groupcallenginekit.AVGroupEngineKit;
import com.comsince.github.groupcallenginekit.MemberBean;
import com.comsince.github.groupcallenginekit.ProxyVideoSink;
import com.comsince.github.p2penginekit.AVEngineKit;

import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.wildfirechat.client.NotInitializedExecption;

/**
 * 群聊界面
 * 支持 9 路同時通信
 */
public class ChatRoomActivity extends AppCompatActivity implements AVGroupEngineKit.CallSessionCallback {
    private String TAG = "ChatRoomActivity";
    private FrameLayout wr_video_view;
    private AVGroupEngineKit avGroupEngineKit;
    private String[] tos;
    private boolean isOutgoing;
    private boolean isAudioOnly;
    //群组聊天,这个targetId就是groupId
    private String targetId;
    private Map<String, SurfaceViewRenderer> _videoViews = new HashMap<>();
    private Map<String, ProxyVideoSink> _sinks = new HashMap<>();
    private List<MemberBean> _infos = new ArrayList<>();
    private VideoTrack _localVideoTrack;

    private int mScreenWidth;

    private EglBase rootEglBase;

    public static final String EXTRA_MO = "ISMO";
    public static final String EXTRA_AUDIO_ONLY = "audioOnly";
    public static final String EXTRA_TOS = "tos";
    public static final String EXTRA_TARGET = "TARGET";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.wr_activity_chat_room);
        initView();
        initVar();
        ChatRoomFragment chatRoomFragment = new ChatRoomFragment();
        replaceFragment(chatRoomFragment);

        try {
            avGroupEngineKit = AVGroupEngineKit.Instance();
        } catch (NotInitializedExecption notInitializedExecption) {
            notInitializedExecption.printStackTrace();
            finish();
        }
        final Intent intent = getIntent();
        targetId = intent.getStringExtra(EXTRA_TARGET);
        ArrayList<String> tosList = intent.getStringArrayListExtra(EXTRA_TOS);
        if(tosList != null){
            tos = new String[tosList.size()];
            tosList.toArray(tos);
        }
        isOutgoing = intent.getBooleanExtra(EXTRA_MO, false);
        isAudioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false);
        init(targetId,tos,isOutgoing,isAudioOnly);
    }


    private void initView() {
        wr_video_view = findViewById(R.id.wr_video_view);
    }

    private void initVar() {
        // 设置宽高比例
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager != null) {
            mScreenWidth = manager.getDefaultDisplay().getWidth();
        }
        wr_video_view.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mScreenWidth));
        rootEglBase = EglBase.create();

    }

    private void init(String targetId,String[] tos, boolean outgoing, boolean audioOnly) {
        AVGroupEngineKit.CallSession session = avGroupEngineKit.getCurrentSession();
        if (outgoing) {
            avGroupEngineKit.startCall(targetId, audioOnly, tos,ChatRoomActivity.this);
        } else {
            if (session == null) {
                finish();
            } else {
                session.setCallback(ChatRoomActivity.this);
            }
        }
    }

    public boolean isOutgoing() {
        return isOutgoing;
    }

    private void addView(String id, VideoTrack videoTrack) {
        SurfaceViewRenderer renderer = new SurfaceViewRenderer(ChatRoomActivity.this);
        renderer.init(rootEglBase.getEglBaseContext(), null);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        renderer.setMirror(true);
        // set render
        ProxyVideoSink sink = new ProxyVideoSink();
        sink.setTarget(renderer);
        if(videoTrack != null){
            videoTrack.addSink(sink);
        }
        _videoViews.put(id, renderer);
        _sinks.put(id, sink);
        _infos.add(new MemberBean(id));
        wr_video_view.addView(renderer);

        int size = _infos.size();
        for (int i = 0; i < size; i++) {
            MemberBean memberBean = _infos.get(i);
            SurfaceViewRenderer renderer1 = _videoViews.get(memberBean.getId());
            if (renderer1 != null) {
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                layoutParams.height = getWidth(size);
                layoutParams.width = getWidth(size);
                layoutParams.leftMargin = getX(size, i);
                layoutParams.topMargin = getY(size, i);
                renderer1.setLayoutParams(layoutParams);
            }

        }


    }


    private void removeView(String userId) {
        ProxyVideoSink sink = _sinks.get(userId);
        SurfaceViewRenderer renderer = _videoViews.get(userId);
        if (sink != null) {
            sink.setTarget(null);
        }
        if (renderer != null) {
            renderer.release();
        }
        _sinks.remove(userId);
        _videoViews.remove(userId);
        _infos.remove(new MemberBean(userId));
        wr_video_view.removeView(renderer);


        int size = _infos.size();
        for (int i = 0; i < _infos.size(); i++) {
            MemberBean memberBean = _infos.get(i);
            SurfaceViewRenderer renderer1 = _videoViews.get(memberBean.getId());
            if (renderer1 != null) {
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                layoutParams.height = getWidth(size);
                layoutParams.width = getWidth(size);
                layoutParams.leftMargin = getX(size, i);
                layoutParams.topMargin = getY(size, i);
                renderer1.setLayoutParams(layoutParams);
            }

        }

    }

    private int getWidth(int size) {
        if (size <= 4) {
            return mScreenWidth / 2;
        } else if (size <= 9) {
            return mScreenWidth / 3;
        }
        return mScreenWidth / 3;
    }

    private int getX(int size, int index) {
        if (size <= 4) {
            if (size == 3 && index == 2) {
                return mScreenWidth / 4;
            }
            return (index % 2) * mScreenWidth / 2;
        } else if (size <= 9) {
            if (size == 5) {
                if (index == 3) {
                    return mScreenWidth / 6;
                }
                if (index == 4) {
                    return mScreenWidth / 2;
                }
            }

            if (size == 7 && index == 6) {
                return mScreenWidth / 3;
            }

            if (size == 8) {
                if (index == 6) {
                    return mScreenWidth / 6;
                }
                if (index == 7) {
                    return mScreenWidth / 2;
                }
            }
            return (index % 3) * mScreenWidth / 3;
        }
        return 0;
    }

    private int getY(int size, int index) {
        if (size < 3) {
            return mScreenWidth / 4;
        } else if (size < 5) {
            if (index < 2) {
                return 0;
            } else {
                return mScreenWidth / 2;
            }
        } else if (size < 7) {
            if (index < 3) {
                return mScreenWidth / 2 - (mScreenWidth / 3);
            } else {
                return mScreenWidth / 2;
            }
        } else if (size <= 9) {
            if (index < 3) {
                return 0;
            } else if (index < 6) {
                return mScreenWidth / 3;
            } else {
                return mScreenWidth / 3 * 2;
            }

        }
        return 0;
    }


    @Override  // 屏蔽返回键
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return keyCode == KeyEvent.KEYCODE_BACK || super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        exit();
        super.onDestroy();
    }

    private void replaceFragment(Fragment fragment) {
        FragmentManager manager = getSupportFragmentManager();
        manager.beginTransaction()
                .replace(R.id.wr_container, fragment)
                .commit();

    }

    public void accept() {
        AVGroupEngineKit.CallSession session = avGroupEngineKit.getCurrentSession();
        if (session != null && session.getState() == AVGroupEngineKit.CallState.Incoming) {
            session.answerCall(false);
        } else {
            finish();
        }
    }

    // 切换摄像头
    public void switchCamera() {
        AVGroupEngineKit.CallSession session = avGroupEngineKit.getCurrentSession();
        if (session != null) {
            session.switchCamera();
        }
    }

    // 挂断
    public void hangUp() {
        AVGroupEngineKit.CallSession session = avGroupEngineKit.getCurrentSession();
        if (session != null) {
            session.endCall();
        }
        exit();
        this.finish();
    }

    // 静音
    public void toggleMic(boolean enable) {
        AVGroupEngineKit.CallSession session = avGroupEngineKit.getCurrentSession();
        if (session != null && session.getState() != AVGroupEngineKit.CallState.Idle) {
            session.muteAudio(enable);
        }
    }

    // 免提
    public void toggleSpeaker(boolean enable) {
        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(enable);
    }

    // 打开关闭摄像头
    public void toggleCamera(boolean enableCamera) {
        if (_localVideoTrack != null) {
            _localVideoTrack.setEnabled(enableCamera);
        }

    }

    private void exit() {
        for (SurfaceViewRenderer renderer : _videoViews.values()) {
            renderer.release();
        }
        for (ProxyVideoSink sink : _sinks.values()) {
            sink.setTarget(null);
        }
        _videoViews.clear();
        _sinks.clear();
        _infos.clear();

    }

    @Override
    public void didCallEndWithReason(AVGroupEngineKit.CallEndReason callEndReason) {

    }

    @Override
    public void didChangeState(AVGroupEngineKit.CallState callState) {

    }

    @Override
    public void didChangeMode(boolean changeMode) {

    }

    @Override
    public void didCreateLocalVideoTrack(String userId, VideoTrack videoTrack) {
        _localVideoTrack = videoTrack;
        runOnUiThread(() -> {
            addView(userId, videoTrack);
        });
    }

    @Override
    public void didReceiveRemoteVideoTrack(String userId, VideoTrack remoteVideoTrack) {
        runOnUiThread(() -> {
            Log.i(TAG,"didReceiveRemoteVideoTrack userId "+userId);
            addView(userId, remoteVideoTrack);
        });
    }

    @Override
    public void didRemoveVideoTrack(String userId) {
        runOnUiThread(() -> {
            removeView(userId);
        });
    }

    @Override
    public void didError(String error) {

    }

    @Override
    public void didGetStats(StatsReport[] statsReports) {

    }
}
