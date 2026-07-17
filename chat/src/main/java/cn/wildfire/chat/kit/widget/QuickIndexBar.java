package cn.wildfire.chat.kit.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import cn.wildfirechat.chat.R;

/**
 * 联系人快速索引条：字母以固定行高紧凑排列并整体垂直居中（iOS 风格），
 * 触摸无背景，当前触摸字母高亮。
 */
public class QuickIndexBar extends View {

    private Paint mPaint;
    private float mTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 11, getResources().getDisplayMetrics());
    private float mMaxCellHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, getResources().getDisplayMetrics());
    private static final String[] LETTERS = new String[]{
            "↑", "☆", "A", "B", "C", "D", "E", "F", "G", "H",
            "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R",
            "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
    };
    private int mCellWidth;
    private float mCellHeight;
    private float mStartY;
    private int mTouchIndex = -1;//用于记录当前触摸的索引值

    //暴露一个字母的监听
    public interface OnLetterUpdateListener {
        void onLetterUpdate(String letter);

        void onLetterCancel();
    }

    private OnLetterUpdateListener mListener;

    public OnLetterUpdateListener getOnLetterUpdateListener() {
        return mListener;
    }

    public void setOnLetterUpdateListener(OnLetterUpdateListener listener) {
        mListener = listener;
    }

    public QuickIndexBar(Context context) {
        this(context, null);
    }

    public QuickIndexBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickIndexBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setTextSize(mTextSize);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        mCellWidth = getMeasuredWidth();
        //固定紧凑行高，放不下时按可用高度均分；整体垂直居中
        mCellHeight = Math.min(mMaxCellHeight, getMeasuredHeight() * 1.0f / LETTERS.length);
        mStartY = (getMeasuredHeight() - mCellHeight * LETTERS.length) / 2.0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (int i = 0; i < LETTERS.length; i++) {
            String text = LETTERS[i];
            //x坐标为单元格宽度的一半 减去 文字宽度的一半
            int x = (int) (mCellWidth / 2.0f - mPaint.measureText(text) / 2.0f);

            Rect bounds = new Rect();
            mPaint.getTextBounds(text, 0, text.length(), bounds);
            int textHeight = bounds.height();

            //y坐标 = 居中偏移 + 单元格高度的一半 + 文字高度的一半 + 前面单元格的累计高度
            int y = (int) (mStartY + mCellHeight / 2.0f + textHeight / 2.0f + i * mCellHeight);

            //当前触摸字母高亮
            mPaint.setColor(getResources().getColor(mTouchIndex == i ? R.color.cobalt : R.color.side_bar));
            canvas.drawText(text, x, y, mPaint);
        }
    }

    private int letterIndexAt(float y) {
        int index = (int) ((y - mStartY) / mCellHeight);
        return (index >= 0 && index < LETTERS.length) ? index : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                int index = letterIndexAt(event.getY());
                if (index != -1 && index != mTouchIndex) {
                    if (mListener != null) {
                        mListener.onLetterUpdate(LETTERS[index]);
                    }
                    mTouchIndex = index;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTouchIndex = -1;
                if (mListener != null) {
                    mListener.onLetterCancel();
                }
                invalidate();
                break;
            default:
                break;
        }
        return true;
    }
}
