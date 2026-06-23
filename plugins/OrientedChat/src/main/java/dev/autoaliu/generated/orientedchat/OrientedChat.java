package dev.autoaliu.generated.orientedchat;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.mg_recycler.MGRecyclerAdapter;
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder;
import com.discord.views.CheckedSetting;
import com.discord.widgets.chat.list.WidgetChatList;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter;

@AliucordPlugin
@SuppressWarnings("unused")
public class OrientedChat extends Plugin {
    private static final String SETTINGS_NAME = "OrientedChat";
    private static final String KEY_MODE = "mode";
    private static final int MODE_DEFAULT = 0;
    private static final int MODE_UPSIDE_DOWN = 1;
    private static final int MODE_HORIZONTAL = 2;
    private static final int CHAT_LIST_RECYCLER_ID = Utils.getResId("chat_list_recycler", "id");

    public OrientedChat() {
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
            WidgetChatList.class,
            "onViewBound",
            new Class<?>[] { View.class },
            new Hook(param -> {
                View view = param.args[0] instanceof View ? (View) param.args[0] : null;
                RecyclerView recyclerView = findChatRecycler(view);
                if (recyclerView != null) applyMode(recyclerView);
            })
        );

        patcher.patch(
            WidgetChatList.class,
            "onViewBoundOrOnResume",
            new Class<?>[] {},
            new Hook(param -> {
                WidgetChatList chatList = (WidgetChatList) param.thisObject;
                RecyclerView recyclerView = findChatRecycler(chatList.getView());
                if (recyclerView != null) applyMode(recyclerView);
            })
        );

        patcher.patch(
            MGRecyclerAdapter.class,
            "onBindViewHolder",
            new Class<?>[] { MGRecyclerViewHolder.class, int.class },
            new Hook(param -> sizeRow((MGRecyclerViewHolder<?, ?>) param.args[0]))
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
    }

    private static RecyclerView findChatRecycler(View root) {
        if (root == null) return null;
        View recycler = root.findViewById(CHAT_LIST_RECYCLER_ID);
        return recycler instanceof RecyclerView ? (RecyclerView) recycler : null;
    }

    private void applyMode(RecyclerView recyclerView) {
        RecyclerView.LayoutManager rawLayoutManager = recyclerView.getLayoutManager();
        if (!(rawLayoutManager instanceof LinearLayoutManager)) return;

        int mode = settings.getInt(KEY_MODE, MODE_DEFAULT);
        LinearLayoutManager layoutManager = (LinearLayoutManager) rawLayoutManager;
        boolean horizontal = mode == MODE_HORIZONTAL;
        layoutManager.setOrientation(horizontal ? LinearLayoutManager.HORIZONTAL : LinearLayoutManager.VERTICAL);
        layoutManager.setReverseLayout(mode == MODE_DEFAULT || horizontal);
        layoutManager.setStackFromEnd(false);
        recyclerView.setHorizontalScrollBarEnabled(horizontal);
        recyclerView.setVerticalScrollBarEnabled(!horizontal);
        recyclerView.setHasFixedSize(!horizontal);
    }

    private void sizeRow(MGRecyclerViewHolder<?, ?> holder) {
        if (holder == null || holder.itemView == null) return;

        int mode = settings.getInt(KEY_MODE, MODE_DEFAULT);
        RecyclerView recyclerView = null;
        View parent = holder.itemView.getParent() instanceof View ? (View) holder.itemView.getParent() : null;
        if (parent instanceof RecyclerView) recyclerView = (RecyclerView) parent;
        if (recyclerView == null || recyclerView.getId() != CHAT_LIST_RECYCLER_ID) return;

        ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
        if (params == null) return;

        int wantedWidth = mode == MODE_HORIZONTAL && recyclerView != null && recyclerView.getWidth() > 0
            ? recyclerView.getWidth()
            : ViewGroup.LayoutParams.MATCH_PARENT;
        int wantedHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params.width != wantedWidth || params.height != wantedHeight) {
            params.width = wantedWidth;
            params.height = wantedHeight;
            holder.itemView.setLayoutParams(params);
        }
    }

    public static final class SettingsSheet extends BottomSheet {
        private final SettingsAPI api = new SettingsAPI(SETTINGS_NAME);

        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);

            int mode = api.getInt(KEY_MODE, MODE_DEFAULT);
            addModeSetting("Default", "Newest messages stay at the bottom.", MODE_DEFAULT, mode);
            addModeSetting("Upside down", "Newest messages appear at the top.", MODE_UPSIDE_DOWN, mode);
            addModeSetting("Horizontal", "Messages are browsed side to side.", MODE_HORIZONTAL, mode);
        }

        private void addModeSetting(String title, String subtitle, int value, int selectedMode) {
            CheckedSetting setting = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, title, subtitle);
            setting.setChecked(selectedMode == value);
            setting.setOnCheckedListener(checked -> {
                if (checked) {
                    api.setInt(KEY_MODE, value);
                    dismiss();
                }
            });
            addView(setting);
        }
    }
}
