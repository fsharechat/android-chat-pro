package cn.wildfirechat.proto;
import android.content.Context;
import com.comsince.github.logger.Log;

import cn.wildfirechat.log.Logger;

public class AndroidLogger implements Log{

    private Class loggerClass;

    public AndroidLogger(Context context){
        Logger.init(context);
    }

    @Override
    public void setTag(Class tagClass) {

    }

    @Override
    public void i(String s, String s1) {
        Logger.i(s,s1);
    }

    @Override
    public void i(String s) {

    }

    @Override
    public void e(String s, Throwable e) {

    }

    @Override
    public void e(String s, String s1, Exception e) {
        Logger.e(s,s1,e);
    }

    @Override
    public void e(String e) {

    }
}
