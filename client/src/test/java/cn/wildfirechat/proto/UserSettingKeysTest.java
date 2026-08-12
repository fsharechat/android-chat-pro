package cn.wildfirechat.proto;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UserSettingKeysTest {

    @Test
    public void conversationKeyUsesHyphenSeparatedTypeLineTarget() {
        // 必须与服务端 MemoryMessagesStore.getUserConversationSlient 的格式完全一致
        assertEquals("1-0-group123", UserSettingKeys.conversationKey(1, 0, "group123"));
        assertEquals("0-0-user456", UserSettingKeys.conversationKey(0, 0, "user456"));
    }

    @Test
    public void conversationKeyKeepsNonZeroLine() {
        assertEquals("1-3-group123", UserSettingKeys.conversationKey(1, 3, "group123"));
    }

    @Test
    public void onlyTopAndSilentAreConversationScopes() {
        assertTrue(UserSettingKeys.isConversationScope(3));   // ConversationTop
        assertTrue(UserSettingKeys.isConversationScope(1));   // ConversationSilent
        assertFalse(UserSettingKeys.isConversationScope(6));  // FavoriteGroup，key 是群 id
        assertFalse(UserSettingKeys.isConversationScope(2));  // GlobalSilent，key 是空串
    }

    @Test
    public void parseConversationKeyRoundTrips() {
        String[] parts = UserSettingKeys.parseConversationKey("1-0-group123");

        assertArrayEquals(new String[]{"1", "0", "group123"}, parts);
    }

    @Test
    public void parseConversationKeyReturnsNullForGroupIdKey() {
        // scope 6 的 key 是群 id，没有分隔符 —— 旧代码在这里 parseInt 崩溃
        assertNull(UserSettingKeys.parseConversationKey("group123"));
    }

    @Test
    public void parseConversationKeyReturnsNullForEmptyOrNull() {
        assertNull(UserSettingKeys.parseConversationKey(""));
        assertNull(UserSettingKeys.parseConversationKey(null));
    }

    @Test
    public void parseConversationKeyReturnsNullWhenTypeOrLineNotNumeric() {
        assertNull(UserSettingKeys.parseConversationKey("a-0-target"));
        assertNull(UserSettingKeys.parseConversationKey("1-b-target"));
    }

    @Test
    public void parseConversationKeyKeepsHyphensInsideTarget() {
        // 群 id 本身可能含连字符，只能按前两个分隔符切
        String[] parts = UserSettingKeys.parseConversationKey("1-0-abc-def-ghi");

        assertArrayEquals(new String[]{"1", "0", "abc-def-ghi"}, parts);
    }
}
