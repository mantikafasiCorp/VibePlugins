package dev.autoaliu.generated.favoriteguild;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.DimenUtils;
import com.discord.app.AppFragment;
import com.discord.api.channel.Channel;
import com.discord.api.guild.GuildExplicitContentFilter;
import com.discord.api.guild.GuildMaxVideoChannelUsers;
import com.discord.api.guild.GuildVerificationLevel;
import com.discord.models.guild.Guild;
import com.discord.stores.StoreStream;
import com.discord.utilities.channel.ChannelSelector;
import com.discord.utilities.color.ColorCompat;
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions;
import com.discord.widgets.guilds.list.GuildListItem;
import com.discord.widgets.guilds.list.WidgetGuildListAdapter;
import com.discord.widgets.guilds.list.WidgetGuildsList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@AliucordPlugin
@SuppressWarnings("unused")
public final class FavoriteGuild extends Plugin {
    private static final String FAVORITES_KEY = "favoriteChannelIds";
    private static final long FAVORITE_GUILD_ID = -740_057_001_001L;
    private static final String FAVORITE_VIEW_TAG = "favoriteguild_action";
    private static FavoriteGuild instance;

    @Override
    public void start(Context context) throws Throwable {
        instance = this;

        patcher.patch(
            WidgetGuildListAdapter.class,
            "setItems",
            new Class<?>[] { List.class, boolean.class },
            new PreHook(param -> param.args[0] = withFavoriteGuild((List<?>) param.args[0]))
        );

        patcher.patch(
            WidgetGuildsList.class,
            "onItemClicked",
            new Class<?>[] { View.class, GuildListItem.class },
            new PreHook(param -> {
                GuildListItem item = (GuildListItem) param.args[1];
                if (!isFavoriteGuildItem(item)) return;

                Utils.openPageWithProxy(((View) param.args[0]).getContext(), new FavoritesPage());
                param.setResult(null);
            })
        );

        patcher.patch(
            WidgetGuildsList.class,
            "onItemLongPressed",
            new Class<?>[] { View.class, GuildListItem.class },
            new PreHook(param -> {
                GuildListItem item = (GuildListItem) param.args[1];
                if (isFavoriteGuildItem(item)) param.setResult(null);
            })
        );

        patcher.patch(
            WidgetChannelsListItemChannelActions.class,
            "access$configureUI",
            new Class<?>[] {
                WidgetChannelsListItemChannelActions.class,
                WidgetChannelsListItemChannelActions.Model.class
            },
            new Hook(param -> addFavoriteAction(
                (WidgetChannelsListItemChannelActions) param.args[0],
                (WidgetChannelsListItemChannelActions.Model) param.args[1]
            ))
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
        instance = null;
    }

    private static boolean isFavoriteGuildItem(GuildListItem item) {
        return item instanceof GuildListItem.GuildItem
            && ((GuildListItem.GuildItem) item).getGuild().getId() == FAVORITE_GUILD_ID;
    }

    private List<GuildListItem> withFavoriteGuild(List<?> original) {
        ArrayList<GuildListItem> items = new ArrayList<>();
        for (Object item : original) {
            if (item instanceof GuildListItem && !isFavoriteGuildItem((GuildListItem) item)) {
                items.add((GuildListItem) item);
            }
        }

        int insertAt = 0;
        if (!items.isEmpty() && items.get(0) instanceof GuildListItem.FriendsItem) insertAt = 1;
        items.add(insertAt, new GuildListItem.GuildItem(
            createFavoriteGuild(),
            0,
            false,
            false,
            false,
            null,
            false,
            false,
            false,
            null,
            null,
            false,
            false,
            false,
            false
        ));
        return items;
    }

    private static Guild createFavoriteGuild() {
        return new Guild(
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            "Favorites",
            "FavoriteGuild Plugin",
            0,
            FAVORITE_GUILD_ID,
            null,
            0L,
            null,
            GuildVerificationLevel.NONE,
            GuildExplicitContentFilter.NONE,
            false,
            0,
            0,
            null,
            null,
            Collections.emptySet(),
            0,
            null,
            null,
            0,
            0,
            0,
            null,
            null,
            null,
            "en-US",
            null,
            GuildMaxVideoChannelUsers.Unlimited.INSTANCE,
            null,
            0,
            false,
            null
        );
    }

    private void addFavoriteAction(WidgetChannelsListItemChannelActions sheet, WidgetChannelsListItemChannelActions.Model model) {
        if (model == null) return;
        Channel channel = model.getChannel();
        if (channel == null || channel.i() == 0L) return;

        View root = sheet.getView();
        if (!(root instanceof NestedScrollView)) return;
        View child = ((NestedScrollView) root).getChildAt(0);
        if (!(child instanceof LinearLayout)) return;
        LinearLayout layout = (LinearLayout) child;
        if (layout.findViewWithTag(FAVORITE_VIEW_TAG) != null) return;

        TextView action = createActionTextView(layout.getContext(), layout);
        action.setTag(FAVORITE_VIEW_TAG);
        action.setText(isFavorite(channel.k()) ? "Remove from Favorites" : "Add to Favorites");
        action.setOnClickListener(view -> {
            boolean added = toggleFavorite(channel.k());
            Toast.makeText(
                view.getContext(),
                added ? "Added to FavoriteGuild" : "Removed from FavoriteGuild",
                Toast.LENGTH_SHORT
            ).show();
            sheet.dismiss();
        });
        layout.addView(action);
    }

    private static TextView createActionTextView(Context context, LinearLayout layout) {
        TextView action = new TextView(context);
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setMinHeight(dp(48));
        action.setPadding(dp(16), 0, dp(16), 0);
        action.setTextColor(themedColor(context, "colorTextNormal"));
        action.setTextSize(16f);

        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof TextView) {
                TextView source = (TextView) child;
                action.setTextColor(source.getTextColors());
                action.setTextSize(0, source.getTextSize());
                action.setTypeface(source.getTypeface());
                break;
            }
        }

        action.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return action;
    }

    private Set<Long> getFavorites() {
        String stored = settings.getString(FAVORITES_KEY, "");
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (stored == null || stored.trim().isEmpty()) return ids;

        for (String part : stored.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) ids.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private void saveFavorites(Set<Long> ids) {
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (builder.length() > 0) builder.append(',');
            builder.append(id);
        }
        settings.setString(FAVORITES_KEY, builder.toString());
    }

    private boolean isFavorite(long channelId) {
        return getFavorites().contains(channelId);
    }

    private boolean toggleFavorite(long channelId) {
        Set<Long> ids = getFavorites();
        boolean added;
        if (ids.contains(channelId)) {
            ids.remove(channelId);
            added = false;
        } else {
            ids.add(channelId);
            added = true;
        }
        saveFavorites(ids);
        return added;
    }

    private static int dp(int value) {
        return DimenUtils.dpToPx(value);
    }

    private static int themedColor(Context context, String attrName) {
        int attr = Utils.getResId(attrName, "attr");
        int color = ColorCompat.getThemedColor(context, attr);
        return color == 0 ? Color.WHITE : color;
    }

    private static void applySelectableBackground(View view) {
        TypedValue typedValue = new TypedValue();
        if (view.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
            view.setBackgroundResource(typedValue.resourceId);
        }
    }

    public static final class FavoritesPage extends AppFragment {
        @Nullable
        @Override
        public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
        ) {
            Context context = inflater.getContext();
            ScrollView scrollView = new ScrollView(context);
            scrollView.setFillViewport(true);
            scrollView.setBackgroundColor(themedColor(context, "colorBackgroundPrimary"));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(18), dp(16), dp(24));
            scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            ));

            content.addView(title(context, "Favorites"));
            content.addView(subtitle(context, "Favorited channels"));

            FavoriteGuild plugin = instance;
            if (plugin == null) {
                content.addView(empty(context, "FavoriteGuild is not running."));
                return scrollView;
            }

            Set<Long> favorites = new HashSet<>(plugin.getFavorites());
            if (favorites.isEmpty()) {
                content.addView(empty(context, "Favorite channels from their channel action menu."));
                return scrollView;
            }

            for (Long channelId : favorites) {
                Channel channel = StoreStream.getChannels().getChannel(channelId);
                if (channel == null) continue;

                Guild guild = StoreStream.getGuilds().getGuild(channel.i());
                content.addView(row(context, channel, guild));
            }

            if (content.getChildCount() == 2) {
                content.addView(empty(context, "No favorited channels are available right now."));
            }
            return scrollView;
        }

        @Override
        public void onViewBound(View view) {
            super.onViewBound(view);
            setActionBarTitle("Favorites");
            setActionBarSubtitle("FavoriteGuild");
            setActionBarDisplayHomeAsUpEnabled(true);
        }

        private View row(Context context, Channel channel, Guild guild) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setMinimumHeight(dp(62));
            applySelectableBackground(row);
            row.setOnClickListener(view -> {
                ChannelSelector.getInstance().selectChannel(channel, null, null);
                if (getActivity() != null) getActivity().finish();
            });

            TextView name = new TextView(context);
            name.setText("#" + safeName(channel.p()));
            name.setTextSize(16f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(themedColor(context, "colorHeaderPrimary"));
            row.addView(name);

            TextView source = new TextView(context);
            source.setText("from " + (guild == null ? "Unknown guild" : guild.getName()));
            source.setTextSize(13f);
            source.setTextColor(themedColor(context, "colorHeaderSecondary"));
            row.addView(source);
            return row;
        }

        private static TextView title(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(24f);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setTextColor(themedColor(context, "colorHeaderPrimary"));
            view.setPadding(0, 0, 0, dp(2));
            return view;
        }

        private static TextView subtitle(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(14f);
            view.setTextColor(themedColor(context, "colorHeaderSecondary"));
            view.setPadding(0, 0, 0, dp(18));
            return view;
        }

        private static TextView empty(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(15f);
            view.setTextColor(themedColor(context, "colorTextMuted"));
            view.setPadding(dp(12), dp(18), dp(12), dp(18));
            return view;
        }

        private static String safeName(String name) {
            return name == null || name.length() == 0 ? "unknown-channel" : name;
        }
    }
}
