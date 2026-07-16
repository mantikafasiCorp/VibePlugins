package dev.autoaliu.generated.text2file;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.InsteadHook;
import com.aliucord.widgets.BottomSheet;
import com.discord.stores.StoreStream;
import com.discord.utilities.user.UserUtils;
import com.discord.views.CheckedSetting;
import com.discord.widgets.chat.MessageContent;
import com.discord.widgets.chat.MessageManager;
import com.discord.widgets.chat.input.ChatInputViewModel;
import com.discord.widgets.notice.WidgetNoticeDialog;
import com.lytefast.flexinput.model.Attachment;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

@AliucordPlugin
@SuppressWarnings({"unused", "unchecked"})
public final class Text2File extends Plugin {
    private static final String SETTINGS_NAME = "Text2File";
    private static final String KEY_ENABLED = "enabled";
    private static final int STANDARD_MESSAGE_LIMIT = 2000;
    private static final int PREMIUM_MESSAGE_LIMIT = 4000;
    private static final String CACHE_DIRECTORY = "Text2File";

    private volatile boolean stopped;

    public Text2File() {
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        stopped = false;
        Method sendMessage = ChatInputViewModel.class.getDeclaredMethod(
            "sendMessage",
            Context.class,
            MessageManager.class,
            MessageContent.class,
            List.class,
            boolean.class,
            Function1.class
        );

        patcher.patch(sendMessage, new InsteadHook(param -> {
            if (stopped || !settings.getBool(KEY_ENABLED, true)) {
                return invokeOriginal((Method) param.method, param.thisObject, param.args);
            }

            ChatInputViewModel viewModel = (ChatInputViewModel) param.thisObject;
            ChatInputViewModel.ViewState viewState = viewModel.getViewState();
            if (!(viewState instanceof ChatInputViewModel.ViewState.Loaded)
                || ((ChatInputViewModel.ViewState.Loaded) viewState).isEditing()) {
                return invokeOriginal((Method) param.method, param.thisObject, param.args);
            }

            MessageContent messageContent = (MessageContent) param.args[2];
            String text = messageContent.getTextContent();
            if (text == null || text.length() <= getMaximumMessageLength()) {
                return invokeOriginal((Method) param.method, param.thisObject, param.args);
            }

            Object[] replayArguments = param.args.clone();
            showConversionDialog((Context) param.args[0], viewModel, (Method) param.method, replayArguments, text);
            return null;
        }));
    }

    @Override
    public void stop(Context context) {
        stopped = true;
        patcher.unpatchAll();
        commands.unregisterAll();
        deleteCachedFiles(context);
    }

    private int getMaximumMessageLength() {
        try {
            return UserUtils.INSTANCE.isPremiumTier2(StoreStream.getUsers().getMe())
                ? PREMIUM_MESSAGE_LIMIT
                : STANDARD_MESSAGE_LIMIT;
        } catch (Throwable throwable) {
            logger.error("Failed to determine the current message limit", throwable);
            return STANDARD_MESSAGE_LIMIT;
        }
    }

    private void showConversionDialog(
        Context context,
        ChatInputViewModel viewModel,
        Method sendMessage,
        Object[] replayArguments,
        String text
    ) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        Function1<View, Unit> convert = view -> {
            accepted.set(true);
            convertAndSend(context, viewModel, sendMessage, replayArguments, text);
            return Unit.a;
        };
        Function1<Boolean, Unit> validationCallback = (Function1<Boolean, Unit>) replayArguments[5];

        WidgetNoticeDialog.show(
            Utils.getAppActivity().getSupportFragmentManager(),
            "Message is too long",
            "Convert the message to a text file and send it as an attachment?",
            "Convert and send",
            "Cancel",
            Collections.singletonMap(Utils.getResId("notice_ok", "id"), convert),
            null,
            null,
            null,
            null,
            true,
            null,
            0,
            () -> {
                if (!accepted.get()) validationCallback.invoke(Boolean.FALSE);
                return Unit.a;
            }
        );
    }

    private void convertAndSend(
        Context context,
        ChatInputViewModel viewModel,
        Method sendMessage,
        Object[] replayArguments,
        String text
    ) {
        Utils.threadPool.execute(() -> {
            try {
                File file = createTextFile(context, text);
                Attachment<File> attachment = new Attachment<>(
                    file.hashCode(),
                    Uri.fromFile(file),
                    file.getName(),
                    file,
                    false,
                    16,
                    null
                );

                List<? extends Attachment<?>> originalAttachments = (List<? extends Attachment<?>>) replayArguments[3];
                ArrayList<Attachment<?>> attachments = new ArrayList<>(originalAttachments);
                attachments.add(attachment);
                MessageContent originalContent = (MessageContent) replayArguments[2];
                replayArguments[2] = new MessageContent("", originalContent.getMentionedUsers());
                replayArguments[3] = attachments;

                Utils.mainThread.post(() -> {
                    if (stopped) return;
                    try {
                        XposedBridge.invokeOriginalMethod(sendMessage, viewModel, replayArguments);
                    } catch (Throwable throwable) {
                        logger.errorToast("Failed to send text file");
                        logger.error("Failed to replay message with text attachment", throwable);
                        ((Function1<Boolean, Unit>) replayArguments[5]).invoke(Boolean.FALSE);
                    }
                });
            } catch (Throwable throwable) {
                logger.errorToast("Failed to create text file");
                logger.error("Failed to convert long message to a file", throwable);
                Utils.mainThread.post(() -> ((Function1<Boolean, Unit>) replayArguments[5]).invoke(Boolean.FALSE));
            }
        });
    }

    private File createTextFile(Context context, String text) throws Exception {
        File directory = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create the Text2File cache directory");
        }

        File file = new File(directory, "message-" + System.currentTimeMillis() + ".txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private void deleteCachedFiles(Context context) {
        File directory = new File(context.getCacheDir(), CACHE_DIRECTORY);
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.delete()) logger.warn("Could not delete cached file " + file.getName());
            }
        }
        if (directory.exists() && !directory.delete()) logger.warn("Could not delete Text2File cache directory");
    }

    private static Object invokeOriginal(Method method, Object receiver, Object[] arguments) {
        try {
            return XposedBridge.invokeOriginalMethod(method, receiver, arguments);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    public static final class SettingsSheet extends BottomSheet {
        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);

            SettingsAPI api = new SettingsAPI(SETTINGS_NAME);
            CheckedSetting enabled = Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Convert long messages",
                "Offer to send messages over Discord's limit as a text file."
            );
            enabled.setChecked(api.getBool(KEY_ENABLED, true));
            enabled.setOnCheckedListener(checked -> api.setBool(KEY_ENABLED, checked));
            addView(enabled);
        }
    }
}
