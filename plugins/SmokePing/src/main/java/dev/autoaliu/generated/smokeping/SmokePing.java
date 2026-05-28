package dev.autoaliu.generated.smokeping;

import android.content.Context;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI;
import com.aliucord.entities.Plugin;

@AliucordPlugin(requiresRestart = false)
@SuppressWarnings("unused")
public final class SmokePing extends Plugin {
    @Override
    public void start(Context context) {
        commands.registerCommand(
            "smokeping",
            "Replies with Pong from Docker.",
            commandContext -> new CommandsAPI.CommandResult("Pong from Docker")
        );
    }

    @Override
    public void stop(Context context) {
        commands.unregisterAll();
        patcher.unpatchAll();
    }
}
