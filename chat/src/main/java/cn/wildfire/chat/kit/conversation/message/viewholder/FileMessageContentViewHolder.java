package cn.wildfire.chat.kit.conversation.message.viewholder;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;

import butterknife.Bind;
import butterknife.OnClick;
import cn.wildfire.chat.kit.annotation.EnableContextMenu;
import cn.wildfire.chat.kit.annotation.LayoutRes;
import cn.wildfire.chat.kit.annotation.MessageContentType;
import cn.wildfire.chat.kit.annotation.ReceiveLayoutRes;
import cn.wildfire.chat.kit.annotation.SendLayoutRes;
import cn.wildfire.chat.kit.conversation.message.model.UiMessage;
import cn.wildfire.chat.kit.third.utils.UIUtils;
import cn.wildfire.chat.kit.utils.FileUtils;
import cn.wildfire.chat.kit.widget.BubbleImageView;
import cn.wildfirechat.chat.R;
import cn.wildfirechat.message.FileMessageContent;
import cn.wildfirechat.message.core.MessageDirection;
import cn.wildfirechat.message.core.MessageStatus;

@MessageContentType(FileMessageContent.class)
@SendLayoutRes(resId = R.layout.conversation_item_file_send)
@ReceiveLayoutRes(resId = R.layout.conversation_item_file_receive)
@EnableContextMenu
public class FileMessageContentViewHolder extends MediaMessageContentViewHolder {

    @Bind(R.id.fileIconImageView)
    BubbleImageView imageView;
    @Bind(R.id.fileTypeTextView)
    TextView fileTypeTextView;
    @Bind(R.id.fileDownloadWaveView)
    cn.wildfire.chat.kit.widget.WaveProgressView fileDownloadWaveView;
    @Bind(R.id.fileNameTextView)
    TextView fileNameTextView;
    @Bind(R.id.fileSizeTextView)
    TextView fileSizeTextView;
    @Bind(R.id.fileStatusTextView)
    TextView fileStatusTextView;
    private FileMessageContent fileMessageContent;

    public FileMessageContentViewHolder(FragmentActivity context, RecyclerView.Adapter adapter, View itemView) {
        super(context, adapter, itemView);
    }

    @Override
    public void onBind(UiMessage message) {
        super.onBind(message);
        fileMessageContent = (FileMessageContent) message.message.content;
        imageView.setProgressVisible(false);
        imageView.showShadow(false);
        if (message.message.status == MessageStatus.Sending || message.isDownloading) {
            fileDownloadWaveView.setVisibility(View.VISIBLE);
            fileDownloadWaveView.setProgress(message.progress);
        } else {
            fileDownloadWaveView.setVisibility(View.GONE);
            fileDownloadWaveView.setProgress(0);
        }
//        Glide.with(context).load(R.mipmap.ic_file)
//                .apply(new RequestOptions().override(UIUtils.dip2Px(150), UIUtils.dip2Px(150)).centerCrop()).into(imageView);
        fileNameTextView.setText(fileMessageContent.getName());
        fileSizeTextView.setText(FileUtils.getReadableFileSize(fileMessageContent.getSize()));
        setDownloadStatus(message);

        String extension = fileExtension(fileMessageContent.getName());
        fileTypeTextView.setText(TextUtils.isEmpty(extension) ? "FILE" : extension);
        ((GradientDrawable) fileTypeTextView.getBackground().mutate()).setColor(fileTypeColor(extension));
    }

    /**
     * 参考 iOS 的做法，把下载状态放到文件大小右侧文字里：未下载 / 下载中 xx% / 已下载，
     * 气泡右侧那个小转圈就不再显示了。
     */
    private void setDownloadStatus(UiMessage message) {
        if (message.message.direction != MessageDirection.Receive) {
            fileStatusTextView.setVisibility(View.GONE);
            return;
        }
        // 下载进度已经由这行文字表达，去掉气泡右侧的小圆圈
        progressBar.setVisibility(View.GONE);

        fileStatusTextView.setVisibility(View.VISIBLE);
        if (message.isDownloading) {
            fileStatusTextView.setText(context.getString(R.string.file_downloading_percent, message.progress));
            return;
        }
        File file = conversationViewModel.mediaMessageContentFile(message);
        boolean downloaded = file != null && file.exists();
        fileStatusTextView.setText(downloaded ? R.string.file_downloaded : R.string.file_not_downloaded);
    }

    private static String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1).toUpperCase();
        return extension.length() > 4 ? extension.substring(0, 4) : extension;
    }

    private static int fileTypeColor(String extension) {
        switch (extension) {
            case "PNG":
            case "JPG":
            case "JPEG":
            case "GIF":
            case "WEBP":
            case "BMP":
            case "HEIC":
                return Color.parseColor("#E4638E");
            case "PDF":
                return Color.parseColor("#F0524C");
            case "DOC":
            case "DOCX":
                return Color.parseColor("#3E7BFA");
            case "XLS":
            case "XLSX":
            case "CSV":
                return Color.parseColor("#34A853");
            case "PPT":
            case "PPTX":
                return Color.parseColor("#FF8A3C");
            case "ZIP":
            case "RAR":
            case "7Z":
            case "TAR":
            case "GZ":
                return Color.parseColor("#9B59D0");
            case "MP3":
            case "WAV":
            case "FLAC":
            case "M4A":
            case "AAC":
            case "OGG":
                return Color.parseColor("#21B5A0");
            case "MP4":
            case "MOV":
            case "AVI":
            case "MKV":
            case "FLV":
            case "WMV":
                return Color.parseColor("#6A5AE0");
            case "APK":
                return Color.parseColor("#3DDC84");
            default:
                return Color.parseColor("#64748B");
        }
    }

    @OnClick(R.id.fileNameTextView)
    public void onClickName(View view){
        boolean fileNameSelected = view.isSelected();
        fileNameTextView.setSelected(!fileNameSelected);
    }

    @OnClick(R.id.fileIconImageView)
    public void onClick(View view) {
        if (message.isDownloading) {
            return;
        }
        File file = conversationViewModel.mediaMessageContentFile(message);
        if (file == null) {
            return;
        }

        if (file.exists()) {
            Intent intent = FileUtils.getViewIntent(context, file);
            ComponentName cn = intent.resolveActivity(context.getPackageManager());
            if (cn == null) {
                Toast.makeText(context, "找不到能打开此文件的应用", Toast.LENGTH_SHORT).show();
                return;
            }
            context.startActivity(intent);
        } else {
            conversationViewModel.downloadMedia(message, file);
        }
    }
}
