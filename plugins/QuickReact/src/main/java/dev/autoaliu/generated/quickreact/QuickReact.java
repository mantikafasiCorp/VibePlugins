package dev.autoaliu.generated.quickreact;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextSwitcher;
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
import com.discord.api.thread.ThreadMetadata;
import com.discord.api.message.reaction.MessageReaction;
import com.discord.api.message.reaction.MessageReactionEmoji;
import com.discord.models.domain.emoji.Emoji;
import com.discord.models.domain.emoji.EmojiSet;
import com.discord.models.domain.emoji.ModelEmojiCustom;
import com.discord.models.message.Message;
import com.discord.stores.StoreStream;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;
import com.discord.views.ReactionView;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.ChatListEntry;
import com.discord.widgets.chat.list.entries.MessageEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import rx.Subscription;

@AliucordPlugin
@SuppressWarnings({"unused", "unchecked"})
public class QuickReact extends Plugin {
    private static final String SETTINGS_NAME = "QuickReact";
    private static final String KEY_ENABLED = "enabled";
    private static final String CONTAINER_TAG = "QuickReact.Container";
    private static final int QUICK_BUTTON_COUNT = 3;
    private static final int CHAT_TEXT_ID = Utils.getResId("chat_list_adapter_item_text", "id");
    private static final int COUNTER_TEXT_SWITCHER_ID = Utils.getResId("counter_text_switcher", "id");
    private static final int EMOJI_TEXT_VIEW_ID = Utils.getResId("emoji_text_view", "id");
    private static final int COLOR_BACKGROUND_TERTIARY_ATTR = Utils.getResId("colorBackgroundTertiary", "attr");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, List<Emoji>> emojiCache = new ConcurrentHashMap<>();
    private final List<Subscription> subscriptions = Collections.synchronizedList(new ArrayList<>());

    public QuickReact() {
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
            WidgetChatListAdapterItemMessage.class,
            "onConfigure",
            new Class<?>[] { int.class, ChatListEntry.class },
            new Hook(param -> configureQuickReactions(
                (WidgetChatListAdapterItemMessage) param.thisObject,
                (ChatListEntry) param.args[1]
            ))
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
        synchronized (subscriptions) {
            for (Subscription subscription : subscriptions) {
                if (subscription != null && !subscription.isUnsubscribed()) subscription.unsubscribe();
            }
            subscriptions.clear();
        }
        emojiCache.clear();
    }

    private void configureQuickReactions(WidgetChatListAdapterItemMessage row, ChatListEntry entry) {
        if (row == null || row.itemView == null) return;

        if (!settings.getBool(KEY_ENABLED, true) || !(entry instanceof MessageEntry)) {
            removeContainer(row.itemView);
            restoreMessageText(row.itemView);
            return;
        }

        MessageEntry messageEntry = (MessageEntry) entry;
        Message message = messageEntry.getMessage();
        if (!canShowFor(message, messageEntry)) {
            removeContainer(row.itemView);
            restoreMessageText(row.itemView);
            return;
        }

        WidgetChatListAdapter adapter = WidgetChatListAdapterItemMessage.access$getAdapter$p(row);
        LinearLayout container = ensureContainer(row.itemView);
        if (container == null) return;

        container.setTag(CONTAINER_TAG);
        container.setTag(COUNTER_TEXT_SWITCHER_ID, message.getId());
        container.removeAllViews();
        constrainMessageText(row.itemView, container);

        List<Emoji> cached = emojiCache.get(cacheKey(message.getChannelId()));
        if (cached != null) {
            populateButtons(container, adapter, message, cached);
            return;
        }

        requestQuickEmojis(container, adapter, message);
    }

    private void requestQuickEmojis(LinearLayout container, WidgetChatListAdapter adapter, Message message) {
        Channel channel = StoreStream.getChannels().getChannel(message.getChannelId());
        long guildId = channel != null ? channel.i() : 0L;
        String key = cacheKey(message.getChannelId());
        long expectedMessageId = message.getId();

        try {
            Subscription subscription = StoreStream.getEmojis()
                .getEmojiSet(guildId, message.getChannelId(), false, false)
                .Z(1)
                .W(emojiSet -> {
                    List<Emoji> recent = firstUsable(emojiSet);
                    emojiCache.put(key, recent);
                    mainHandler.post(() -> {
                        Object boundMessageId = container.getTag(COUNTER_TEXT_SWITCHER_ID);
                        if (boundMessageId instanceof Long && ((Long) boundMessageId) == expectedMessageId) {
                            populateButtons(container, adapter, message, recent);
                        }
                    });
                }, throwable -> logger.error("QuickReact failed to read frequent emojis", throwable));
            subscriptions.add(subscription);
        } catch (Throwable throwable) {
            logger.error("QuickReact failed to request frequent emojis", throwable);
        }
    }

    private void populateButtons(LinearLayout container, WidgetChatListAdapter adapter, Message message, List<Emoji> emojis) {
        container.removeAllViews();
        if (emojis == null || emojis.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }

        int added = 0;
        for (Emoji emoji : emojis) {
            if (added >= QUICK_BUTTON_COUNT) break;

            MessageReactionEmoji reactionEmoji = toReactionEmoji(emoji);
            if (reactionEmoji == null) continue;

            Map<String, MessageReaction> reactions = message.getReactionsMap();
            MessageReaction existingReaction = reactions != null ? reactions.get(reactionEmoji.c()) : null;
            final MessageReaction reaction = existingReaction != null
                ? existingReaction
                : new MessageReaction(0, reactionEmoji, false);
            ReactionView button = createButton(container.getContext(), reaction, message.getId());
            button.setOnClickListener(view -> {
                StoreStream.getEmojis().onEmojiUsed(emoji);
                adapter.onReactionClicked(message.getId(), reaction, true);
            });
            container.addView(button);
            added++;
        }

        container.setVisibility(added == 0 ? View.GONE : View.VISIBLE);
    }

    private static boolean canShowFor(Message message, MessageEntry entry) {
        if (message == null || entry == null) return false;
        if (entry.isThreadStarterMessage()) return false;
        if (message.isLocal() || message.isFailed() || message.isEphemeralMessage()) return false;

        Channel channel = StoreStream.getChannels().getChannel(message.getChannelId());
        if (channel == null) return true;
        ThreadMetadata threadMetadata = channel.B();
        return threadMetadata == null || !threadMetadata.b();
    }

    private static LinearLayout ensureContainer(View root) {
        if (!(root instanceof ConstraintLayout)) return null;

        View existing = root.findViewWithTag(CONTAINER_TAG);
        if (existing instanceof LinearLayout) return (LinearLayout) existing;

        Context context = root.getContext();
        LinearLayout container = new LinearLayout(context);
        container.setId(View.generateViewId());
        container.setTag(CONTAINER_TAG);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        params.topToTop = CHAT_TEXT_ID;
        params.setMarginEnd(DimenUtils.dpToPx(8));
        ((ConstraintLayout) root).addView(container, params);
        return container;
    }

    private static void removeContainer(View root) {
        if (!(root instanceof ViewGroup)) return;
        View existing = root.findViewWithTag(CONTAINER_TAG);
        if (existing != null) ((ViewGroup) root).removeView(existing);
    }

    private static void constrainMessageText(View root, View container) {
        View messageText = root.findViewById(CHAT_TEXT_ID);
        if (messageText == null || !(messageText.getLayoutParams() instanceof ConstraintLayout.LayoutParams)) return;

        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) messageText.getLayoutParams();
        params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
        params.rightToRight = ConstraintLayout.LayoutParams.UNSET;
        params.endToStart = container.getId();
        params.rightToLeft = container.getId();
        messageText.setLayoutParams(params);
    }

    private static void restoreMessageText(View root) {
        View messageText = root.findViewById(CHAT_TEXT_ID);
        if (messageText == null || !(messageText.getLayoutParams() instanceof ConstraintLayout.LayoutParams)) return;

        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) messageText.getLayoutParams();
        params.endToStart = ConstraintLayout.LayoutParams.UNSET;
        params.rightToLeft = ConstraintLayout.LayoutParams.UNSET;
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        params.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID;
        messageText.setLayoutParams(params);
    }

    private static ReactionView createButton(Context context, MessageReaction reaction, long messageId) {
        ReactionView button = new ReactionView(context, null, 0, 6);
        int horizontal = DimenUtils.dpToPx(6);
        button.setPadding(horizontal, button.getPaddingTop(), horizontal, button.getPaddingBottom());
        button.a(reaction, messageId, true);

        TextSwitcher counter = button.findViewById(COUNTER_TEXT_SWITCHER_ID);
        if (counter != null) counter.setVisibility(View.GONE);

        View emojiText = button.findViewById(EMOJI_TEXT_VIEW_ID);
        if (emojiText != null) {
            ViewGroup.LayoutParams rawParams = emojiText.getLayoutParams();
            if (rawParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams emojiParams = (LinearLayout.LayoutParams) rawParams;
                emojiParams.setMarginEnd(0);
                emojiParams.gravity = Gravity.CENTER;
                emojiText.setLayoutParams(emojiParams);
            }
            if (emojiText instanceof TextView) ((TextView) emojiText).setGravity(Gravity.CENTER);
        }

        GradientDrawable background = new GradientDrawable();
        background.setColor(ColorCompat.getThemedColor(context, COLOR_BACKGROUND_TERTIARY_ATTR));
        background.setCornerRadius(DimenUtils.dpToPx(13));
        button.setBackground(background);
        button.setGravity(Gravity.CENTER);
        button.setMinimumWidth(DimenUtils.dpToPx(30));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            DimenUtils.dpToPx(26)
        );
        params.setMarginStart(DimenUtils.dpToPx(4));
        button.setLayoutParams(params);
        return button;
    }

    private static MessageReactionEmoji toReactionEmoji(Emoji emoji) {
        if (emoji instanceof ModelEmojiCustom) {
            ModelEmojiCustom custom = (ModelEmojiCustom) emoji;
            return new MessageReactionEmoji(custom.getIdStr(), custom.getName(), custom.isAnimated());
        }

        String reactionKey = emoji.getReactionKey();
        return reactionKey == null || reactionKey.isEmpty()
            ? null
            : new MessageReactionEmoji(null, reactionKey, false);
    }

    private static List<Emoji> firstUsable(EmojiSet emojiSet) {
        if (emojiSet == null || emojiSet.recentEmojis == null) return Collections.emptyList();

        ArrayList<Emoji> result = new ArrayList<>();
        for (Emoji emoji : emojiSet.recentEmojis) {
            if (emoji != null && emoji.isUsable() && emoji.isAvailable()) result.add(emoji);
            if (result.size() >= QUICK_BUTTON_COUNT) break;
        }
        return result;
    }

    private static String cacheKey(long channelId) {
        return String.valueOf(channelId);
    }

    public static final class SettingsSheet extends BottomSheet {
        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);

            SettingsAPI api = new SettingsAPI(SETTINGS_NAME);
            CheckedSetting enabled = Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Enable quick react buttons",
                "Show your top three frequently used emoji beside messages."
            );
            enabled.setChecked(api.getBool(KEY_ENABLED, true));
            enabled.setOnCheckedListener(checked -> api.setBool(KEY_ENABLED, checked));
            addView(enabled);
        }
    }
}
