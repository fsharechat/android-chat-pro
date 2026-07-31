package cn.wildfire.chat.kit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import cn.wildfirechat.model.ConversationInfo;

/**
 * 用户会话的长安菜单项
 * <p>
 * 所注解的方法，必须是public，且接受两个参数，第一个为{@link android.view.View}， 第二个为{@link ConversationInfo}
 */

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConversationContextMenuItem {
    String title();

    String tag();

    int priority() default 0;

    /**
     * 是否需要二次确认
     *
     * @return
     */
    boolean confirm() default false;

    /**
     * 菜单项图标，0 表示不显示图标。
     * <p>不按 tag 映射图标是因为 tag 并不唯一（例如「删除会话」与「清空会话」共用 TAG_REMOVE）。
     *
     * @return
     */
    int icon() default 0;

    /**
     * 是否为危险操作（删除、清空这类不可逆操作），为 true 时菜单项文案与图标标红
     *
     * @return
     */
    boolean destructive() default false;

    /**
     * 二次确认的提示，只有当{@link #confirm()}为true时有用
     *
     * @return
     */
    String confirmPrompt() default "";
}
