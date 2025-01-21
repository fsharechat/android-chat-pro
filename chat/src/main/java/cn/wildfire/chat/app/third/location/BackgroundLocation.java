package cn.wildfire.chat.app.third.location;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import com.tencent.map.geolocation.TencentLocationListener;
import com.tencent.map.geolocation.TencentLocationManager;
import com.tencent.map.geolocation.TencentLocationRequest;
import cn.wildfirechat.chat.R;


public class BackgroundLocation {

    private TencentLocationManager mLocationManager;
    private TencentLocationRequest mLocationRequest;
    private Context context;
    private NotificationManager notificationManager;
    private boolean isCreateChannel = false;

    public BackgroundLocation(Context context){
        this.context = context;
        mLocationManager = TencentLocationManager.getInstance(context);
        mLocationRequest = TencentLocationRequest.create();
    }

    public void requestLocationUpdate(TencentLocationListener tencentLocationListener){
        mLocationManager.enableForegroundLocation(1001, buildNotification());
        mLocationManager.requestLocationUpdates(mLocationRequest, tencentLocationListener, context.getMainLooper());
    }

    public void removeLocationUpdate(TencentLocationListener tencentLocationListener){
        mLocationManager.removeUpdates(tencentLocationListener);
        mLocationManager.disableForegroundLocation(true);
    }

    private Notification buildNotification() {
        Notification.Builder builder = null;
        Notification notification = null;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            //Android O上对Notification进行了修改，如果设置的targetSDKVersion>=26建议使用此种方式创建通知栏
            if (notificationManager == null) {
                notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            }
            String channelId = "fsharechat_notification";
            if (!isCreateChannel) {
                NotificationChannel notificationChannel = new NotificationChannel(channelId,
                    "fsharechat location", NotificationManager.IMPORTANCE_DEFAULT);
                notificationChannel.enableLights(true);//是否在桌面icon右上角展示小圆点
                notificationChannel.setLightColor(Color.BLUE); //小圆点颜色
                notificationChannel.setShowBadge(true); //是否在久按桌面图标时显示此渠道的通知
                notificationManager.createNotificationChannel(notificationChannel);
                isCreateChannel = true;
            }
            builder = new Notification.Builder(context.getApplicationContext(), channelId);
        } else {
            builder = new Notification.Builder(context.getApplicationContext());
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("飞享IM")
                .setContentText("正在进行后台定位")
                .setWhen(System.currentTimeMillis());

        if (android.os.Build.VERSION.SDK_INT >= 16) {
            notification = builder.build();
        } else {
            notification = builder.getNotification();
        }
        return notification;
    }



}
