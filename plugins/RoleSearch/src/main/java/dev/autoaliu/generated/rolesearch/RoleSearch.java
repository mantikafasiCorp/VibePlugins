package dev.autoaliu.generated.rolesearch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.widget.LinearLayout;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.DimenUtils;
import com.discord.api.role.GuildRole;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.mg_recycler.DragAndDropAdapter;
import com.discord.widgets.servers.WidgetServerSettingsRoles;
import com.discord.widgets.servers.WidgetServerSettingsRolesAdapter;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

@AliucordPlugin
@SuppressWarnings("unused")
public class RoleSearch extends Plugin {
    private static final String SEARCH_TAG = "RoleSearch.SearchBox";
    private static final WeakHashMap<WidgetServerSettingsRolesAdapter, SearchState> STATES = new WeakHashMap<>();
    private static Field adapterField;

    @Override
    public void start(Context context) throws Throwable {
        adapterField = WidgetServerSettingsRoles.class.getDeclaredField("adapter");
        adapterField.setAccessible(true);

        patcher.patch(
            WidgetServerSettingsRoles.class.getDeclaredMethod("onViewBound", View.class),
            new Hook(param -> installSearchBox((WidgetServerSettingsRoles) param.thisObject, (View) param.args[0]))
        );

        patcher.patch(
            WidgetServerSettingsRoles.class.getDeclaredMethod("onViewBoundOrOnResume"),
            new Hook(param -> {
                WidgetServerSettingsRoles fragment = (WidgetServerSettingsRoles) param.thisObject;
                View root = fragment.getView();
                if (root != null) installSearchBox(fragment, root);
            })
        );

        patcher.patch(
            WidgetServerSettingsRolesAdapter.class.getDeclaredMethod(
                "configure",
                List.class,
                Function1.class,
                Function1.class
            ),
            new PreHook(param -> {
                WidgetServerSettingsRolesAdapter adapter = (WidgetServerSettingsRolesAdapter) param.thisObject;
                SearchState state = getState(adapter);
                state.items = new ArrayList<>((List<DragAndDropAdapter.Payload>) param.args[0]);
                state.roleSelectedListener = (Function1<? super GuildRole, Unit>) param.args[1];
                state.roleDropListener = (Function1<? super Map<String, Integer>, Unit>) param.args[2];
                param.args[0] = filterItems(state.items, state.query);
            })
        );

        patcher.patch(
            WidgetServerSettingsRolesAdapter.class.getDeclaredMethod("isValidMove", int.class, int.class),
            new PreHook(param -> {
                SearchState state = STATES.get((WidgetServerSettingsRolesAdapter) param.thisObject);
                if (state != null && !state.query.isEmpty()) param.setResult(false);
            })
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
        STATES.clear();
        adapterField = null;
    }

    private static SearchState getState(WidgetServerSettingsRolesAdapter adapter) {
        SearchState state = STATES.get(adapter);
        if (state == null) {
            state = new SearchState();
            STATES.put(adapter, state);
        }
        return state;
    }

    private static void installSearchBox(WidgetServerSettingsRoles fragment, View root) {
        try {
            WidgetServerSettingsRolesAdapter adapter = (WidgetServerSettingsRolesAdapter) adapterField.get(fragment);
            if (adapter == null || !(root instanceof ViewGroup)) return;

            AppBarLayout appBar = findAppBar((ViewGroup) root);
            if (appBar == null || hasSearchBox(appBar)) return;

            Context context = root.getContext();
            SearchState state = getState(adapter);
            TextInputEditText editText = newSearchBox(context);
            CardView card = wrapInMembersStyleHeader(context, editText);
            appBar.addView(card);

            editText.setText(state.query);
            editText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    state.query = String.valueOf(s).trim().toLowerCase(Locale.ROOT);
                    if (state.items != null && state.roleSelectedListener != null && state.roleDropListener != null) {
                        adapter.configure(state.items, state.roleSelectedListener, state.roleDropListener);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        } catch (Throwable ignored) {
            // Optional UI enhancement; Discord should continue normally if this screen changes.
        }
    }

    private static TextInputEditText newSearchBox(Context context) {
        int inputStyle = getStyleId("UiKit.TextInputLayout.EditText.SingleLine.Search");
        Context inputContext = inputStyle != 0 ? new ContextThemeWrapper(context, inputStyle) : context;
        TextInputEditText editText = new TextInputEditText(inputContext);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editText.setTextColor(themedColor(context, "colorTextNormal"));
        editText.setHintTextColor(themedColor(context, "colorTextMuted"));
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        return editText;
    }

    private static CardView wrapInMembersStyleHeader(Context context, TextInputEditText editText) {
        int layoutStyle = getStyleId("UiKit.TextInputLayout.Search");
        Context layoutContext = layoutStyle != 0 ? new ContextThemeWrapper(context, layoutStyle) : context;

        TextInputLayout inputLayout = new TextInputLayout(layoutContext);
        int iconTint = themedColor(context, "colorInteractiveNormal");
        inputLayout.setHint("Search Roles");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
        inputLayout.setBoxBackgroundColor(themedColor(context, "colorBackgroundTertiary"));
        inputLayout.setBoxStrokeWidth(0);
        inputLayout.setBoxStrokeWidthFocused(0);
        inputLayout.setStartIconDrawable(com.aliucord.Utils.getResId("ic_search_grey_24dp", "drawable"));
        inputLayout.setStartIconTintList(ColorStateList.valueOf(iconTint));
        inputLayout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        inputLayout.setEndIconTintList(ColorStateList.valueOf(iconTint));
        inputLayout.addView(editText, new TextInputLayout.LayoutParams(
            TextInputLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(inputLayout, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));

        CardView card = new CardView(context);
        card.setTag(SEARCH_TAG);
        card.setCardBackgroundColor(themedColor(context, "colorBackgroundTertiary"));
        card.setRadius(DimenUtils.dpToPx(4));
        card.addView(header, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AppBarLayout.LayoutParams params = new AppBarLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int margin = DimenUtils.dpToPx(8);
        params.setMargins(margin, margin, margin, margin);
        card.setLayoutParams(params);
        return card;
    }

    private static int getStyleId(String name) {
        try {
            return com.aliucord.Utils.getResId(name, "style");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int themedColor(Context context, String attrName) {
        int attr = com.aliucord.Utils.getResId(attrName, "attr");
        return attr != 0 ? ColorCompat.getThemedColor(context, attr) : 0;
    }

    private static AppBarLayout findAppBar(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof AppBarLayout) return (AppBarLayout) child;
            if (child instanceof ViewGroup && !(child instanceof Toolbar)) {
                AppBarLayout nested = findAppBar((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean hasSearchBox(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            if (SEARCH_TAG.equals(root.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static List<DragAndDropAdapter.Payload> filterItems(List<DragAndDropAdapter.Payload> items, String query) {
        if (query == null || query.isEmpty()) return items;

        ArrayList<DragAndDropAdapter.Payload> filtered = new ArrayList<>();
        for (DragAndDropAdapter.Payload item : items) {
            if (!(item instanceof WidgetServerSettingsRolesAdapter.RoleItem)) continue;

            WidgetServerSettingsRolesAdapter.RoleItem roleItem = (WidgetServerSettingsRolesAdapter.RoleItem) item;
            GuildRole role = roleItem.getRole();
            String name = role != null ? role.g() : null;
            if (name != null && name.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private static final class SearchState {
        private String query = "";
        private List<DragAndDropAdapter.Payload> items;
        private Function1<? super GuildRole, Unit> roleSelectedListener;
        private Function1<? super Map<String, Integer>, Unit> roleDropListener;
    }
}
