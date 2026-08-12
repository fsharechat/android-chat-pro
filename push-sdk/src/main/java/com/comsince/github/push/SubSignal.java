package com.comsince.github.push;

/**
 * @author comsicne
 * Copyright (c) [2019]
 * @Time 19-6-11 下午4:07
 **/
public enum SubSignal {
    NONE,
    // connect
    CONNECTION_ACCEPTED,
    CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION,
    CONNECTION_REFUSED_IDENTIFIER_REJECTED,
    CONNECTION_REFUSED_SERVER_UNAVAILABLE,
    CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD,
    CONNECTION_REFUSED_NOT_AUTHORIZED,
    CONNECTION_REFUSED_UNEXPECT_NODE,
    CONNECTION_REFUSED_SESSION_NOT_EXIST,
    //publish
    US,
    FAR,
    UPUI,
    FRN,
    FRUS,
    FRP,
    FHR,
    FP,
    MN,
    MS,
    MP,
    FN,
    GC,
    GPGI,
    GPGM,
    GAM,
    GKM,
    GQ,
    GMI,
    MMI,
    GQNUT,
    MR,
    RMN,
    LRM,
    GD,
    GMURL,
    FALS,
    MRN,
    MRP,
    MRR,
    MDR,
    SAI,
    // 用户设置同步：UG 拉取、UP 上报、UN 服务端通知。
    // 只允许在末尾追加，ordinal 与服务端 push-common、ios SubSignal.swift 对齐
    UG,
    UP,
    UN
    ;


    public static SubSignal toEnum(int ordinal) {
        byte o = (byte) ordinal;
        if (o > NONE.ordinal() &&
                o < SubSignal.values().length) {
            for (SubSignal signal : SubSignal.values()) {
                if (signal.ordinal() == o) {
                    return signal;
                }
            }
        }
        return NONE;

    }

    public static SubSignal toEnum(String topic){
        if(topic != null && !"".equals(topic)){
            for(SubSignal signal : SubSignal.values()){
                if(signal.name().equals(topic)){
                    return signal;
                }
            }
        }
        return NONE;
    }
}
