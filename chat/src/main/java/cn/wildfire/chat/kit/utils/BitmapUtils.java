package cn.wildfire.chat.kit.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

public class BitmapUtils {
    public static Bitmap compressImage(Bitmap originalBitmap, int maxWidth, int maxHeight, int quality) {
        // 获取原始图片的宽度和高度
        int originalWidth = originalBitmap.getWidth();
        int originalHeight = originalBitmap.getHeight();

        // 计算宽高比例
        float aspectRatio = (float) originalWidth / originalHeight;

        // 计算新的宽度和高度，保持比例
        int newWidth = maxWidth;
        int newHeight = (int) (newWidth / aspectRatio);

        // 如果新的高度大于最大高度，则调整为最大高度
        if (newHeight > maxHeight) {
            newHeight = maxHeight;
            newWidth = (int) (newHeight * aspectRatio);
        }

        // 根据计算出来的宽高来缩放图片
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true);

        // 压缩图片到指定质量
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        scaledBitmap.compress(Bitmap.CompressFormat.PNG, quality, byteArrayOutputStream);

        // 获取压缩后的字节数据
        byte[] byteArray = byteArrayOutputStream.toByteArray();

        // 将压缩后的字节数据解码回 Bitmap
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
    }

}
