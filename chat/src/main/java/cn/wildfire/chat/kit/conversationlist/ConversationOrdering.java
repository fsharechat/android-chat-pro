package cn.wildfire.chat.kit.conversationlist;

import java.util.List;

import cn.wildfirechat.model.ConversationInfo;

/**
 * 会话列表的插入位置计算。从 ConversationListAdapter 抽出来，纯逻辑、可单测。
 */
public final class ConversationOrdering {

    private ConversationOrdering() {
    }

    public static int targetPosition(List<ConversationInfo> conversationInfos,
                                     ConversationInfo conversationInfo,
                                     int currentPosition) {
        if (conversationInfos == null) {
            return -1;
        }
        if (conversationInfos.isEmpty()) {
            return 0;
        }
        // isTop
        ConversationInfo info;
        int position = 0;
        if (conversationInfo.isTop) {
            for (int i = 0; i < conversationInfos.size(); i++) {
                info = conversationInfos.get(i);
                if (info.isTop) {
                    if (conversationInfo.timestamp >= info.timestamp) {
                        position = i;
                        break;
                    }
                } else {
                    position = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < conversationInfos.size(); i++) {
                info = conversationInfos.get(i);
                if (info.isTop) {
                    // do nothing, just continue
                } else {
                    if (conversationInfo.timestamp >= info.timestamp) {
                        position = i;
                        break;
                    }
                }
            }
        }
        if (currentPosition >= 0 && position > currentPosition) {
            position = position - 1;
        }

        // 计算新加的会话位置。
        // 上面那两个分支默认 position = 0，新会话若比所有现存会话都旧就会被错插到列表头部，
        // 所以这里以 size() 为默认值重新定位一次。
        // 注意必须同样区分 isTop：早先这里只复制了非置顶那一支，导致首次登录
        // （每个会话都是新插入）时整个列表退化成纯时间戳排序，置顶完全失效。
        if (currentPosition == -1) {
            position = conversationInfos.size();
            for (int i = 0; i < conversationInfos.size(); i++) {
                info = conversationInfos.get(i);
                if (conversationInfo.isTop) {
                    // 置顶会话只在置顶区内按时间倒序排；遇到第一个非置顶项就插在它前面
                    if (!info.isTop || conversationInfo.timestamp >= info.timestamp) {
                        position = i;
                        break;
                    }
                } else {
                    // 非置顶会话必须排在所有置顶项之后
                    if (!info.isTop && conversationInfo.timestamp >= info.timestamp) {
                        position = i;
                        break;
                    }
                }
            }
        }
        return position;
    }
}
