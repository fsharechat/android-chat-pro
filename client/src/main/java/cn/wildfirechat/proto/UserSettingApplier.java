package cn.wildfirechat.proto;

import java.util.List;

/**
 * UG 应答的落地逻辑。从 UserSettingPullHandler 抽出来，纯逻辑、可单测。
 *
 * 抽出来的直接原因：落库之后必须通知上层刷新。好友同步这条路一直是对的
 * （NotifyFriendHandler 落库后会调 JavaProtoLogic.onFriendRequestUpdated()），
 * 用户设置这条路漏了对应的 onSettingUpdated()——整条刷新链路（ClientService →
 * ChatManager.settingUpdateListeners → ConversationListViewModel）都是通的，
 * 只是从来没有人扣扳机。于是其他设备改的置顶/免打扰/保存的群组确实存进了本地库，
 * 界面却要等下次重进页面才看得到，表现出来就是「安卓端收不到同步」。
 */
public final class UserSettingApplier {

    /**
     * 只声明用得到的三个方法，测试里给个几行的实现即可，不必伪造整个 ImMemoryStore。
     */
    public interface SettingSink {
        long getUserSettingVersion();

        void setUserSetting(int scope, String key, String value);

        void setUserSettingVersion(long version);
    }

    public interface Notifier {
        void onSettingUpdated();
    }

    private UserSettingApplier() {
    }

    /**
     * 把 UG 拉回来的增量条目落库，并推进本地 version。
     *
     * @return 落地后的新 version
     */
    public static long apply(SettingSink sink,
                             List<WFCMessage.UserSettingEntry> entries,
                             Notifier notifier) {
        long maxUpdateDt = sink.getUserSettingVersion();
        for (WFCMessage.UserSettingEntry entry : entries) {
            sink.setUserSetting(entry.getScope(), entry.getKey(), entry.getValue());
            if (entry.getUpdateDt() > maxUpdateDt) {
                maxUpdateDt = entry.getUpdateDt();
            }
        }
        sink.setUserSettingVersion(maxUpdateDt);
        // 必须等全部条目落地之后再通知，否则上层读到的是写了一半的状态。
        // 空增量（每次连上来都会有一次）不通知，避免无谓的列表重载。
        if (!entries.isEmpty()) {
            notifier.onSettingUpdated();
        }
        return maxUpdateDt;
    }
}
