package dev.autoaliu.generated.auraplugin;

import android.content.Context;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI;
import com.aliucord.entities.Plugin;
import com.discord.api.commands.ApplicationCommandType;

@AliucordPlugin(requiresRestart = false)
@SuppressWarnings("unused")
public final class AuraPlugin extends Plugin {
    private static final String USER_OPTION = "user";

    @Override
    public void start(Context context) {
        commands.registerCommand(
            "aura",
            "Sends aura @user.",
            Utils.createCommandOption(
                ApplicationCommandType.USER,
                USER_OPTION,
                "User to aura",
                null,
                true
            ),
            commandContext -> {
                long userId = commandContext.getRequiredLong(USER_OPTION);
                return new CommandsAPI.CommandResult("aura <@" + userId + ">", null, true);
            }
        );
    }

    @Override
    public void stop(Context context) {
        commands.unregisterAll();
        patcher.unpatchAll();
    }
}
