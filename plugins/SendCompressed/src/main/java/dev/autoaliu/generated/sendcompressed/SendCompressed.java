package dev.autoaliu.generated.sendcompressed;

import android.content.ContentResolver;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.opengl.EGL14;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.FragmentManager;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.InsteadHook;
import com.aliucord.utils.DimenUtils;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.attachments.AttachmentUtilsKt;
import com.discord.utilities.color.ColorCompat;
import com.discord.widgets.chat.MessageContent;
import com.discord.widgets.chat.MessageManager;
import com.discord.widgets.chat.input.ChatInputViewModel;
import com.discord.widgets.chat.input.attachments.AttachmentBottomSheet;
import com.discord.views.CheckedSetting;
import com.lytefast.flexinput.model.Attachment;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;
import com.otaliastudios.opengl.core.EglNativeConfigChooser;
import com.otaliastudios.opengl.internal.EglDisplay;
import com.otaliastudios.opengl.internal.EglConfig;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

@AliucordPlugin
@SuppressWarnings({"unused", "unchecked", "rawtypes"})
public final class SendCompressed extends Plugin {
    private static final String SETTINGS_NAME = "SendCompressed";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_IMAGES = "images";
    private static final String KEY_VIDEOS = "videos";
    private static final String KEY_AGGRESSIVE = "aggressive";
    private static final String KEY_SKIP_SMALL = "skipSmall";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_TARGET_MB = "targetMb";
    private static final long DEFAULT_TARGET_BYTES = 10L * 1024L * 1024L;
    private static final long SMALL_FILE_BYTES = 10L * 1024L * 1024L;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x00000040;
    private static final int EGL_RECORDABLE_ANDROID = 0x00003142;
    private static final int MAX_WAIT_MINUTES = 8;
    private static final String NOTIFICATION_CHANNEL_ID = "sendcompressed_progress";
    private static final int NOTIFICATION_ID = 0x53434d50;
    private static final String ACTION_CANCEL = "dev.autoaliu.generated.sendcompressed.CANCEL";
    private static final int COLOR_HEADER_SECONDARY_ATTR = Utils.getResId("colorHeaderSecondary", "attr");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private volatile boolean cancelRequested;
    private Method sendMessageMethod;
    private ExecutorService compressionExecutor;
    private HandlerThread transcoderCallbackThread;
    private Handler transcoderCallbackHandler;
    private volatile Future<?> activeCompressionTask;
    private volatile Future<Void> activeTranscodeTask;
    private volatile WeakReference<AttachmentBottomSheet> activeAttachmentSheet = new WeakReference<>(null);
    private BroadcastReceiver cancelReceiver;

    public SendCompressed() {
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        stopped = false;
        compressionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "SendCompressed-worker");
            thread.setDaemon(true);
            return thread;
        });
        transcoderCallbackThread = new HandlerThread("SendCompressed-transcoder", Process.THREAD_PRIORITY_BACKGROUND);
        transcoderCallbackThread.start();
        transcoderCallbackHandler = new Handler(transcoderCallbackThread.getLooper());

        registerCancelReceiver(context);
        patchTranscoderEglConfigChooser();
        patchAttachmentBottomSheetShow();
        sendMessageMethod = ChatInputViewModel.class.getDeclaredMethod(
            "sendMessage",
            Context.class,
            MessageManager.class,
            MessageContent.class,
            List.class,
            boolean.class,
            Function1.class
        );

        patcher.patch(sendMessageMethod, new InsteadHook(param -> {
            if (stopped || !settings.getBool(KEY_ENABLED, true)) {
                return invokeOriginal((Method) param.method, param.thisObject, param.args);
            }

            boolean replayedWithCompressedAttachments = (boolean) param.args[4];
            List<? extends Attachment<?>> attachments = (List<? extends Attachment<?>>) param.args[3];
            Context sendContext = (Context) param.args[0];
            if (replayedWithCompressedAttachments || attachments == null || attachments.isEmpty() || !hasCompressibleAttachment(sendContext, attachments)) {
                return invokeOriginal((Method) param.method, param.thisObject, param.args);
            }

            ChatInputViewModel viewModel = (ChatInputViewModel) param.thisObject;
            Object[] args = param.args.clone();
            cancelRequested = false;
            SendDestination destination = captureDestination(viewModel);
            Function1<Boolean, Unit> callback = (Function1<Boolean, Unit>) args[5];
            args[5] = (Function1<Boolean, Unit>) success -> {
                if (!Boolean.TRUE.equals(success)) return callback.invoke(Boolean.FALSE);
                return Unit.a;
            };
            ExecutorService executor = compressionExecutor;
            if (executor == null || executor.isShutdown()) {
                return invokeOriginal((Method) param.method, param.thisObject, param.args);
            }
            activeCompressionTask = executor.submit(() -> compressAndReplay(viewModel, args, destination));
            releaseInputUi(callback);
            return null;
        }));
    }

    private void patchTranscoderEglConfigChooser() throws NoSuchMethodException {
        patcher.patch(EglNativeConfigChooser.class.getDeclaredMethod(
            "getConfig$library_release",
            EglDisplay.class,
            int.class,
            boolean.class
        ), new InsteadHook(param -> chooseEglConfig((EglDisplay) param.args[0], (int) param.args[1], (boolean) param.args[2])));
    }

    private void patchAttachmentBottomSheetShow() throws NoSuchMethodException {
        patcher.patch(AttachmentBottomSheet.Companion.class.getDeclaredMethod(
            "show",
            FragmentManager.class,
            Attachment.class,
            Function0.class,
            Function1.class,
            Function0.class
        ), new InsteadHook(param -> {
            try {
                AttachmentBottomSheet sheet = (AttachmentBottomSheet) XposedBridge.invokeOriginalMethod((Method) param.method, param.thisObject, param.args);
                activeAttachmentSheet = new WeakReference<>(sheet);
                return sheet;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }));
    }

    private EglConfig chooseEglConfig(EglDisplay display, int version, boolean recordable) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;
        if (version >= 3) renderableType |= EGL_OPENGL_ES3_BIT_KHR;

        int[] spec = new int[] {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            recordable ? EGL_RECORDABLE_ANDROID : EGL14.EGL_NONE, recordable ? 1 : 0,
            EGL14.EGL_NONE
        };
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] count = new int[1];
        boolean found = EGL14.eglChooseConfig(display.getNative(), spec, 0, configs, 0, configs.length, count, 0);
        if (!found || count[0] <= 0 || configs[0] == null) return null;
        return new EglConfig(configs[0]);
    }

    @Override
    public void stop(Context context) {
        stopped = true;
        cancelRequested = true;
        Future<Void> transcodeTask = activeTranscodeTask;
        if (transcodeTask != null) transcodeTask.cancel(true);
        Future<?> compressionTask = activeCompressionTask;
        if (compressionTask != null) compressionTask.cancel(true);
        activeAttachmentSheet = new WeakReference<>(null);
        ExecutorService executor = compressionExecutor;
        if (executor != null) {
            executor.shutdownNow();
            compressionExecutor = null;
        }
        HandlerThread callbackThread = transcoderCallbackThread;
        if (callbackThread != null) {
            callbackThread.quitSafely();
            transcoderCallbackThread = null;
            transcoderCallbackHandler = null;
        }
        unregisterCancelReceiver(context);
        ProgressReporter.cancel(context);
        patcher.unpatchAll();
        commands.unregisterAll();
    }

    private void registerCancelReceiver(Context context) {
        Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
        cancelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
                    cancelActiveWork(context);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_CANCEL);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(cancelReceiver, filter);
        }
    }

    private void unregisterCancelReceiver(Context context) {
        BroadcastReceiver receiver = cancelReceiver;
        if (receiver == null) return;
        cancelReceiver = null;
        try {
            Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
            appContext.unregisterReceiver(receiver);
        } catch (Throwable ignored) {}
    }

    private void cancelActiveWork(Context context) {
        cancelRequested = true;
        Future<Void> transcodeTask = activeTranscodeTask;
        if (transcodeTask != null) transcodeTask.cancel(true);
        Future<?> compressionTask = activeCompressionTask;
        if (compressionTask != null) compressionTask.cancel(true);
        dismissActiveAttachmentSheet();
        ProgressReporter.cancel(context);
    }

    private boolean hasCompressibleAttachment(Context context, List<? extends Attachment<?>> attachments) {
        ContentResolver resolver = context.getContentResolver();
        for (Attachment<?> attachment : attachments) {
            if (shouldSkipSmallFile(context, attachment)) continue;
            if (settings.getBool(KEY_IMAGES, true) && isCompressibleImage(attachment, resolver)) return true;
            if (settings.getBool(KEY_VIDEOS, true) && AttachmentUtilsKt.isVideo(attachment, resolver)) return true;
        }
        return false;
    }

    private void compressAndReplay(ChatInputViewModel viewModel, Object[] args, SendDestination destination) {
        Context context = (Context) args[0];
        List<? extends Attachment<?>> attachments = (List<? extends Attachment<?>>) args[3];
        Function1<Boolean, Unit> callback = (Function1<Boolean, Unit>) args[5];

        try {
            if (stopped || Thread.currentThread().isInterrupted()) {
                callback.invoke(Boolean.FALSE);
                return;
            }
            ProgressReporter progress = new ProgressReporter(context, attachments.size());
            List<Attachment<?>> compressed = compressAttachments(context, attachments, progress);
            if (cancelRequested || Thread.currentThread().isInterrupted()) {
                mainHandler.post(() -> callback.invoke(Boolean.FALSE));
                progress.cancel();
                return;
            }
            args[3] = compressed;
            args[4] = true;
            progress.message("SendCompressed: sending compressed attachments...");
            mainHandler.post(() -> {
                dismissActiveAttachmentSheet();
                if (stopped) {
                    callback.invoke(Boolean.FALSE);
                    progress.cancel();
                    return;
                }
                try {
                    if (shouldSendDirectly(viewModel, destination)) {
                        sendToCapturedChannel(args, destination);
                    } else {
                        XposedBridge.invokeOriginalMethod(sendMessageMethod, viewModel, args);
                    }
                    progress.finish("SendCompressed: sending compressed attachments...");
                } catch (Throwable throwable) {
                    logger.error("Failed to send compressed attachments", throwable);
                    callback.invoke(Boolean.FALSE);
                    progress.cancel();
                }
            });
        } catch (Throwable throwable) {
            logger.error("Failed to compress attachments", throwable);
            mainHandler.post(() -> {
                dismissActiveAttachmentSheet();
                ProgressReporter.cancel(context);
                try {
                    XposedBridge.invokeOriginalMethod(sendMessageMethod, viewModel, args);
                } catch (Throwable sendError) {
                    logger.error("Failed to send original attachments after compression failure", sendError);
                    callback.invoke(Boolean.FALSE);
                }
            });
        }
    }

    private SendDestination captureDestination(ChatInputViewModel viewModel) {
        try {
            Object viewState = viewModel.getViewState();
            if (!(viewState instanceof ChatInputViewModel.ViewState.Loaded)) return SendDestination.unavailable();
            ChatInputViewModel.ViewState.Loaded loaded = (ChatInputViewModel.ViewState.Loaded) viewState;
            return new SendDestination(
                loaded.getChannelId(),
                loaded.getMaxFileSizeMB(),
                loaded.isEditing(),
                loaded.getSelectedThreadDraft() != null
            );
        } catch (Throwable throwable) {
            logger.error("Failed to capture original send destination", throwable);
            return SendDestination.unavailable();
        }
    }

    private void sendToCapturedChannel(Object[] args, SendDestination destination) {
        Context context = (Context) args[0];
        MessageManager messageManager = (MessageManager) args[1];
        MessageContent messageContent = (MessageContent) args[2];
        List<? extends Attachment<?>> attachments = (List<? extends Attachment<?>>) args[3];
        Function1<Boolean, Unit> callback = (Function1<Boolean, Unit>) args[5];
        final boolean[] validationHandled = {false};

        MessageManager.AttachmentsRequest attachmentsRequest = new MessageManager.AttachmentsRequest(
            currentFileSizeMb(context, attachments),
            destination.maxFileSizeMB,
            attachments
        );
        Function2<Integer, Integer, Unit> onMessageTooLong = (currentLength, maxLength) -> {
            validationHandled[0] = true;
            callback.invoke(Boolean.FALSE);
            return Unit.a;
        };
        Function2<Integer, Boolean, Unit> onFilesTooLarge = (maxFileSizeMb, isPremium) -> {
            validationHandled[0] = true;
            callback.invoke(Boolean.FALSE);
            return Unit.a;
        };

        boolean accepted = MessageManager.sendMessage$default(
            messageManager,
            messageContent.getTextContent(),
            messageContent.getMentionedUsers(),
            attachmentsRequest,
            Long.valueOf(destination.channelId),
            null,
            false,
            onMessageTooLong,
            onFilesTooLarge,
            null,
            16 | 32 | 256,
            null
        );
        if (!validationHandled[0]) callback.invoke(Boolean.valueOf(accepted));
    }

    private boolean shouldSendDirectly(ChatInputViewModel viewModel, SendDestination destination) {
        if (!destination.canSendDirectly()) return false;
        try {
            Object viewState = viewModel.getViewState();
            if (!(viewState instanceof ChatInputViewModel.ViewState.Loaded)) return true;
            ChatInputViewModel.ViewState.Loaded loaded = (ChatInputViewModel.ViewState.Loaded) viewState;
            return loaded.getChannelId() != destination.channelId
                || loaded.isEditing()
                || loaded.getSelectedThreadDraft() != null;
        } catch (Throwable throwable) {
            logger.error("Failed to compare current send destination", throwable);
            return true;
        }
    }

    private float currentFileSizeMb(Context context, List<? extends Attachment<?>> attachments) {
        long bytes = 0L;
        for (Attachment<?> attachment : attachments) {
            long size = originalSize(context, attachment);
            if (size <= 0L || size == Long.MAX_VALUE) continue;
            bytes += size;
        }
        return bytes / (1024f * 1024f);
    }

    private void releaseInputUi(Function1<Boolean, Unit> callback) {
        Runnable release = () -> {
            dismissActiveAttachmentSheet();
            try {
                callback.invoke(Boolean.TRUE);
            } catch (Throwable throwable) {
                logger.error("Failed to release input UI before compression", throwable);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release.run();
        } else {
            mainHandler.post(release);
        }
    }

    private void dismissActiveAttachmentSheet() {
        AttachmentBottomSheet sheet = activeAttachmentSheet.get();
        if (sheet == null) return;
        try {
            if (sheet.isAdded()) sheet.dismissAllowingStateLoss();
        } catch (Throwable throwable) {
            logger.error("Failed to dismiss attachment sheet", throwable);
        } finally {
            activeAttachmentSheet = new WeakReference<>(null);
        }
    }

    private List<Attachment<?>> compressAttachments(Context context, List<? extends Attachment<?>> attachments, ProgressReporter progress) {
        ArrayList<Attachment<?>> out = new ArrayList<>(attachments.size());
        ContentResolver resolver = context.getContentResolver();
        long targetBytes = targetBytes();

        int index = 0;
        for (Attachment<?> attachment : attachments) {
            if (cancelRequested || Thread.currentThread().isInterrupted()) throw new CancellationException("SendCompressed canceled");
            index++;
            Attachment<?> replacement = attachment;
            try {
                if (shouldSkipSmallFile(context, attachment)) {
                    progress.skipped(index, "under 10 MB");
                } else if (settings.getBool(KEY_IMAGES, true) && isCompressibleImage(attachment, resolver)) {
                    progress.startAttachment(index, "image");
                    replacement = compressImage(context, attachment, targetBytes);
                } else if (settings.getBool(KEY_VIDEOS, true) && AttachmentUtilsKt.isVideo(attachment, resolver)) {
                    progress.startAttachment(index, "video");
                    replacement = compressVideo(context, attachment, targetBytes, progress);
                }
            } catch (Throwable throwable) {
                logger.error("Failed to compress " + attachment.getDisplayName() + "; sending original.", throwable);
            }
            out.add((Attachment<?>) replacement);
        }
        return out;
    }

    private boolean shouldSkipSmallFile(Context context, Attachment<?> attachment) {
        if (!settings.getBool(KEY_SKIP_SMALL, true)) return false;
        long size = originalSize(context, attachment);
        return size > 0L && size < SMALL_FILE_BYTES;
    }

    private boolean isCompressibleImage(Attachment<?> attachment, ContentResolver resolver) {
        String mime = AttachmentUtilsKt.getMimeType(attachment, resolver);
        return mime != null && mime.startsWith("image/") && !"image/gif".equals(mime);
    }

    private Attachment<?> compressImage(Context context, Attachment<?> attachment, long targetBytes) throws Exception {
        File outFile = newTempFile(context, attachment.getDisplayName(), ".jpg");
        int quality = imageQuality();
        int maxDim = maxImageDimension();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(attachment.getUri())) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
        Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(attachment.getUri())) {
            bitmap = BitmapFactory.decodeStream(input, null, opts);
        }
        if (bitmap == null) return attachment;

        Bitmap scaled = scaleDown(bitmap, maxDim);
        if (scaled != bitmap) bitmap.recycle();

        try {
            writeJpeg(scaled, outFile, quality);
            if (settings.getBool(KEY_AGGRESSIVE, true)) {
                int currentQuality = quality;
                int currentMaxDim = Math.min(maxDim, Math.max(scaled.getWidth(), scaled.getHeight()));
                while (outFile.length() > targetBytes && currentQuality > 36) {
                    currentQuality -= 8;
                    writeJpeg(scaled, outFile, currentQuality);
                }
                while (outFile.length() > targetBytes && currentMaxDim > 640) {
                    currentMaxDim = Math.max(640, (int) (currentMaxDim * 0.82f));
                    Bitmap smaller = scaleDown(scaled, currentMaxDim);
                    if (smaller != scaled) scaled.recycle();
                    scaled = smaller;
                    writeJpeg(scaled, outFile, Math.max(34, currentQuality));
                }
            }
        } finally {
            scaled.recycle();
        }

        if (outFile.length() <= 0 || outFile.length() >= originalSize(context, attachment)) return attachment;
        return toAttachment(outFile, replaceExtension(attachment.getDisplayName(), "jpg"), attachment.getSpoiler());
    }

    private Attachment<?> compressVideo(Context context, Attachment<?> attachment, long targetBytes, ProgressReporter progressReporter) throws Exception {
        if (cancelRequested || Thread.currentThread().isInterrupted()) throw new CancellationException("SendCompressed canceled");
        long originalSize = originalSize(context, attachment);
        File outFile = newTempFile(context, attachment.getDisplayName(), ".mp4");
        VideoPreset preset = videoPreset(context, attachment, originalSize, targetBytes);

        Future<Void> transcodeTask = null;
        try {
            transcodeTask = Transcoder.into(outFile.getAbsolutePath())
                .addDataSource(context, attachment.getUri())
                .setVideoTrackStrategy(DefaultVideoStrategy.atMost(preset.maxMinor, preset.maxMajor)
                    .bitRate(preset.videoBitrate)
                    .frameRate(preset.frameRate)
                    .keyFrameInterval(3f)
                    .build())
                .setAudioTrackStrategy(DefaultAudioStrategy.builder()
                    .channels(2)
                    .sampleRate(44100)
                    .bitRate(preset.audioBitrate)
                    .build())
                .setListener(new TranscoderListener() {
                    @Override public void onTranscodeProgress(double progress) {
                        progressReporter.videoProgress(progress);
                    }
                    @Override public void onTranscodeCompleted(int successCode) {}
                    @Override public void onTranscodeCanceled() {}
                    @Override public void onTranscodeFailed(Throwable exception) {}
                })
                .setListenerHandler(transcoderCallbackHandler)
                .transcode();
            activeTranscodeTask = transcodeTask;
            transcodeTask.get(MAX_WAIT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            if (transcodeTask != null) transcodeTask.cancel(true);
            logger.error("Video compression timed out for " + attachment.getDisplayName() + "; sending original.", timeout);
            if (!outFile.delete()) outFile.deleteOnExit();
            return attachment;
        } catch (CancellationException canceled) {
            if (!outFile.delete()) outFile.deleteOnExit();
            if (cancelRequested || Thread.currentThread().isInterrupted()) throw canceled;
            return attachment;
        } catch (Throwable throwable) {
            logger.error("Video compression failed for " + attachment.getDisplayName() + "; sending original.", throwable);
            if (!outFile.delete()) outFile.deleteOnExit();
            return attachment;
        } finally {
            if (activeTranscodeTask == transcodeTask) activeTranscodeTask = null;
        }
        if (outFile.length() <= 0 || outFile.length() >= originalSize) {
            if (!outFile.delete()) outFile.deleteOnExit();
            return attachment;
        }
        return toAttachment(outFile, replaceExtension(attachment.getDisplayName(), "mp4"), attachment.getSpoiler());
    }

    private final class ProgressReporter {
        private final Context context;
        private final NotificationManager notificationManager;
        private final int total;
        private int index;
        private int lastPercent = -10;
        private long lastNotifyAt;

        ProgressReporter(Context context, int total) {
            this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
            this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
            this.total = Math.max(1, total);
            ensureChannel(this.context, notificationManager);
        }

        synchronized void startAttachment(int index, String type) {
            this.index = index;
            this.lastPercent = -10;
            this.lastNotifyAt = 0L;
            notify("Compressing " + type + " " + index + "/" + total, "Preparing attachment...", 0, 0, true, false);
        }

        synchronized void skipped(int index, String reason) {
            this.index = index;
            notify("Skipping attachment " + index + "/" + total, reason, 0, 0, false, false);
        }

        synchronized void videoProgress(double progress) {
            int percent = Math.max(0, Math.min(99, (int) Math.round(progress * 100d)));
            long now = System.currentTimeMillis();
            if (percent < lastPercent + 5 && now - lastNotifyAt < 1000L) return;
            lastPercent = percent;
            lastNotifyAt = now;
            notify("Compressing video " + index + "/" + total, percent + "% complete", 100, percent, false, false);
        }

        void message(String message) {
            notify(message, "Working in the background", 0, 0, true, false);
        }

        void finish(String message) {
            notify(message, "Upload started", 0, 0, false, true);
            mainHandler.postDelayed(this::cancel, 4000L);
        }

        void cancel() {
            cancel(context);
        }

        private void notify(String title, String text, int max, int progress, boolean indeterminate, boolean autoCancel) {
            if (stopped || notificationManager == null) return;
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(context);
            builder
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(!autoCancel)
                .setAutoCancel(autoCancel)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(max, progress, indeterminate);
            if (!autoCancel) {
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent(context));
            }
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }

        private PendingIntent cancelIntent(Context context) {
            Intent intent = new Intent(ACTION_CANCEL).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(context, 0, intent, flags);
        }

        static void cancel(Context context) {
            Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
            NotificationManager manager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        }

        private static void ensureChannel(Context context, NotificationManager manager) {
            if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SendCompressed progress",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    private VideoPreset videoPreset(Context context, Attachment<?> attachment, long originalSize, long targetBytes) {
        int quality = settings.getInt(KEY_QUALITY, 1);
        int maxMajor = quality >= 4 ? 2560 : quality == 3 ? 1920 : quality == 2 ? 1280 : quality == 1 ? 960 : 720;
        int maxMinor = quality >= 4 ? 1440 : quality == 3 ? 1080 : quality == 2 ? 720 : quality == 1 ? 540 : 480;
        int frameRate = quality >= 3 ? 30 : quality == 2 ? 30 : 24;
        int audioBitrate = quality >= 4 ? 192_000 : quality == 3 ? 160_000 : quality == 2 ? 128_000 : 96_000;
        int videoBitrate = quality >= 4 ? 8_000_000 : quality == 3 ? 4_800_000 : quality == 2 ? 2_400_000 : quality == 1 ? 1_200_000 : 700_000;

        if (settings.getBool(KEY_AGGRESSIVE, true) && originalSize > targetBytes) {
            long durationMs = videoDurationMs(context, attachment.getUri());
            if (durationMs > 0) {
                long totalBits = Math.max(256_000L, (targetBytes - 256_000L) * 8_000L / durationMs);
                videoBitrate = (int) Math.max(220_000L, Math.min(videoBitrate, totalBits - audioBitrate));
                if (videoBitrate < 650_000) {
                    maxMajor = 640;
                    maxMinor = 360;
                    frameRate = 20;
                    audioBitrate = 64_000;
                }
            }
        }
        return new VideoPreset(maxMinor, maxMajor, frameRate, videoBitrate, audioBitrate);
    }

    private long videoDurationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0L : Long.parseLong(duration);
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {}
        }
    }

    private long originalSize(Context context, Attachment<?> attachment) {
        try {
            return com.discord.utilities.rest.SendUtilsKt.computeFileSizeBytes(attachment.getUri(), context.getContentResolver());
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;
        }
    }

    private long targetBytes() {
        int mb = settings.getInt(KEY_TARGET_MB, 10);
        if (mb < 1) mb = 10;
        if (mb > 25) mb = 25;
        return mb * 1024L * 1024L;
    }

    private Object invokeOriginal(Method method, Object receiver, Object[] args) {
        try {
            return XposedBridge.invokeOriginalMethod(method, receiver, args);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    private int imageQuality() {
        int quality = settings.getInt(KEY_QUALITY, 1);
        return quality >= 4 ? 94 : quality == 3 ? 90 : quality == 2 ? 86 : quality == 1 ? 74 : 58;
    }

    private int maxImageDimension() {
        int quality = settings.getInt(KEY_QUALITY, 1);
        return quality >= 4 ? 4096 : quality == 3 ? 3072 : quality == 2 ? 2560 : quality == 1 ? 1920 : 1280;
    }

    private static int sampleSize(int width, int height, int maxDim) {
        int sample = 1;
        int largest = Math.max(width, height);
        while (largest / sample > maxDim * 2) sample *= 2;
        return Math.max(1, sample);
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxDim) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int largest = Math.max(width, height);
        if (largest <= maxDim) return bitmap;
        float ratio = maxDim / (float) largest;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)), true);
    }

    private static void writeJpeg(Bitmap bitmap, File file, int quality) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
        }
    }

    private File newTempFile(Context context, String displayName, String ext) throws Exception {
        File dir = new File(context.getCacheDir(), "SendCompressed");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create cache directory");
        String base = stripExtension(displayName).replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.length() < 1) base = "attachment";
        return File.createTempFile(base + "_", ext, dir);
    }

    private File copyToTempFile(Context context, Attachment<?> attachment) throws Exception {
        File file = newTempFile(context, attachment.getDisplayName(), extensionWithDot(attachment.getDisplayName()));
        try (InputStream input = context.getContentResolver().openInputStream(attachment.getUri());
             FileOutputStream output = new FileOutputStream(file, false)) {
            if (input == null) throw new IllegalStateException("Could not open attachment input stream");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return file;
    }

    private static Attachment<?> toAttachment(File file, String displayName, boolean spoiler) {
        Attachment<File> attachment = new Attachment<>(file.hashCode(), Uri.fromFile(file), displayName, file, false, 16, null);
        attachment.setSpoiler(spoiler);
        return attachment;
    }

    private static String replaceExtension(String name, String extension) {
        return stripExtension(name) + "." + extension;
    }

    private static String stripExtension(String name) {
        if (name == null || name.trim().isEmpty()) return "attachment";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(0, dot) : name;
    }

    private static String extensionWithDot(String name) {
        if (name == null) return ".mp4";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) return ".mp4";
        String extension = name.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        return extension.length() > 1 ? extension : ".mp4";
    }

    private static final class VideoPreset {
        final int maxMinor;
        final int maxMajor;
        final int frameRate;
        final int videoBitrate;
        final int audioBitrate;

        VideoPreset(int maxMinor, int maxMajor, int frameRate, int videoBitrate, int audioBitrate) {
            this.maxMinor = maxMinor;
            this.maxMajor = maxMajor;
            this.frameRate = frameRate;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
        }
    }

    private static final class SendDestination {
        final long channelId;
        final int maxFileSizeMB;
        final boolean editing;
        final boolean threadDraft;

        SendDestination(long channelId, int maxFileSizeMB, boolean editing, boolean threadDraft) {
            this.channelId = channelId;
            this.maxFileSizeMB = maxFileSizeMB;
            this.editing = editing;
            this.threadDraft = threadDraft;
        }

        boolean canSendDirectly() {
            return channelId > 0L && maxFileSizeMB > 0 && !editing && !threadDraft;
        }

        static SendDestination unavailable() {
            return new SendDestination(0L, 0, true, true);
        }
    }

    public static final class SettingsSheet extends BottomSheet {
        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);
            SettingsAPI api = new SettingsAPI(SETTINGS_NAME);

            addView(header("SendCompressed"));
            addSwitch(api, KEY_ENABLED, true, "Enable compression", "Compress supported attachments before sending.");
            addSwitch(api, KEY_IMAGES, true, "Compress images", "JPEG, PNG, and WebP images are re-encoded.");
            addSwitch(api, KEY_VIDEOS, true, "Compress videos", "Videos are transcoded to smaller MP4 files.");
            addSwitch(api, KEY_SKIP_SMALL, true, "Skip files under 10 MB", "Send smaller attachments without recompressing them.");
            addSwitch(api, KEY_AGGRESSIVE, true, "Aggressive 10 MB fit", "Lower quality further when an attachment is over the target size.");

            addView(header("Quality"));
            addQuality(api, 4, "Maximum", "Highest detail, least compression.");
            addQuality(api, 3, "Very High", "Higher quality with larger compressed files.");
            addQuality(api, 2, "High", "Larger files with better detail.");
            addQuality(api, 1, "Balanced", "Default size and quality.");
            addQuality(api, 0, "Small", "Prioritize smaller uploads.");

            addView(header("Target Limit"));
            addTarget(api, 10, "10 MB", "Discord's standard attachment limit.");
            addTarget(api, 8, "8 MB", "Leaves upload overhead room.");
            addTarget(api, 25, "25 MB", "Use a larger custom target.");
        }

        private void addSwitch(SettingsAPI api, String key, boolean def, String title, String subtitle) {
            CheckedSetting setting = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle);
            setting.setChecked(api.getBool(key, def));
            setting.setOnCheckedListener(checked -> api.setBool(key, checked));
            addView(setting);
        }

        private void addQuality(SettingsAPI api, int value, String title, String subtitle) {
            CheckedSetting setting = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, title, subtitle);
            setting.setChecked(api.getInt(KEY_QUALITY, 1) == value);
            setting.setOnCheckedListener(checked -> {
                if (checked) {
                    api.setInt(KEY_QUALITY, value);
                    dismiss();
                }
            });
            addView(setting);
        }

        private void addTarget(SettingsAPI api, int value, String title, String subtitle) {
            CheckedSetting setting = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, title, subtitle);
            setting.setChecked(api.getInt(KEY_TARGET_MB, 10) == value);
            setting.setOnCheckedListener(checked -> {
                if (checked) {
                    api.setInt(KEY_TARGET_MB, value);
                    dismiss();
                }
            });
            addView(setting);
        }

        private TextView header(String text) {
            TextView header = new TextView(requireContext());
            header.setText(text.toUpperCase(Locale.ROOT));
            header.setTextColor(ColorCompat.getThemedColor(requireContext(), COLOR_HEADER_SECONDARY_ATTR));
            header.setTextSize(12f);
            header.setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(16), DimenUtils.dpToPx(16), DimenUtils.dpToPx(6));
            return header;
        }
    }
}
