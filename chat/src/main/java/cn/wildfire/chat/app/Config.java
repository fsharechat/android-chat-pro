package cn.wildfire.chat.app;

import android.os.Environment;

/**
 * Created by heavyrain lee on 2017/11/24.
 */

public class Config {

//    String IM_SERVER_HOST = "192.168.0.102";
    public static String IM_SERVER_HOST = "backend-tcp.fsharechat.cn:backend-tcp-s2.fsharechat.cn";
//    String IM_SERVER_HOST = "172.16.46.201";
    public static int IM_SERVER_PORT = 6789;

//    String APP_SERVER_HOST = "192.168.0.102";
    public static String APP_SERVER_HOST = "backend-http.fsharechat.cn";
//    String APP_SERVER_HOST = "172.16.46.201";
    public static int APP_SERVER_PORT = 443;

    public static String ICE_ADDRESS = "turn:turn.fsharechat.cn:3478";
    public static String ICE_SH_ADDRESS = "turn:sh-turn.fsharechat.cn:3478";
    public static String ICE_USERNAME = "comsince";
    public static String ICE_PASSWORD = "comsince";

    public static int DEFAULT_MAX_AUDIO_RECORD_TIME_SECOND = 120;

    public static String SP_NAME = "config";
    public static String SP_KEY_SHOW_GROUP_MEMBER_ALIAS = "show_group_member_alias:%s";

    public static String VIDEO_SAVE_DIR = Environment.getExternalStorageDirectory().getPath() + "/FshareChat/video";
    public static String AUDIO_SAVE_DIR = Environment.getExternalStorageDirectory().getPath() + "/FshareChat/audio";
    public static String PHOTO_SAVE_DIR = Environment.getExternalStorageDirectory().getPath() + "/FshareChat/photo";
    public static String FILE_SAVE_DIR = Environment.getExternalStorageDirectory().getPath() + "/FshareChat/file";
}
