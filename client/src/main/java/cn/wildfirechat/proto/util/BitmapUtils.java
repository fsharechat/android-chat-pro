package cn.wildfirechat.proto.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

public class BitmapUtils {
    /**
     * 缩略图最长边上限，与本端发送时生成的缩略图尺寸（最长 510）保持一致。
     * 其他端（如 iOS）发过来的缩略图可能是原图级别的尺寸，必须限制后再解码。
     */
    public static final int MAX_THUMBNAIL_EDGE = 512;

    /**
     * 按最长边限制解码缩略图。全尺寸解码出来的 Bitmap 不仅占内存，还会大到写不进 Parcel，
     * 转发这类需要跨 Activity 传递消息的操作会直接失败。
     */
    public static Bitmap decodeBoundedBitmap(byte[] data, int maxEdge) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);

        int sampleSize = 1;
        int edge = Math.max(options.outWidth, options.outHeight);
        while (edge / sampleSize > maxEdge) {
            sampleSize *= 2;
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /**
     * 缩略图压成 JPEG 字节，用于写入 Parcel。直接 writeParcelable(Bitmap) 写的是解压后的像素，
     * 大图会抛 "Could not copy bitmap to parcel blob."。
     */
    public static byte[] compressToJpeg(Bitmap bitmap, int quality) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        return baos.toByteArray();
    }

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
