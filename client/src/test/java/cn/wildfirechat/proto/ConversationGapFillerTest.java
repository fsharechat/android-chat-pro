package cn.wildfirechat.proto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.wildfirechat.model.ProtoMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConversationGapFillerTest {

    private static final int SIGNAL_TYPE = 402;

    /** 用内存集合模拟本地库，只关心 uid。 */
    private static class FakeSink implements ConversationGapFiller.GapSink {
        final Set<Long> localUids = new HashSet<>();
        final Set<String> gaps = new HashSet<>();
        final List<Long> saved = new ArrayList<>();
        boolean savedAsTail;

        @Override
        public boolean hasOlderMessage(String target, long beforeUid) {
            for (long uid : localUids) {
                if (uid > 0 && uid < beforeUid) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void insertGap(String target, long beforeUid) {
            gaps.add(target + ":" + beforeUid);
        }

        @Override
        public void deleteGap(String target, long beforeUid) {
            gaps.remove(target + ":" + beforeUid);
        }

        @Override
        public long getLocalFloorUid(String target, long belowUid) {
            long floor = 0;
            for (long uid : localUids) {
                if (uid > 0 && uid < belowUid && uid > floor) {
                    floor = uid;
                }
            }
            return floor;
        }

        @Override
        public boolean hasMessage(long messageUid) {
            return localUids.contains(messageUid);
        }

        @Override
        public boolean canPersistent(int contentType) {
            return contentType != SIGNAL_TYPE;
        }

        @Override
        public ProtoMessage convert(WFCMessage.Message message) {
            ProtoMessage protoMessage = new ProtoMessage();
            protoMessage.setMessageUid(message.getMessageId());
            return protoMessage;
        }

        @Override
        public void saveHistory(String target, List<ProtoMessage> messages, boolean isTail) {
            for (ProtoMessage message : messages) {
                saved.add(message.getMessageUid());
                localUids.add(message.getMessageUid());
            }
            savedAsTail = isTail;
        }
    }

    private static WFCMessage.Message message(long uid, int type) {
        return WFCMessage.Message.newBuilder()
                .setConversation(WFCMessage.Conversation.newBuilder().setType(1).setTarget("g1").setLine(0))
                .setFromUser("other")
                .setContent(WFCMessage.MessageContent.newBuilder().setType(type))
                .setMessageId(uid)
                .build();
    }

    /** 服务端按 uid 降序返回 */
    private static WFCMessage.PullMessageResult page(long... uids) {
        WFCMessage.PullMessageResult.Builder builder = WFCMessage.PullMessageResult.newBuilder().setCurrent(0).setHead(0);
        for (long uid : uids) {
            builder.addMessage(message(uid, 1));
        }
        return builder.build();
    }

    private static WFCMessage.ConversationGap gap(String target, long beforeUid) {
        return WFCMessage.ConversationGap.newBuilder()
                .setConversation(WFCMessage.Conversation.newBuilder().setType(1).setTarget(target).setLine(0))
                .setBeforeUid(beforeUid)
                .build();
    }

    private static List<Long> uids(List<ProtoMessage> messages) {
        List<Long> out = new ArrayList<>();
        for (ProtoMessage message : messages) {
            out.add(message.getMessageUid());
        }
        return out;
    }

    // ---- recordGaps

    @Test
    public void recordsGapOnlyWhenOlderLocalHistoryExists() {
        FakeSink sink = new FakeSink();
        sink.localUids.add(5L);

        ConversationGapFiller.recordGaps(sink, Arrays.asList(gap("g1", 100), gap("g2", 1)));

        assertEquals(new HashSet<>(Arrays.asList("g1:100")), sink.gaps);
    }

    @Test
    public void recordGapsReturnsRecordedTailGapsForEagerFill() {
        FakeSink sink = new FakeSink();
        sink.localUids.add(5L);

        List<WFCMessage.ConversationGap> tails = ConversationGapFiller.recordGaps(sink,
                Arrays.asList(gap("g1", 100), gap("g2", Long.MAX_VALUE)));

        assertEquals(1, tails.size());
        assertEquals("g2", tails.get(0).getConversation().getTarget());
    }

    // ---- isFillingGap

    @Test
    public void remoteRequestFillsGapOnlyWhenPagingFromAtOrAboveIt() {
        assertFalse(ConversationGapFiller.isFillingGap(0, 100));
        assertTrue(ConversationGapFiller.isFillingGap(100, 100));
        assertTrue(ConversationGapFiller.isFillingGap(100, 120));
        assertFalse(ConversationGapFiller.isFillingGap(100, 50));
    }

    // ---- applyPage

    @Test
    public void fullPageAboveLocalHistoryIsSavedAndMovesGapDown() {
        FakeSink sink = new FakeSink();
        sink.localUids.addAll(Arrays.asList(5L, 100L));
        sink.insertGap("g1", 100);

        List<ProtoMessage> shown = ConversationGapFiller.applyPage(sink, "g1", 100, 2, page(90, 80));

        assertEquals(Arrays.asList(80L, 90L), uids(shown));
        assertEquals(Arrays.asList(80L, 90L), sink.saved);
        assertEquals(new HashSet<>(Arrays.asList("g1:80")), sink.gaps);
        assertFalse(sink.savedAsTail);
    }

    @Test
    public void pageReachingLocalHistoryClosesGapWithoutResurrectingDeletedRows() {
        // 本地 6 被用户删过，补拉回来的 6、7 都不能再给出去
        FakeSink sink = new FakeSink();
        sink.localUids.addAll(Arrays.asList(7L, 100L));
        sink.insertGap("g1", 100);

        List<ProtoMessage> shown = ConversationGapFiller.applyPage(sink, "g1", 100, 3, page(90, 7, 6));

        assertEquals(Arrays.asList(90L), uids(shown));
        assertEquals(Arrays.asList(90L), sink.saved);
        assertTrue(sink.gaps.isEmpty());
    }

    @Test
    public void shortOrEmptyPageClosesGap() {
        FakeSink sink = new FakeSink();
        sink.localUids.add(5L);
        sink.insertGap("g1", 100);

        List<ProtoMessage> shown = ConversationGapFiller.applyPage(sink, "g1", 100, 3, page());

        assertTrue(shown.isEmpty());
        assertTrue(sink.gaps.isEmpty());
    }

    @Test
    public void signalingCountsTowardsPageSizeButIsNeitherShownNorSaved() {
        // 过滤后不足一页不代表服务端到底了，否则断层会被提前关掉
        FakeSink sink = new FakeSink();
        sink.localUids.add(5L);
        sink.insertGap("g1", 100);
        WFCMessage.PullMessageResult mixed = WFCMessage.PullMessageResult.newBuilder().setCurrent(0).setHead(0)
                .addMessage(message(90, SIGNAL_TYPE))
                .addMessage(message(80, 1))
                .build();

        List<ProtoMessage> shown = ConversationGapFiller.applyPage(sink, "g1", 100, 2, mixed);

        assertEquals(Arrays.asList(80L), uids(shown));
        assertEquals(new HashSet<>(Arrays.asList("g1:80")), sink.gaps);
    }

    @Test
    public void tailGapFillIsSavedAsNewestMessages() {
        FakeSink sink = new FakeSink();
        sink.localUids.add(5L);
        sink.insertGap("g1", Long.MAX_VALUE);

        ConversationGapFiller.applyPage(sink, "g1", Long.MAX_VALUE, 2, page(200, 190));

        assertTrue(sink.savedAsTail);
        assertEquals(new HashSet<>(Arrays.asList("g1:190")), sink.gaps);
    }

    // ---- applyHistoryPage（普通远程历史，同样落库）

    @Test
    public void historyPageIsSavedAndReturnedAscending() {
        FakeSink sink = new FakeSink();
        sink.localUids.add(50L);

        List<ProtoMessage> shown = ConversationGapFiller.applyHistoryPage(sink, page(40, 30));

        assertEquals(Arrays.asList(30L, 40L), uids(shown));
        assertEquals(Arrays.asList(30L, 40L), sink.saved);
        assertFalse(sink.savedAsTail);
    }

    @Test
    public void historyPageSkipsAlreadyStoredMessagesWhenSaving() {
        FakeSink sink = new FakeSink();
        sink.localUids.add(40L);

        List<ProtoMessage> shown = ConversationGapFiller.applyHistoryPage(sink, page(40, 30));

        assertEquals(Arrays.asList(30L, 40L), uids(shown));
        assertEquals(Arrays.asList(30L), sink.saved);
    }

    @Test
    public void historyPageDropsSignaling() {
        FakeSink sink = new FakeSink();
        WFCMessage.PullMessageResult mixed = WFCMessage.PullMessageResult.newBuilder().setCurrent(0).setHead(0)
                .addMessage(message(40, SIGNAL_TYPE))
                .addMessage(message(30, 1))
                .build();

        List<ProtoMessage> shown = ConversationGapFiller.applyHistoryPage(sink, mixed);

        assertEquals(Arrays.asList(30L), uids(shown));
        assertEquals(Arrays.asList(30L), sink.saved);
    }
}
