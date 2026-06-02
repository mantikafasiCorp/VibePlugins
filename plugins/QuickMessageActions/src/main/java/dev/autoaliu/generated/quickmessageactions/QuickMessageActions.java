package dev.autoaliu.generated.quickmessageactions;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ImageView;
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
import com.discord.widgets.chat.list.actions.WidgetChatListActions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import rx.Subscription;

@AliucordPlugin
@SuppressWarnings({"unused", "unchecked"})
public class QuickMessageActions extends Plugin {
    private static final String SETTINGS_NAME = "QuickMessageActions";
    private static final String KEY_QUICK_REACTIONS = "quickReactions";
    private static final String KEY_MESSAGE_ACTIONS = "messageActions";
    private static final String LEGACY_KEY_ENABLED = "enabled";
    private static final String LEGACY_KEY_ACTIONS_ONLY = "actionsOnly";
    private static final int QUICK_BUTTON_COUNT = 3;
    private static final int CHAT_TEXT_ID = Utils.getResId("chat_list_adapter_item_text", "id");
    private static final int COUNTER_TEXT_SWITCHER_ID = Utils.getResId("counter_text_switcher", "id");
    private static final int EMOJI_TEXT_VIEW_ID = Utils.getResId("emoji_text_view", "id");
    private static final int COLOR_BACKGROUND_TERTIARY_ATTR = Utils.getResId("colorBackgroundTertiary", "attr");
    private static final SettingsAPI PLUGIN_SETTINGS = new SettingsAPI(SETTINGS_NAME);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, List<Emoji>> emojiCache = new ConcurrentHashMap<>();
    private final List<Subscription> subscriptions = Collections.synchronizedList(new ArrayList<>());
    
    private static PopupWindow currentPopup = null;
    private static long popupMessageId = -1L;

    public QuickMessageActions() {
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
            WidgetChatListAdapterItemMessage.class,
            "onConfigure",
            new Class<?>[] { int.class, ChatListEntry.class },
            new Hook(param -> configureQuickMessageActions(
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
        if (currentPopup != null) {
            currentPopup.dismiss();
            currentPopup = null;
        }
    }

    private void configureQuickMessageActions(WidgetChatListAdapterItemMessage row, ChatListEntry entry) {
        if (row == null || row.itemView == null) return;

        if (!(entry instanceof MessageEntry)) {
            restoreOriginalClickListener(row.itemView);
            return;
        }

        MessageEntry messageEntry = (MessageEntry) entry;
        Message message = messageEntry.getMessage();
        if (!canShowFor(message, messageEntry)) {
            restoreOriginalClickListener(row.itemView);
            return;
        }

        WidgetChatListAdapter adapter = WidgetChatListAdapterItemMessage.access$getAdapter$p(row);
        applyClickListener(row.itemView, message, adapter);
    }

    private static boolean showMessageActions() {
        return PLUGIN_SETTINGS.getBool(KEY_MESSAGE_ACTIONS, PLUGIN_SETTINGS.getBool(LEGACY_KEY_ENABLED, true));
    }

    private static boolean showQuickReactions() {
        return PLUGIN_SETTINGS.getBool(KEY_QUICK_REACTIONS, !PLUGIN_SETTINGS.getBool(LEGACY_KEY_ACTIONS_ONLY, false));
    }

    private void applyClickListener(View itemView, Message message, WidgetChatListAdapter adapter) {
        View.OnClickListener oldListener = getOnClickListener(itemView);
        
        if (oldListener instanceof QuickMessageActionsClickListener) {
            QuickMessageActionsClickListener qrListener = (QuickMessageActionsClickListener) oldListener;
            qrListener.message = message;
            qrListener.adapter = adapter;
        } else {
            itemView.setOnClickListener(new QuickMessageActionsClickListener(unwrapQuickMessageActionsListener(oldListener), message, adapter));
        }
    }

    private static void restoreOriginalClickListener(View itemView) {
        View.OnClickListener oldListener = getOnClickListener(itemView);
        if (oldListener instanceof QuickMessageActionsClickListener) {
            itemView.setOnClickListener(((QuickMessageActionsClickListener) oldListener).original);
        } else if (isQuickMessageActionsListener(oldListener)) {
            itemView.setOnClickListener(unwrapQuickMessageActionsListener(oldListener));
        }
    }

    private static View.OnClickListener getOnClickListener(View view) {
        try {
            java.lang.reflect.Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo != null) {
                java.lang.reflect.Field onClickListenerField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
                onClickListenerField.setAccessible(true);
                return (View.OnClickListener) onClickListenerField.get(listenerInfo);
            }
        } catch (Exception e) { }
        return null;
    }

    private static boolean isQuickMessageActionsListener(Object listener) {
        return listener != null && listener.getClass().getName().endsWith("QuickMessageActions$QuickMessageActionsClickListener");
    }

    private static View.OnClickListener unwrapQuickMessageActionsListener(View.OnClickListener listener) {
        if (!isQuickMessageActionsListener(listener)) return listener;

        try {
            java.lang.reflect.Field originalField = listener.getClass().getDeclaredField("original");
            originalField.setAccessible(true);
            Object original = originalField.get(listener);
            return original instanceof View.OnClickListener ? (View.OnClickListener) original : null;
        } catch (Throwable ignored) {
            return listener;
        }
    }

    private static void dismissCurrentPopup() {
        if (currentPopup != null) {
            currentPopup.dismiss();
            currentPopup = null;
            popupMessageId = -1L;
        }
    }

    private class QuickMessageActionsClickListener implements View.OnClickListener {
        public final View.OnClickListener original;
        public Message message;
        public WidgetChatListAdapter adapter;
        private int clicks = 0;
        private Runnable pendingRunnable = null;

        public QuickMessageActionsClickListener(View.OnClickListener original, Message message, WidgetChatListAdapter adapter) {
            this.original = original;
            this.message = message;
            this.adapter = adapter;
        }

        @Override
        public void onClick(View v) {
            Message clickedMessage = message;
            WidgetChatListAdapter clickedAdapter = adapter;
            clicks++;
            if (pendingRunnable != null) {
                mainHandler.removeCallbacks(pendingRunnable);
                pendingRunnable = null;
            }
            if (original != null) {
                original.onClick(v);
            }
            pendingRunnable = () -> {
                if (clicks == 1) {
                    handleSingleTap(v, clickedMessage, clickedAdapter);
                }
                clicks = 0;
                pendingRunnable = null;
            };
            mainHandler.postDelayed(pendingRunnable, 300);
        }
    }

    private boolean isMe(Message msg) {
        try {
            long authorId = new com.discord.models.user.CoreUser(msg.getAuthor()).getId();
            long myId = StoreStream.getUsers().getMe().getId();
            return authorId == myId;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleSingleTap(View anchor, Message message, WidgetChatListAdapter adapter) {
        if (currentPopup != null && currentPopup.isShowing()) {
            boolean wasSame = popupMessageId == message.getId();
            currentPopup.dismiss();
            currentPopup = null;
            popupMessageId = -1L;
            if (wasSame) return; // Toggle off if clicked same message
        }

        Context context = anchor.getContext();
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        
        GradientDrawable background = new GradientDrawable();
        background.setColor(ColorCompat.getThemedColor(context, COLOR_BACKGROUND_TERTIARY_ATTR));
        background.setCornerRadius(DimenUtils.dpToPx(8));
        container.setBackground(background);
        container.setPadding(DimenUtils.dpToPx(4), DimenUtils.dpToPx(4), DimenUtils.dpToPx(4), DimenUtils.dpToPx(4));
        container.setElevation(DimenUtils.dpToPx(8));

        boolean includeMessageActions = showMessageActions();
        boolean includeQuickReactions = showQuickReactions();

        if (!includeMessageActions && !includeQuickReactions) {
            return;
        }

        if (includeMessageActions) {
            addQuickAction(container, message, "ic_reply_24dp", v -> onReply(message));
            if (isMe(message)) {
                addQuickAction(container, message, "ic_edit_24dp", v -> onEdit(message));
                addQuickAction(container, message, "ic_delete_24dp", v -> onDelete(message));
            }
        }

        currentPopup = new PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        currentPopup.setOutsideTouchable(true);
        currentPopup.setFocusable(true);
        currentPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        currentPopup.setOnDismissListener(() -> {
            if (currentPopup != null && currentPopup.getContentView() == container) {
                currentPopup = null;
                popupMessageId = -1L;
            }
        });

        popupMessageId = message.getId();

        if (!includeQuickReactions) {
            showPopupIfNotEmpty(anchor, container);
            return;
        }

        List<Emoji> cached = emojiCache.get(cacheKey(message.getChannelId()));
        if (cached != null) {
            populateButtons(container, adapter, message, cached);
            showPopupIfNotEmpty(anchor, container);
            return;
        }

        requestQuickEmojis(container, adapter, message, anchor);
    }

    private void addQuickAction(LinearLayout container, Message message, String iconName, View.OnClickListener listener) {
        int resId = Utils.getResId(iconName, "drawable");
        if (resId == 0) return;
        
        Context context = container.getContext();
        ImageView iv = new ImageView(context);
        iv.setImageResource(resId);
        
        int padding = DimenUtils.dpToPx(6);
        iv.setPadding(padding, padding, padding, padding);
        
        try {
            iv.setColorFilter(ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr")));
        } catch (Throwable ignored) {}
        
        iv.setOnClickListener(v -> {
            listener.onClick(v);
            if (currentPopup != null) {
                currentPopup.dismiss();
                currentPopup = null;
                popupMessageId = -1L;
            }
        });
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            DimenUtils.dpToPx(36),
            DimenUtils.dpToPx(36)
        );
        params.gravity = Gravity.CENTER;
        container.addView(iv, params);
    }

    private void onEdit(Message message) {
        try {
            WidgetChatListActions actions = new WidgetChatListActions();
            for (java.lang.reflect.Method m : WidgetChatListActions.class.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("edit")) {
                    m.setAccessible(true);
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 1 && types[0] == Message.class) {
                        m.invoke(actions, message);
                        return;
                    } else if (types.length == 2 && types[0] == WidgetChatListActions.class && types[1] == Message.class) {
                        m.invoke(null, actions, message);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("Failed to edit", e);
        }
    }

    private void onReply(Message message) {
        try {
            WidgetChatListActions actions = new WidgetChatListActions();
            Channel channel = StoreStream.getChannels().getChannel(message.getChannelId());
            for (java.lang.reflect.Method m : WidgetChatListActions.class.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("reply")) {
                    m.setAccessible(true);
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 2 && types[0] == Message.class && types[1] == Channel.class) {
                        m.invoke(actions, message, channel);
                        return;
                    } else if (types.length == 3 && types[0] == WidgetChatListActions.class && types[1] == Message.class && types[2] == Channel.class) {
                        m.invoke(null, actions, message, channel);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("Failed to reply", e);
        }
    }

    private void onDelete(Message message) {
        try {
            WidgetChatListActions actions = new WidgetChatListActions();
            for (java.lang.reflect.Method m : WidgetChatListActions.class.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("delete")) {
                    m.setAccessible(true);
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 1 && types[0] == Message.class) {
                        m.invoke(actions, message);
                        return;
                    } else if (types.length == 2 && types[0] == WidgetChatListActions.class && types[1] == Message.class) {
                        m.invoke(null, actions, message);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("Failed to delete", e);
            try {
                Object api = com.discord.utilities.rest.RestAPI.getApi();
                Object observable = api.getClass().getMethod("deleteMessage", long.class, long.class).invoke(api, message.getChannelId(), message.getId());
                if (observable != null) {
                    for (java.lang.reflect.Method sm : observable.getClass().getMethods()) {
                        if (sm.getName().equals("subscribe") && sm.getParameterTypes().length == 0) {
                            sm.invoke(observable);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private void requestQuickEmojis(LinearLayout container, WidgetChatListAdapter adapter, Message message, View anchor) {
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
                        if (currentPopup != null && currentPopup.getContentView() == container && popupMessageId == expectedMessageId) {
                            populateButtons(container, adapter, message, recent);
                            showPopupIfNotEmpty(anchor, container);
                        }
                    });
                }, throwable -> logger.error("QuickMessageActions failed to read frequent emojis", throwable));
            subscriptions.add(subscription);
        } catch (Throwable throwable) {
            logger.error("QuickMessageActions failed to request frequent emojis", throwable);
        }
    }

    private void showPopup(View anchor) {
        if (currentPopup == null) return;
        View messageText = anchor.findViewById(CHAT_TEXT_ID);
        View popupAnchor = messageText != null ? messageText : anchor;
        try {
            currentPopup.showAsDropDown(popupAnchor, DimenUtils.dpToPx(16), DimenUtils.dpToPx(4));
        } catch (Exception e) {
            logger.error("Failed to show QuickMessageActions popup", e);
        }
    }

    private void showPopupIfNotEmpty(View anchor, LinearLayout container) {
        if (container.getChildCount() == 0) {
            dismissCurrentPopup();
            return;
        }
        showPopup(anchor);
    }

    private void populateButtons(LinearLayout container, WidgetChatListAdapter adapter, Message message, List<Emoji> emojis) {
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            if (container.getChildAt(i) instanceof ReactionView) {
                container.removeViewAt(i);
            }
        }

        if (emojis == null || emojis.isEmpty()) {
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
                if (currentPopup != null) {
                    currentPopup.dismiss();
                    currentPopup = null;
                    popupMessageId = -1L;
                }
            });
            container.addView(button);
            added++;
        }
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

            CheckedSetting quickReactions = Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Show quick reactions",
                "Show recent emoji reaction buttons."
            );
            quickReactions.setChecked(showQuickReactions());
            quickReactions.setOnCheckedListener(checked -> {
                PLUGIN_SETTINGS.setBool(KEY_QUICK_REACTIONS, checked);
                dismissCurrentPopup();
            });
            addView(quickReactions);

            CheckedSetting messageActions = Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Show message actions",
                "Show reply, edit, and delete action buttons."
            );
            messageActions.setChecked(showMessageActions());
            messageActions.setOnCheckedListener(checked -> {
                PLUGIN_SETTINGS.setBool(KEY_MESSAGE_ACTIONS, checked);
                dismissCurrentPopup();
            });
            addView(messageActions);
        }
    }
}
