import re
import sys

def main():
    with open('plugins/QuickMessageActions/src/main/java/dev/autoaliu/generated/quickmessageactions/QuickMessageActions.java', 'r') as f:
        content = f.read()

    # Add activeMessageId and activeRootView
    content = content.replace(
        'private final List<Subscription> subscriptions = Collections.synchronizedList(new ArrayList<>());',
        'private final List<Subscription> subscriptions = Collections.synchronizedList(new ArrayList<>());\n    private long activeMessageId = -1L;\n    private View activeRootView = null;'
    )

    # Replace configureQuickMessageActionsions
    configure_old = re.search(r'private void configureQuickMessageActionsions\(.*?requestQuickEmojis\(container, adapter, message\);\n    }', content, re.DOTALL)
    if not configure_old:
        print("Could not find configureQuickMessageActionsions")
        sys.exit(1)
        
    configure_new = """private void configureQuickMessageActionsions(WidgetChatListAdapterItemMessage row, ChatListEntry entry) {
        if (row == null || row.itemView == null) return;

        if (!settings.getBool(KEY_ENABLED, true) || !(entry instanceof MessageEntry)) {
            removeContainer(row.itemView);
            return;
        }

        MessageEntry messageEntry = (MessageEntry) entry;
        Message message = messageEntry.getMessage();
        if (!canShowFor(message, messageEntry)) {
            removeContainer(row.itemView);
            return;
        }

        WidgetChatListAdapter adapter = WidgetChatListAdapterItemMessage.access$getAdapter$p(row);

        row.itemView.setOnClickListener(v -> {
            if (activeMessageId == message.getId()) {
                activeMessageId = -1L;
                removeContainer(row.itemView);
                activeRootView = null;
            } else {
                activeMessageId = message.getId();
                if (activeRootView != null && activeRootView != row.itemView) {
                    removeContainer(activeRootView);
                }
                activeRootView = row.itemView;
                int position = row.getAdapterPosition();
                if (position != -1) {
                    adapter.notifyItemChanged(position);
                }
            }
        });

        if (activeMessageId != message.getId()) {
            removeContainer(row.itemView);
            return;
        }

        LinearLayout container = ensureContainer(row.itemView);
        if (container == null) return;

        container.setTag(CONTAINER_TAG);
        container.setTag(COUNTER_TEXT_SWITCHER_ID, message.getId());
        container.removeAllViews();

        List<Emoji> cached = emojiCache.get(cacheKey(message.getChannelId()));
        if (cached != null) {
            populateButtons(container, adapter, message, cached);
            return;
        }

        requestQuickEmojis(container, adapter, message);
    }"""
    content = content.replace(configure_old.group(0), configure_new)

    # Modify populateButtons to close container on click
    populate_old = re.search(r'private void populateButtons\(.*?container\.setVisibility\(added == 0 \? View\.GONE : View\.VISIBLE\);\n    }', content, re.DOTALL)
    if not populate_old:
        print("Could not find populateButtons")
        sys.exit(1)
        
    populate_new = populate_old.group(0).replace(
        'adapter.onReactionClicked(message.getId(), reaction, true);',
        'adapter.onReactionClicked(message.getId(), reaction, true);\n                activeMessageId = -1L;\n                activeRootView = null;\n                container.setVisibility(View.GONE);'
    )
    content = content.replace(populate_old.group(0), populate_new)

    # Modify ensureContainer
    ensure_old = re.search(r'private static LinearLayout ensureContainer\(View root\) \{.*?return container;\n    \}', content, re.DOTALL)
    if not ensure_old:
        print("Could not find ensureContainer")
        sys.exit(1)
        
    ensure_new = """private static LinearLayout ensureContainer(View root) {
        if (!(root instanceof ConstraintLayout)) return null;

        View existing = root.findViewWithTag(CONTAINER_TAG);
        if (existing instanceof LinearLayout) return (LinearLayout) existing;

        Context context = root.getContext();
        LinearLayout container = new LinearLayout(context);
        container.setId(View.generateViewId());
        container.setTag(CONTAINER_TAG);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        
        GradientDrawable background = new GradientDrawable();
        background.setColor(ColorCompat.getThemedColor(context, COLOR_BACKGROUND_TERTIARY_ATTR));
        background.setCornerRadius(DimenUtils.dpToPx(8));
        container.setBackground(background);
        container.setPadding(DimenUtils.dpToPx(4), DimenUtils.dpToPx(4), DimenUtils.dpToPx(4), DimenUtils.dpToPx(4));
        container.setElevation(DimenUtils.dpToPx(8));

        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        View messageText = root.findViewById(CHAT_TEXT_ID);
        int anchorId = messageText != null ? CHAT_TEXT_ID : ConstraintLayout.LayoutParams.PARENT_ID;
        
        if (messageText != null) {
            params.startToStart = anchorId;
            params.topToBottom = anchorId;
            params.setMarginTop(DimenUtils.dpToPx(4));
        } else {
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            params.setMarginStart(DimenUtils.dpToPx(72));
            params.setMarginBottom(DimenUtils.dpToPx(4));
        }
        
        ((ConstraintLayout) root).addView(container, params);
        return container;
    }"""
    content = content.replace(ensure_old.group(0), ensure_new)

    # Remove constrainMessageText and restoreMessageText
    constrain_old = re.search(r'private static void constrainMessageText\(View root, View container\) \{.*?\}\n\n    private static void restoreMessageText\(View root\) \{.*?\}\n', content, re.DOTALL)
    if constrain_old:
        content = content.replace(constrain_old.group(0), '')

    with open('plugins/QuickMessageActions/src/main/java/dev/autoaliu/generated/quickmessageactions/QuickMessageActions.java', 'w') as f:
        f.write(content)
        
    print("Updated successfully")

if __name__ == "__main__":
    main()
