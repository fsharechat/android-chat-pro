package com.comsince.github.push;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * SubSignal 的 ordinal 是二进制协议的一部分，与服务端 push-common、ios 的 SubSignal.swift 逐位对齐。
 * 只允许在末尾追加；任何插入或重排都会让线上客户端解析错位。
 */
public class SubSignalOrdinalTest {

    @Test
    public void existingOrdinalsAreNotShifted() {
        assertEquals(0, SubSignal.NONE.ordinal());
        assertEquals(9, SubSignal.US.ordinal());
        assertEquals(22, SubSignal.GPGI.ordinal());
        assertEquals(40, SubSignal.SAI.ordinal());
    }

    @Test
    public void userSettingSignalsAreAppendedAtTheEnd() {
        assertEquals(41, SubSignal.UG.ordinal());
        assertEquals(42, SubSignal.UP.ordinal());
        assertEquals(43, SubSignal.UN.ordinal());
    }

    @Test
    public void toEnumResolvesUserSettingOrdinals() {
        assertEquals(SubSignal.UG, SubSignal.toEnum(41));
        assertEquals(SubSignal.UP, SubSignal.toEnum(42));
        assertEquals(SubSignal.UN, SubSignal.toEnum(43));
    }
}
