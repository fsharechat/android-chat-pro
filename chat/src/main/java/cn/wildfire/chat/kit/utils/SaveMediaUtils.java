package cn.wildfire.chat.kit.utils;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SaveMediaUtils {

    private static final OkHttpClient client = new OkHttpClient();

    /**
     * 异步下载并保存图片或视频到系统相册
     * @param context 上下文
     * @param url 文件 URL
     * @param isVideo 是否为视频
     */
    public static void saveFileToGalleryAsync(Context context, String url, boolean isVideo) {
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                e.printStackTrace();
                showToast(context, "下载失败：" + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    showToast(context, "下载失败：" + response.message());
                    return;
                }

                try (InputStream inputStream = response.body().byteStream()) {

                    // 1. 获取文件扩展名（如 .jpg / .mp4）
                    String ext = getExtensionFromUrl(url, isVideo);

                    // 2. 构造文件名和 MIME 类型
                    String fileName = (isVideo ? "VID_" : "IMG_") + System.currentTimeMillis() + ext;
                    String mimeType = getMimeTypeFromExtension(ext, isVideo);

                    String relativePath = (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                            + "/" + (isVideo ? "FShareChatVideos" : "FShareChatImages");

                    OutputStream outStream;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                        Uri uri = context.getContentResolver().insert(
                                isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI :
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values);

                        if (uri == null) {
                            showToast(context, "保存失败：无法创建媒体 URI");
                            return;
                        }

                        outStream = context.getContentResolver().openOutputStream(uri);
                        if (outStream == null) {
                            showToast(context, "保存失败：无法打开输出流");
                            return;
                        }

                        copyStream(inputStream, outStream);
                        outStream.close();

                        values.clear();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(uri, values, null, null);

                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(
                                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                                isVideo ? "FShareChatVideos" : "FShareChatImages");

                        if (!dir.exists()) dir.mkdirs();

                        File file = new File(dir, fileName);
                        outStream = new FileOutputStream(file);
                        copyStream(inputStream, outStream);
                        outStream.close();

                        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
                    }

                    showToast(context, "文件已保存到相册");

                } catch (Exception e) {
                    e.printStackTrace();
                    showToast(context, "保存失败：" + e.getMessage());
                }
            }
        });
    }

    // 从 URL 中提取文件扩展名
    private static String getExtensionFromUrl(String url, boolean isVideo) {
        try {
            String path = Uri.parse(url).getPath();
            if (path != null && path.contains(".")) {
                return path.substring(path.lastIndexOf("."));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return isVideo ? ".mp4" : ".jpg"; // fallback
    }

    // 根据扩展名推断 MIME 类型
    private static String getMimeTypeFromExtension(String ext, boolean isVideo) {
        ext = ext.toLowerCase();
        if (ext.endsWith(".png")) return "image/png";
        if (ext.endsWith(".webp")) return "image/webp";
        if (ext.endsWith(".gif")) return "image/gif";
        if (ext.endsWith(".bmp")) return "image/bmp";
        if (ext.endsWith(".mp4")) return "video/mp4";
        if (ext.endsWith(".mov")) return "video/quicktime";
        if (ext.endsWith(".avi")) return "video/x-msvideo";
        if (ext.endsWith(".mkv")) return "video/x-matroska";
        return isVideo ? "video/*" : "image/*";
    }

    // 拷贝输入流到输出流
    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        in.close();
    }

    // 在主线程显示 Toast
    private static void showToast(Context context, String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        );
    }
}

