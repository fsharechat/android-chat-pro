package cn.wildfire.chat.kit.conversation.message.viewholder;

import java.util.List;

import cn.wildfire.chat.kit.widget.dialog.FshareContextMenu;

/**
 * 支持长按选字的消息 ViewHolder：长按时由它进入选字状态并弹出菜单，代替普通的
 * {@link FshareContextMenu}。
 */
public interface SelectableTextViewHolder {
    /**
     * 全选文字、显示选区手柄并弹出菜单。
     *
     * @return false 表示当前不能选字（如流式输出中），调用方按普通菜单处理
     */
    boolean showSelectionMenu(List<FshareContextMenu.Item> items, FshareContextMenu.Callback callback);
}
