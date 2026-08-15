package cn.wildfirechat.message;

import android.graphics.Bitmap;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;

import cn.wildfirechat.proto.util.BitmapUtils;
import cn.wildfirechat.message.core.ContentTag;
import cn.wildfirechat.message.core.MessageContentType;
import cn.wildfirechat.message.core.MessagePayload;
import cn.wildfirechat.message.core.PersistFlag;

/**
 * Created by heavyrain lee on 2017/12/6.
 */

@ContentTag(type = MessageContentType.ContentType_Image, flag = PersistFlag.Persist_And_Count)
public class ImageMessageContent extends MediaMessageContent {
    private Bitmap thumbnail;

    public ImageMessageContent() {
    }

    public ImageMessageContent(String path) {
        this.localPath = path;
        mediaType = MessageContentMediaType.IMAGE;
    }

    public Bitmap getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(Bitmap thumbnail) {
        this.thumbnail = thumbnail;
    }


    @Override
    public MessagePayload encode() {
        MessagePayload payload = super.encode();
        payload.searchableContent = "[图片]";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 75, baos);
        payload.binaryContent = baos.toByteArray();
        return payload;
    }


    @Override
    public void decode(MessagePayload payload) {
        super.decode(payload);
        if (payload.binaryContent != null) {
            thumbnail = BitmapUtils.decodeBoundedBitmap(payload.binaryContent, BitmapUtils.MAX_THUMBNAIL_EDGE);
        }
    }

    @Override
    public String digest(Message message) {
        return "[图片]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // 缩略图以压缩后的字节传递，直接写 Bitmap 会因为像素数据超出 binder 缓冲而失败
        dest.writeByteArray(BitmapUtils.compressToJpeg(this.thumbnail, 75));
        dest.writeString(this.localPath);
        dest.writeString(this.remoteUrl);
        dest.writeInt(this.mediaType == null ? -1 : this.mediaType.ordinal());
        dest.writeInt(this.mentionedType);
        dest.writeStringList(this.mentionedTargets);
    }

    protected ImageMessageContent(Parcel in) {
        this.thumbnail = BitmapUtils.decodeBoundedBitmap(in.createByteArray(), BitmapUtils.MAX_THUMBNAIL_EDGE);
        this.localPath = in.readString();
        this.remoteUrl = in.readString();
        int tmpMediaType = in.readInt();
        this.mediaType = tmpMediaType == -1 ? null : MessageContentMediaType.values()[tmpMediaType];
        this.mentionedType = in.readInt();
        this.mentionedTargets = in.createStringArrayList();
    }

    public static final Creator<ImageMessageContent> CREATOR = new Creator<ImageMessageContent>() {
        @Override
        public ImageMessageContent createFromParcel(Parcel source) {
            return new ImageMessageContent(source);
        }

        @Override
        public ImageMessageContent[] newArray(int size) {
            return new ImageMessageContent[size];
        }
    };
}
