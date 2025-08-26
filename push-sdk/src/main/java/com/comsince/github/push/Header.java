package com.comsince.github.push;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class Header {
    public static final int LENGTH = 10;
    public static final int VERSION = 2;
    public static final byte magic = (byte)0xf8;


    protected byte[] mContents;

    public Header() {
        mContents = new byte[LENGTH];
        mContents[0] = (byte)0xf8;  //flag
        mContents[1] = VERSION;   //version
    }

    public Header(ByteBuffer buffer) {
        mContents = new byte[LENGTH];
        if(buffer.limit() >= LENGTH) {
            for(int i = 0; i < LENGTH; i++) {
                mContents[i] = buffer.get();
            }
        }
    }

    public byte[] getContents() {
        return mContents;
    }

    public Header setVersion(int version) {
        mContents[1] = (byte) version;
        return this;
    }

    public int getVersion() {
        return mContents[1];
    }

    public Header setSignal(Signal signal) {
        mContents[2] = (byte)((mContents[2] & (0x01 << 7)) | signal.ordinal());
        return this;
    }

    public Signal getSignal() {
        return Signal.toEnum((~(0x01 << 7)) & mContents[2]);
    }

    public Header setLength(int length) {
        mContents[3] = (byte)((length >> 24) & 0xff);
        mContents[4] = (byte)((length >> 16) & 0xff);
        mContents[5] = (byte)((length >> 8)  & 0xff);
        mContents[6] = (byte)(length);
        return this;
    }

    public int getLength() {
        return ( ((mContents[3] & 0xff) << 24)
                |((mContents[4] & 0xff) << 16)
                |((mContents[5] & 0xff) << 8)
                | (0xff & mContents[6]));
    }

    public Header setSubSignal(SubSignal subSignal){
        mContents[7] = (byte)((mContents[7] & (0x01 << 7)) | subSignal.ordinal()) ;
        return this;
    }

    public SubSignal getSubSignal(){
        return SubSignal.toEnum((~(0x01 << 7)) & mContents[7]);
    }

    public Header setMessageId(int messageId){
        mContents[8] = (byte)((messageId >> 8) & 0xff);
        mContents[9] = (byte)(messageId & 0xff);
        return this;
    }

    public int getMessageId(){
        return (((mContents[8] & 0xff) << 8) | (0xff & mContents[9]));
    }


    public boolean isValid(){
        return mContents[0] == (byte)0xf8 &&
                getLength() >= 0 &&
                getSignal() != Signal.NONE;
    }

    public static void main(String[] args){
        System.out.println(0x01 << 7);
        System.out.println(~(0x01 << 7));
        System.out.println(~0x80);
    }

    @Override
    public String toString() {
        return "Header magic "+mContents[0]+" length "+getLength()+" signal "+getSignal();
    }
}
