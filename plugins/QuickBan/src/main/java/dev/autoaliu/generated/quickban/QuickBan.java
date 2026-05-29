package dev.autoaliu.generated.quickban;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.utils.DimenUtils;
import com.aliucord.widgets.BottomSheet;
import com.discord.api.channel.Channel;
import com.discord.api.permission.Permission;
import com.discord.api.user.User;
import com.discord.models.message.Message;
import com.discord.stores.StoreStream;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.permissions.PermissionUtils;
import com.discord.views.CheckedSetting;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.ChatListEntry;
import com.discord.widgets.chat.list.entries.MessageEntry;
import com.discord.widgets.user.WidgetBanUser;

@AliucordPlugin
@SuppressWarnings("unused")
public class QuickBan extends Plugin {
    private static final String SETTINGS_NAME = "QuickBan";
    private static final String KEY_ENABLED = "enabled";
    private static final String BUTTON_TAG = "QuickBan.BanButton";
    private static final int CHAT_TEXT_ID = Utils.getResId("chat_list_adapter_item_text", "id");
    private static final int COLOR_BACKGROUND_TERTIARY_ATTR = Utils.getResId("colorBackgroundTertiary", "attr");
    private static final int COLOR_TEXT_DANGER_ATTR = Utils.getResId("colorTextDanger", "attr");

    public QuickBan() {
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
            WidgetChatListAdapterItemMessage.class,
            "onConfigure",
            new Class<?>[] { int.class, ChatListEntry.class },
            new Hook(param -> configureBanButton(
                (WidgetChatListAdapterItemMessage) param.thisObject,
                (ChatListEntry) param.args[1]
            ))
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
    }

    private void configureBanButton(WidgetChatListAdapterItemMessage row, ChatListEntry entry) {
        if (row == null || row.itemView == null) return;

        if (!settings.getBool(KEY_ENABLED, true) || !(entry instanceof MessageEntry)) {
            removeButton(row.itemView);
            return;
        }

        MessageEntry messageEntry = (MessageEntry) entry;
        Message message = messageEntry.getMessage();
        long guildId = getGuildId(message);
        User author = message != null ? message.getAuthor() : null;
        if (!shouldShow(message, messageEntry, guildId, author)) {
            removeButton(row.itemView);
            return;
        }

        TextView button = ensureButton(row.itemView);
        if (button == null) return;

        String username = author.getUsername();
        long userId = author.getId();
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(view -> {
            try {
                WidgetBanUser.launch(username, guildId, userId, Utils.appActivity.getSupportFragmentManager());
            } catch (Throwable throwable) {
                logger.error("QuickBan failed to open ban dialog", throwable);
            }
        });
    }

    private static boolean shouldShow(Message message, MessageEntry entry, long guildId, User author) {
        if (message == null || entry == null || author == null || guildId <= 0L) return false;
        if (entry.isThreadStarterMessage()) return false;
        if (message.isLocal() || message.isFailed() || message.isEphemeralMessage()) return false;
        if (!PermissionUtils.can(Permission.BAN_MEMBERS, entry.getPermissionsForChannel())) return false;

        try {
            return StoreStream.getUsers().getMe().getId() != author.getId();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static long getGuildId(Message message) {
        if (message == null) return 0L;

        Long directGuildId = message.getGuildId();
        if (directGuildId != null && directGuildId > 0L) return directGuildId;

        Channel channel = StoreStream.getChannels().getChannel(message.getChannelId());
        return channel != null ? channel.i() : 0L;
    }

    private static TextView ensureButton(View root) {
        if (!(root instanceof ConstraintLayout)) return null;

        View existing = root.findViewWithTag(BUTTON_TAG);
        if (existing instanceof TextView) return (TextView) existing;

        Context context = root.getContext();
        TextView button = new TextView(context);
        button.setId(View.generateViewId());
        button.setTag(BUTTON_TAG);
        button.setText("Ban");
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(DimenUtils.dpToPx(48));
        button.setMinHeight(DimenUtils.dpToPx(26));
        button.setPadding(DimenUtils.dpToPx(12), 0, DimenUtils.dpToPx(12), 0);
        button.setTextColor(ColorCompat.getThemedColor(context, COLOR_TEXT_DANGER_ATTR));
        button.setTextSize(12f);
        button.setClickable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(ColorCompat.getThemedColor(context, COLOR_BACKGROUND_TERTIARY_ATTR));
        background.setStroke(DimenUtils.dpToPx(1), ColorCompat.getThemedColor(context, COLOR_TEXT_DANGER_ATTR));
        background.setCornerRadius(DimenUtils.dpToPx(4));
        button.setBackground(background);

        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            DimenUtils.dpToPx(26)
        );
        params.startToStart = CHAT_TEXT_ID;
        params.leftToLeft = CHAT_TEXT_ID;
        params.topToBottom = CHAT_TEXT_ID;
        params.setMargins(0, DimenUtils.dpToPx(2), 0, DimenUtils.dpToPx(2));
        ((ConstraintLayout) root).addView(button, params);
        return button;
    }

    private static void removeButton(View root) {
        if (!(root instanceof ViewGroup)) return;
        View existing = root.findViewWithTag(BUTTON_TAG);
        if (existing != null) ((ViewGroup) root).removeView(existing);
    }

    public static final class SettingsSheet extends BottomSheet {
        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);

            SettingsAPI api = new SettingsAPI(SETTINGS_NAME);
            CheckedSetting enabled = Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Enable Ban buttons",
                "Show a Ban button below eligible guild messages."
            );
            enabled.setChecked(api.getBool(KEY_ENABLED, true));
            enabled.setOnCheckedListener(checked -> api.setBool(KEY_ENABLED, checked));
            addView(enabled);
        }
    }
}
