package com.comsince.github;
import com.comsince.github.logger.Log;
import com.comsince.github.client.NIOClient;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.util.AES;
import com.comsince.github.push.util.Base64;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author comsicne
 *         Copyright (c) [2019] [Meizu.inc]
 * @Time 19-2-21 下午2:48
 **/
public class PushNIOClientStarter {
    static Log log = LoggerFactory.getLogger(PushNIOClientStarter.class);
    static ExecutorService executorService = Executors.newFixedThreadPool(5);


    public static void main(String[] args){
//        executorService.execute(new Runnable() {
//            @Override
//            public void run() {
//                for(int i=0;i<10000;i++){
//                   sendConnect("localhost",6789);
//                }
//            }
//        });
        byte[] aes_key= {0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x78,0x79,0x7A,0x7B,0x7C,0x7D,0x7E,0x7F};
        System.out.println(Base64.encode(aes_key));

    }

    public static void sendConnect(String host, int port){
        final NIOClient nioClient = new NIOClient(host,port);
        nioClient.connect();
    }

}
