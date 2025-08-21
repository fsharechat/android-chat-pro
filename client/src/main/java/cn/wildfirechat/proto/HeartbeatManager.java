package cn.wildfirechat.proto;

import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;

import java.util.Random;

public class HeartbeatManager {
    private Log logger = LoggerFactory.getLogger(HeartbeatManager.class);

    private final long MIN_HEARTBEAT_INTERVAL =  30 * 1000;
    private final long MAX_HEARTBEAT_INTERVAL = 6 * 60 * 1000;
    private final long MIDDLE_HEARTBEAT_INTERVAL = 1 * 60 * 1000;
    private volatile long currentMaxHeartbeatInterval = MIN_HEARTBEAT_INTERVAL;
    private volatile long currentHeartbeatInterval = MIN_HEARTBEAT_INTERVAL;
    private volatile long currentScheduleTime = 0;
    private volatile long currentSendSuccessTime = 0;
    private volatile long currentExceptionTime = 0;
    private volatile boolean upSearchingMaxInterval = true;
    //连续心跳成功,中间任何一次间断进行重置
    private int currentHeartbeatSuccessNum = 0;
    private boolean disableUpSearchingMaxInterval = false;
    //当前心跳间隔成功次数
    private int currentHeartbeatIntervalSuccessNum = 0;
    private long tempHeartbeatInterval = 0;
    private Random heartBeatRandom = new Random();

    public long currentHeartInterval(){
        if(tempHeartbeatInterval > 0 ){
            return tempHeartbeatInterval;
        }
        return currentHeartbeatInterval;
    }

    /**
     * 这里不用考虑网络异常断开情况
     * 心跳间隔的选择只在计算中间间隔时间内是否能够继续发送数据，如果网络异常断开，心跳定时器会被清除，重新进行新的一轮计算
     * 计算的核心是步进计算与步减计算
     *
     * */
    public void reportHeartbeatExceptionTime(long exceptionTime){
        this.currentExceptionTime = exceptionTime;
        if(currentExceptionTime != 0 && currentScheduleTime != 0 && currentExceptionTime > currentScheduleTime){
            long interval = currentExceptionTime - currentScheduleTime;
            //比较当前时间间隔与最大心跳间隔的差值，即步进增加值
            long diff = interval - currentMaxHeartbeatInterval;
            logger.i("reportHeartbeatExceptionTime diff "+diff);
            long absDiff = Math.abs(diff);
            if(absDiff >= 0){
                //心跳失败，重置成功次数
                currentHeartbeatSuccessNum = 0;
                tempHeartbeatInterval = 0;
                upSearchingMaxInterval = false;
                if(absDiff < 60 * 1000){
                    //等值试探失败
                    currentMaxHeartbeatInterval = interval - 30 * 1000;
                } else if (absDiff < 120 * 1000){
                    //步进试探失败
                    currentMaxHeartbeatInterval = interval - absDiff / 2;
                } else if( absDiff < 6 * 60 * 1000){
                    currentMaxHeartbeatInterval = currentMaxHeartbeatInterval - 1 * 60 * 1000;
                } else {
                    disableUpSearchingMaxInterval = true;
                    currentMaxHeartbeatInterval = currentMaxHeartbeatInterval - 2 * 60 * 1000;
                    logger.i("disable UpSearchingMaxInterval currentMaxHeartbeatInterval=" + currentMaxHeartbeatInterval);

                }
            }
        }
//        if(currentMaxHeartbeatInterval < MIDDLE_HEARTBEAT_INTERVAL){
//            upSearchingMaxInterval = true;
//        }
        if(currentMaxHeartbeatInterval < 15 * 1000){
            currentMaxHeartbeatInterval = MIN_HEARTBEAT_INTERVAL;
        }
        if(currentMaxHeartbeatInterval > MAX_HEARTBEAT_INTERVAL){
            currentMaxHeartbeatInterval = MAX_HEARTBEAT_INTERVAL;
        }
        currentHeartbeatInterval = currentMaxHeartbeatInterval;
        logger.i("reportHeartbeatExceptionTime current heart interval "+currentHeartbeatInterval+" max interval "+currentMaxHeartbeatInterval +" upSearchingMaxInterval "+upSearchingMaxInterval);
    }

    /**
     * 上报当前定时心跳的时间
     * @param currentTime 当前发起定时的时间
     * */
    public void reportHeartbeatScheduleTime(long currentTime){
        this.currentScheduleTime = currentTime;
    }

    public void reportHeartbeatSendSuccessTime(long successTime){
        this.currentSendSuccessTime = successTime;
        recalculateMaxHeartbeatInterval();
    }

    public synchronized long nextHeartbeatInterval(){
        long maxSuccessTime = currentHeartbeatInterval / (30 * 1000);
        logger.i("nextHeartbeatInterval currentHeartbeatInterval= "+currentHeartbeatInterval+" maxSuccessTime="+maxSuccessTime +" currentHeartbeatIntervalSuccessNum="+currentHeartbeatIntervalSuccessNum);
        if(upSearchingMaxInterval && currentHeartbeatIntervalSuccessNum > maxSuccessTime){
            currentHeartbeatInterval += 30 * 1000;
            currentHeartbeatIntervalSuccessNum = 0;
            tempHeartbeatInterval = 0;
        } else {
            if(currentHeartbeatInterval > MIDDLE_HEARTBEAT_INTERVAL){
                int randomInt = heartBeatRandom.nextInt((int)maxSuccessTime);
                logger.i("nextHeartbeatInterval randomInt="+randomInt);
                if(randomInt % 2 == 1){
                    tempHeartbeatInterval = currentHeartbeatInterval - randomInt * 30 * 1000;
                    if(tempHeartbeatInterval < 0){
                        tempHeartbeatInterval = MIN_HEARTBEAT_INTERVAL;
                    }
                    logger.i("nextHeartbeatInterval tempHeartbeatInterval="+tempHeartbeatInterval);
                    return tempHeartbeatInterval;
                }
            }
            tempHeartbeatInterval = 0;
            currentHeartbeatIntervalSuccessNum++;
            currentHeartbeatInterval = currentMaxHeartbeatInterval;
        }
        if(currentHeartbeatInterval >= MAX_HEARTBEAT_INTERVAL){
            int randomInt = heartBeatRandom.nextInt(12);
            tempHeartbeatInterval = MAX_HEARTBEAT_INTERVAL - randomInt * 30 * 1000;
            logger.i("nextHeartbeatInterval reach max interval so random interval="+tempHeartbeatInterval);
            return tempHeartbeatInterval;
        }
        logger.i("nextHeartbeatInterval currentHeartbeatInterval="+currentHeartbeatInterval+" currentMaxHeartbeatInterval="+currentMaxHeartbeatInterval+" upSearchingMaxInterval "+upSearchingMaxInterval);
        return currentHeartbeatInterval;
    }

    /**
     * 重新计算心跳间隔
     * */
    private void recalculateMaxHeartbeatInterval(){
        if(currentSendSuccessTime != 0 && currentScheduleTime != 0){
            long interval = currentSendSuccessTime - currentScheduleTime;
            long diff = interval - currentMaxHeartbeatInterval;
            logger.i("recalculateMaxHeartbeatInterval diff:"+diff+" upSearchingMaxInterval: "+upSearchingMaxInterval);
            if(diff > 0 && diff < 90 * 1000){
                // 只有在合理间隔的增加值才算是有效的心跳间隔，心跳步进策略最大每次增加60s
                if(upSearchingMaxInterval){
                    currentMaxHeartbeatInterval = interval;
                    if(interval > MAX_HEARTBEAT_INTERVAL){
                        currentMaxHeartbeatInterval = MAX_HEARTBEAT_INTERVAL;
                    }
                }
                //防止tryheartbeat导致的无效次数
                if(!upSearchingMaxInterval){
                    currentHeartbeatSuccessNum++;
                    logger.i("recalculateMaxHeartbeatInterval currentHeartbeatSuccessNum="+currentHeartbeatSuccessNum+" disableUpSearchingMaxInterval="+disableUpSearchingMaxInterval);
                    if(currentHeartbeatSuccessNum > 3 && !disableUpSearchingMaxInterval){
                        upSearchingMaxInterval = true;
                        long diffMaxHeartbeatTime = MAX_HEARTBEAT_INTERVAL - currentMaxHeartbeatInterval;
                        if(diffMaxHeartbeatTime >= 6 * 60 * 1000){
                            currentMaxHeartbeatInterval = currentMaxHeartbeatInterval + 30 * 1000;
                            currentHeartbeatSuccessNum = 0;
                            logger.i("recalculateMaxHeartbeatInterval add max Heartbeat interval currentMaxHeartbeatInterval "+currentMaxHeartbeatInterval);
                        }
                    }
                }
                if(!upSearchingMaxInterval && disableUpSearchingMaxInterval){
                    if(currentMaxHeartbeatInterval < MIDDLE_HEARTBEAT_INTERVAL){
                        logger.i("enable disableUpSearchingMaxInterval");
                        disableUpSearchingMaxInterval = false;
                    }
                }
            }

            logger.i("recalculateMaxHeartbeatInterval interval="+interval+" currentHeartbeatInterval="+currentHeartbeatInterval+" currentMaxHeartbeatInterval="+currentMaxHeartbeatInterval);
        }
    }

}
