package cn.wildfire.chat.kit.conversationlist;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.wildfirechat.model.ConversationInfo;

import static org.junit.Assert.assertEquals;

/**
 * 会话列表排序：置顶会话整体排在非置顶之前，两个区内各自按 timestamp 倒序。
 *
 * 回归背景：首次登录时每个会话都是新插入（currentPosition == -1），
 * 而「新加会话」那条分支曾经完全不看 isTop，导致整个列表退化成纯时间戳排序、置顶失效。
 */
public class ConversationOrderingTest {

    private ConversationInfo conv(boolean isTop, long timestamp) {
        ConversationInfo info = new ConversationInfo();
        info.isTop = isTop;
        info.timestamp = timestamp;
        return info;
    }

    private List<ConversationInfo> list(ConversationInfo... infos) {
        return new ArrayList<>(Arrays.asList(infos));
    }

    @Test
    public void newTopConversationGoesAboveEveryNonTopEvenWhenOlder() {
        // 这就是线上现象：新插入的置顶会话因为时间戳更旧，被排到了非置顶会话下面
        List<ConversationInfo> existing = list(conv(true, 100L), conv(false, 90L));

        int position = ConversationOrdering.targetPosition(existing, conv(true, 80L), -1);

        assertEquals(1, position);
    }

    @Test
    public void newTopConversationGoesToHeadWhenListHasOnlyNonTop() {
        List<ConversationInfo> existing = list(conv(false, 100L), conv(false, 50L));

        int position = ConversationOrdering.targetPosition(existing, conv(true, 10L), -1);

        assertEquals(0, position);
    }

    @Test
    public void newTopConversationSortsByTimestampAmongTops() {
        List<ConversationInfo> existing = list(conv(true, 100L), conv(true, 50L), conv(false, 90L));

        assertEquals(0, ConversationOrdering.targetPosition(existing, conv(true, 150L), -1));
        assertEquals(1, ConversationOrdering.targetPosition(existing, conv(true, 70L), -1));
    }

    @Test
    public void newNonTopConversationNeverJumpsAboveTops() {
        // 即使时间戳最新，非置顶也必须排在所有置顶之后
        List<ConversationInfo> existing = list(conv(true, 1L), conv(false, 100L));

        int position = ConversationOrdering.targetPosition(existing, conv(false, 999L), -1);

        assertEquals(1, position);
    }

    @Test
    public void newOldestNonTopConversationIsAppendedNotPrepended() {
        // 这个块最初就是为了修「新会话被插到 index 0」而加的，不能改回去
        List<ConversationInfo> existing = list(conv(false, 100L), conv(false, 50L));

        int position = ConversationOrdering.targetPosition(existing, conv(false, 10L), -1);

        assertEquals(2, position);
    }

    @Test
    public void newTopConversationIsAppendedToTopBlockWhenOldestTop() {
        List<ConversationInfo> existing = list(conv(true, 100L), conv(true, 50L));

        int position = ConversationOrdering.targetPosition(existing, conv(true, 10L), -1);

        assertEquals(2, position);
    }

    @Test
    public void emptyListAlwaysReturnsZero() {
        assertEquals(0, ConversationOrdering.targetPosition(new ArrayList<ConversationInfo>(), conv(true, 1L), -1));
        assertEquals(0, ConversationOrdering.targetPosition(new ArrayList<ConversationInfo>(), conv(false, 1L), -1));
    }

    @Test
    public void nullListReturnsMinusOne() {
        assertEquals(-1, ConversationOrdering.targetPosition(null, conv(false, 1L), -1));
    }

    @Test
    public void existingConversationMovedUpKeepsIndexUnshifted() {
        // 更新既有会话时（currentPosition >= 0），目标位置在其之前则不做 -1 修正
        List<ConversationInfo> existing = list(conv(false, 100L), conv(false, 50L), conv(false, 10L));

        int position = ConversationOrdering.targetPosition(existing, conv(false, 200L), 2);

        assertEquals(0, position);
    }

    @Test
    public void existingConversationMovedDownShiftsBackByOne() {
        // 目标位置在其之后时要减 1，因为调用方会先把它从原位置移除再插入
        List<ConversationInfo> existing = list(conv(false, 100L), conv(false, 50L), conv(false, 10L));

        int position = ConversationOrdering.targetPosition(existing, conv(false, 20L), 0);

        assertEquals(1, position);
    }
}
