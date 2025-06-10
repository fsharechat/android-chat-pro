package cn.wildfire.chat.app;

import android.app.ActivityManager;
import android.content.Context;
import java.io.File;

import cn.wildfire.chat.app.third.location.viewholder.LocationMessageContentViewHolder;
import cn.wildfire.chat.kit.WfcUIKit;
import cn.wildfire.chat.kit.conversation.message.viewholder.MessageViewHolderManager;


public class MyApp extends BaseApp {

    private WfcUIKit wfcUIKit;

    @Override
    public void onCreate() {
        super.onCreate();

        // 只在主进程初始化
        if (getCurProcessName(this).equals("cn.comsince.fschat")) {
            wfcUIKit = new WfcUIKit();
            wfcUIKit.init(this);
            MessageViewHolderManager.getInstance().registerMessageViewHolder(LocationMessageContentViewHolder.class);
//            seuptwfcdirs();
            setupWFCDirs();
        }
    }

//    private void seuptwfcdirs() {
//        File file = new File(Config.VIDEO_SAVE_DIR);
//        if (!file.exists()) {
//            file.mkdirs();
//        }
//        file = new File(Config.AUDIO_SAVE_DIR);
//        if (!file.exists()) {
//            file.mkdirs();
//        }
//        file = new File(Config.FILE_SAVE_DIR);
//        if (!file.exists()) {
//            file.mkdirs();
//        }
//        file = new File(Config.PHOTO_SAVE_DIR);
//        if (!file.exists()) {
//            file.mkdirs();
//        }
//    }

    private void setupWFCDirs() {
        Config.VIDEO_SAVE_DIR = this.getDir("video", Context.MODE_PRIVATE).getAbsolutePath();
        Config.AUDIO_SAVE_DIR = this.getDir("audio", Context.MODE_PRIVATE).getAbsolutePath();
        Config.PHOTO_SAVE_DIR = this.getDir("photo", Context.MODE_PRIVATE).getAbsolutePath();
//        Config.FILE_SAVE_DIR = this.getDir("file", Context.MODE_PRIVATE).getAbsolutePath();
    }

    public static String getCurProcessName(Context context) {

        int pid = android.os.Process.myPid();

        ActivityManager activityManager = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);

        for (ActivityManager.RunningAppProcessInfo appProcess : activityManager
                .getRunningAppProcesses()) {

            if (appProcess.pid == pid) {
                return appProcess.processName;
            }
        }
        return null;
    }
}
