package cn.wildfirechat.proto;

import cn.wildfirechat.remote.UserSettingScope;

/**
 * 用户设置 key 的唯一定义处。
 * 会话类 setting 的 key 格式为 type-line-target，必须与服务端
 * MemoryMessagesStore.getUserConversationSlient() 的拼法完全一致，
 * 否则上传的设置服务端读不到。
 */
public final class UserSettingKeys {

    private static final String SPLIT = "-";

    private UserSettingKeys() {
    }

    public static String conversationKey(int conversationType, int line, String target) {
        return conversationType + SPLIT + line + SPLIT + target;
    }

    /**
     * 只有置顶和会话免打扰的 key 是会话三元组。
     * scope 6 的 key 是群 id、scope 2 的 key 是空串，都不能按会话 key 解析。
     */
    public static boolean isConversationScope(int scope) {
        return scope == UserSettingScope.ConversationTop || scope == UserSettingScope.ConversationSilent;
    }

    /**
     * 解析会话 key，返回 {type, line, target}；不是合法会话 key 时返回 null。
     * target 里可能含连字符（群 id），因此只按前两个分隔符切分。
     */
    public static String[] parseConversationKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String[] parts = key.split(SPLIT, 3);
        if (parts.length != 3) {
            return null;
        }
        if (!isNumeric(parts[0]) || !isNumeric(parts[1])) {
            return null;
        }
        return parts;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
