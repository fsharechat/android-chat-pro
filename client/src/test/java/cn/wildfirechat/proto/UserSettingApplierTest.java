package cn.wildfirechat.proto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * UG 增量落地。
 *
 * 回归背景：其他设备改置顶/免打扰/保存的群组后，服务端推 UN、本端拉 UG，数据确实存进了
 * 本地库，但落库后没有通知上层刷新，会话列表要等下次重进页面才更新——用户看到的现象是
 * 「iOS/PC 上的操作同步不到安卓，反过来却可以」（反过来能看到是因为安卓本端操作时 UI 是
 * 自己直接改的）。
 */
public class UserSettingApplierTest {

    /** 只实现用得到的三个方法，顺带把写入顺序记下来供断言。 */
    private static class FakeSink implements UserSettingApplier.SettingSink {
        long version;
        final Map<String, String> written = new LinkedHashMap<>();

        @Override
        public long getUserSettingVersion() {
            return version;
        }

        @Override
        public void setUserSetting(int scope, String key, String value) {
            written.put(scope + ":" + key, value);
        }

        @Override
        public void setUserSettingVersion(long version) {
            this.version = version;
        }
    }

    private static class RecordingNotifier implements UserSettingApplier.Notifier {
        int calls;

        @Override
        public void onSettingUpdated() {
            calls++;
        }
    }

    private WFCMessage.UserSettingEntry entry(int scope, String key, String value, long updateDt) {
        return WFCMessage.UserSettingEntry.newBuilder()
                .setScope(scope)
                .setKey(key)
                .setValue(value)
                .setUpdateDt(updateDt)
                .build();
    }

    @Test
    public void notifiesUiAfterApplyingEntries() {
        // 这条就是线上现象：设置存下来了，但界面不刷新
        FakeSink sink = new FakeSink();
        RecordingNotifier notifier = new RecordingNotifier();

        UserSettingApplier.apply(sink, Arrays.asList(entry(3, "1-0-groupX", "1", 100L)), notifier);

        assertEquals(1, notifier.calls);
    }

    @Test
    public void doesNotNotifyOnEmptyIncrement() {
        // 每次连上来都会有一次空拉取，不该因此触发一轮列表重载
        FakeSink sink = new FakeSink();
        RecordingNotifier notifier = new RecordingNotifier();

        UserSettingApplier.apply(sink, new ArrayList<WFCMessage.UserSettingEntry>(), notifier);

        assertEquals(0, notifier.calls);
    }

    @Test
    public void writesEveryEntryIncludingTombstones() {
        // value "0" 是取消操作的墓碑，必须照常写入，不能被过滤掉，
        // 否则本端无法感知其他设备的「取消置顶/取消免打扰」
        FakeSink sink = new FakeSink();

        UserSettingApplier.apply(sink, Arrays.asList(
                entry(3, "1-0-groupX", "1", 100L),
                entry(1, "0-0-userY", "0", 101L),
                entry(6, "groupZ", "1", 102L)
        ), new RecordingNotifier());

        assertEquals("1", sink.written.get("3:1-0-groupX"));
        assertEquals("0", sink.written.get("1:0-0-userY"));
        assertEquals("1", sink.written.get("6:groupZ"));
        assertEquals(3, sink.written.size());
    }

    @Test
    public void advancesVersionToMaxUpdateDt() {
        FakeSink sink = new FakeSink();
        sink.version = 50L;

        long result = UserSettingApplier.apply(sink, Arrays.asList(
                entry(3, "a", "1", 70L),
                entry(3, "b", "1", 60L)
        ), new RecordingNotifier());

        assertEquals(70L, result);
        assertEquals(70L, sink.version);
    }

    @Test
    public void neverMovesVersionBackwards() {
        // 服务端只回 _dt > version 的条目，但本地 version 不能因为任何原因倒退：
        // 倒退会让下次拉取重复拿到已经处理过的条目
        FakeSink sink = new FakeSink();
        sink.version = 200L;

        long result = UserSettingApplier.apply(sink, Arrays.asList(entry(3, "a", "1", 100L)),
                new RecordingNotifier());

        assertEquals(200L, result);
        assertEquals(200L, sink.version);
    }

    @Test
    public void emptyIncrementKeepsVersionUnchanged() {
        FakeSink sink = new FakeSink();
        sink.version = 123L;

        UserSettingApplier.apply(sink, new ArrayList<WFCMessage.UserSettingEntry>(), new RecordingNotifier());

        assertEquals(123L, sink.version);
    }

    @Test
    public void tombstoneOnlyIncrementStillNotifies() {
        // 其他设备「取消置顶」时，增量里只有一条 value "0"，
        // 这同样是一次可见的变化，必须刷新界面
        FakeSink sink = new FakeSink();
        RecordingNotifier notifier = new RecordingNotifier();

        UserSettingApplier.apply(sink, Arrays.asList(entry(3, "1-0-groupX", "0", 300L)), notifier);

        assertEquals(1, notifier.calls);
        assertEquals("0", sink.written.get("3:1-0-groupX"));
    }

    @Test
    public void appliesAllEntriesBeforeNotifying() {
        // 通知必须发生在全部条目落地之后，否则 UI 读到的是写了一半的状态
        FakeSink sink = new FakeSink();
        final List<Integer> sizeSeenByNotifier = new ArrayList<>();
        UserSettingApplier.Notifier notifier = new UserSettingApplier.Notifier() {
            @Override
            public void onSettingUpdated() {
                sizeSeenByNotifier.add(sink.written.size());
            }
        };

        UserSettingApplier.apply(sink, Arrays.asList(
                entry(3, "a", "1", 10L),
                entry(3, "b", "1", 20L),
                entry(3, "c", "1", 30L)
        ), notifier);

        assertEquals(Arrays.asList(3), sizeSeenByNotifier);
    }
}
