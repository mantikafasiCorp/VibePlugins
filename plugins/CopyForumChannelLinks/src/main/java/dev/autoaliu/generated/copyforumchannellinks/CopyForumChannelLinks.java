package dev.autoaliu.generated.copyforumchannellinks;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.DimenUtils;
import com.discord.api.channel.Channel;
import com.discord.app.AppBottomSheet;
import com.discord.utilities.color.ColorCompat;
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions;
import com.discord.widgets.channels.list.WidgetChannelsListItemThreadActions;

@AliucordPlugin
@SuppressWarnings("unused")
public final class CopyForumChannelLinks extends Plugin {
    private static final String ACTION_TAG = "copy_forum_channel_link_action";
    private static final int GUILD_PUBLIC_THREAD = 11;

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
            WidgetChannelsListItemChannelActions.class,
            "access$configureUI",
            new Class<?>[] {
                WidgetChannelsListItemChannelActions.class,
                WidgetChannelsListItemChannelActions.Model.class
            },
            new Hook(param -> addCopyLinkAction(
                (WidgetChannelsListItemChannelActions) param.args[0],
                (WidgetChannelsListItemChannelActions.Model) param.args[1]
            ))
        );

        patcher.patch(
            WidgetChannelsListItemThreadActions.class,
            "access$configureUI",
            new Class<?>[] {
                WidgetChannelsListItemThreadActions.class,
                WidgetChannelsListItemThreadActions.Model.class
            },
            new Hook(param -> addForumPostCopyLinkAction(
                (WidgetChannelsListItemThreadActions) param.args[0],
                (WidgetChannelsListItemThreadActions.Model) param.args[1]
            ))
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
    }

    private static void addCopyLinkAction(
        WidgetChannelsListItemChannelActions sheet,
        WidgetChannelsListItemChannelActions.Model model
    ) {
        if (model == null) return;

        Channel channel = model.getChannel();
        if (channel == null) return;

        boolean isForumPost = channel.D() == GUILD_PUBLIC_THREAD;
        if (channel.D() != Channel.GUILD_FORUM && !isForumPost) return;

        addCopyLinkAction(sheet, channel, isForumPost ? "Forum Post" : "Forum Channel");
    }

    private static void addForumPostCopyLinkAction(
        WidgetChannelsListItemThreadActions sheet,
        WidgetChannelsListItemThreadActions.Model model
    ) {
        if (model == null) return;

        Channel channel = model.getChannel();
        Channel parentChannel = model.getParentChannel();
        if (channel == null || parentChannel == null) return;
        if (channel.D() != GUILD_PUBLIC_THREAD || parentChannel.D() != Channel.GUILD_FORUM) return;

        addCopyLinkAction(sheet, channel, "Forum Post");
    }

    private static void addCopyLinkAction(AppBottomSheet sheet, Channel channel, String targetName) {

        long guildId = channel.i();
        long channelId = channel.k();
        if (guildId == 0L || channelId == 0L) return;

        View root = sheet.getView();
        if (!(root instanceof NestedScrollView)) return;

        View child = ((NestedScrollView) root).getChildAt(0);
        if (!(child instanceof LinearLayout)) return;

        LinearLayout layout = (LinearLayout) child;
        if (layout.findViewWithTag(ACTION_TAG) != null) return;

        TextView action = createActionView(layout.getContext(), layout);
        action.setTag(ACTION_TAG);
        action.setText("Copy " + targetName + " Link");
        action.setOnClickListener(view -> {
            String link = "https://discord.com/channels/" + guildId + "/" + channelId;
            Utils.setClipboard(targetName + " link", link);
            Utils.showToast(view.getContext(), targetName + " link copied");
            sheet.dismiss();
        });
        layout.addView(action);
    }

    private static TextView createActionView(Context context, LinearLayout layout) {
        TextView action = new TextView(context);
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setMinHeight(DimenUtils.dpToPx(48));
        action.setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), 0);
        action.setTextColor(themedColor(context));
        action.setTextSize(16f);

        for (int index = 0; index < layout.getChildCount(); index++) {
            View child = layout.getChildAt(index);
            if (child instanceof TextView) {
                TextView source = (TextView) child;
                action.setTextColor(source.getTextColors());
                action.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
                action.setTypeface(source.getTypeface());
                break;
            }
        }

        TypedValue selectableBackground = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)) {
            action.setBackgroundResource(selectableBackground.resourceId);
        }

        action.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return action;
    }

    private static int themedColor(Context context) {
        int attribute = Utils.getResId("colorTextNormal", "attr");
        int color = ColorCompat.getThemedColor(context, attribute);
        return color == 0 ? Color.WHITE : color;
    }
}
