package cn.wildfirechat.proto;

import java.util.ArrayList;
import java.util.List;

import cn.wildfirechat.model.ProtoMessage;

/**
 * 会话断层：增量拉取（MP）时服务端每个会话最多下发最近 20 条，被截断的会话在
 * PullMessageResult.gap 里带上 before_uid。本地若还留着离线前的老消息，两段之间就缺了一截，
 * 而界面往上翻是先读本地、本地读完才拉远程，缺的那截永远不会出现。
 *
 * 处理方式：记下断层；本地分页只返回断层以上的消息（见 ProtoMessageDataStore.getMessages），
 * 读完后界面照常拉远程，这时用本类补拉：落库、交给界面，并删除或下移断层。
 * before_uid 为 Long.MAX_VALUE 是尾部断层（该会话一条都没下发），收到时立即补拉最新一页。
 *
 * 纯逻辑、可单测，存储操作通过 GapSink 注入。设计见服务端
 * docs/superpowers/specs/2026-10-26-conversation-gap-sync-design.md
 */
public final class ConversationGapFiller {

    public interface GapSink {
        /** 本地该会话是否有 0 < uid < beforeUid 的消息 */
        boolean hasOlderMessage(String target, long beforeUid);

        void insertGap(String target, long beforeUid);

        void deleteGap(String target, long beforeUid);

        /** 断层下方本地最新一条的 uid，没有返回 0 */
        long getLocalFloorUid(String target, long belowUid);

        boolean hasMessage(long messageUid);

        boolean canPersistent(int contentType);

        ProtoMessage convert(WFCMessage.Message message);

        /**
         * 补拉到的消息落库。普通断层补的是历史，不能改会话的最后一条消息；
         * 尾部断层补的是最新一段，需要更新会话
         */
        void saveHistory(String target, List<ProtoMessage> messages, boolean isTail);
    }

    private ConversationGapFiller() {
    }

    /**
     * 记录增量拉取带回的断层。本地没有更早消息时不会形成空洞，忽略。
     * 返回记录下来的尾部断层，调用方需立即补拉。
     */
    public static List<WFCMessage.ConversationGap> recordGaps(GapSink sink, List<WFCMessage.ConversationGap> gaps) {
        List<WFCMessage.ConversationGap> tails = new ArrayList<>();
        for (WFCMessage.ConversationGap gap : gaps) {
            String target = gap.getConversation().getTarget();
            if (!sink.hasOlderMessage(target, gap.getBeforeUid())) {
                continue;
            }
            sink.insertGap(target, gap.getBeforeUid());
            if (gap.getBeforeUid() == Long.MAX_VALUE) {
                tails.add(gap);
            }
        }
        return tails;
    }

    /**
     * 界面拉远程历史时，是否正在从断层处往下翻。gapFloor 为该会话最高的普通断层，0 表示没有。
     * 本地分页不会返回断层以下的消息，所以此时界面最老一条的 uid 一定不低于断层
     */
    public static boolean isFillingGap(long gapFloor, long beforeUid) {
        return gapFloor > 0 && beforeUid >= gapFloor;
    }

    /**
     * 普通远程历史（本地已翻到底、没有断层）的一页（服务端按 uid 降序），返回交给界面的消息（升序）。
     * 与补断层一样落库且不改会话：界面翻页的游标是本地 messageId，消息不在本地库里时
     * getSortKey 查不到 uid，下一次本地分页的位置就不可靠；落库后下次打开会话也不必重新拉
     */
    public static List<ProtoMessage> applyHistoryPage(GapSink sink, WFCMessage.PullMessageResult page) {
        List<ProtoMessage> shown = new ArrayList<>();
        List<ProtoMessage> fresh = new ArrayList<>();
        for (int i = page.getMessageCount() - 1; i >= 0; i--) {
            WFCMessage.Message message = page.getMessage(i);
            if (!sink.canPersistent(message.getContent().getType())) {
                continue;
            }
            ProtoMessage protoMessage = sink.convert(message);
            shown.add(protoMessage);
            if (!sink.hasMessage(message.getMessageId())) {
                fresh.add(protoMessage);
            }
        }
        if (!fresh.isEmpty()) {
            sink.saveHistory(fresh.get(0).getTarget(), fresh, false);
        }
        return shown;
    }

    /**
     * 处理补拉回来的一页（服务端按 uid 降序），返回交给界面的消息（升序）。
     * uid 不超过断层下方本地最新一条的消息不再给出：要么已经在本地，要么是用户删过的。
     * 已经接上本地、或服务端已到底（空页、不满一页）时删除断层，否则下移到这一页最老的一条
     */
    public static List<ProtoMessage> applyPage(GapSink sink, String target, long gapUid, int requestedCount, WFCMessage.PullMessageResult page) {
        long floorUid = sink.getLocalFloorUid(target, gapUid);
        List<ProtoMessage> shown = new ArrayList<>();
        List<ProtoMessage> fresh = new ArrayList<>();
        long oldestUid = Long.MAX_VALUE;
        for (int i = page.getMessageCount() - 1; i >= 0; i--) {
            WFCMessage.Message message = page.getMessage(i);
            oldestUid = Math.min(oldestUid, message.getMessageId());
            if (message.getMessageId() <= floorUid || !sink.canPersistent(message.getContent().getType())) {
                continue;
            }
            ProtoMessage protoMessage = sink.convert(message);
            shown.add(protoMessage);
            if (!sink.hasMessage(message.getMessageId())) {
                fresh.add(protoMessage);
            }
        }
        if (!fresh.isEmpty()) {
            sink.saveHistory(target, fresh, gapUid == Long.MAX_VALUE);
        }

        sink.deleteGap(target, gapUid);
        boolean reachedLocal = oldestUid <= floorUid;
        boolean serverExhausted = page.getMessageCount() < requestedCount;
        if (!reachedLocal && !serverExhausted) {
            sink.insertGap(target, oldestUid);
        }
        return shown;
    }
}
