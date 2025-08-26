package com.comsince.github.logger;

/**
 * @Auther: comsince
 * @Date: 25-8-22 14:50
 * @Description:
 */
public class FactoryLogger implements Log{

    String loggerClassName;
    Log logger;

    public FactoryLogger(String loggerClassName, Log logger) {
        this.loggerClassName = loggerClassName;
        this.logger = logger;
    }

    @Override
    public void setTag(Class classTag) {

    }

    @Override
    public void i(String tag, String message) {
        logger.i(tag,message);
    }

    @Override
    public void i(String message) {
        i(loggerClassName,message);
    }

    @Override
    public void e(String message, Throwable e) {
        e(loggerClassName,message,null);
    }

    @Override
    public void e(String tag, String message, Exception e) {
        logger.e(tag,message,e);
    }

    @Override
    public void e(String message) {
        e(loggerClassName,message,null);
    }
}
