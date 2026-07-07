package dev.autoaliu.generated.fixspoilers;

import android.content.Context;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.discord.api.message.attachment.MessageAttachment;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

@AliucordPlugin
@SuppressWarnings("unused")
public class FixSpoilers extends Plugin {
    private static final int IS_SPOILER_FLAG = 1 << 3;
    private static final String SPOILER_PREFIX = "SPOILER_";

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
            Gson.class,
            "h",
            new Class<?>[] { TypeToken.class },
            new Hook(param -> {
                Object typeToken = param.args[0];
                Object adapter = param.getResult();
                if (
                    typeToken instanceof TypeToken
                        && ((TypeToken<?>) typeToken).getRawType() == MessageAttachment.class
                        && adapter instanceof TypeAdapter
                        && !(adapter instanceof SpoilerAttachmentAdapter)
                ) {
                    param.setResult(new SpoilerAttachmentAdapter((TypeAdapter<MessageAttachment>) adapter).nullSafe());
                }
            })
        );
    }

    @Override
    public void stop(Context context) throws Throwable {
        patcher.unpatchAll();
        commands.unregisterAll();
    }

    private static String spoilerFilename(String filename) {
        if (filename == null || filename.startsWith(SPOILER_PREFIX)) return filename;
        return SPOILER_PREFIX + filename;
    }

    private static boolean isNull(JsonReader reader) throws IOException {
        return reader.N().ordinal() == 8;
    }

    private static Long readNullableLong(JsonReader reader) throws IOException {
        if (isNull(reader)) {
            reader.H();
            return null;
        }
        return reader.A();
    }

    private static Integer readNullableInt(JsonReader reader) throws IOException {
        Long value = readNullableLong(reader);
        return value == null ? null : value.intValue();
    }

    private static String readNullableString(JsonReader reader) throws IOException {
        if (isNull(reader)) {
            reader.H();
            return null;
        }
        return reader.J();
    }

    private static void writeNullableString(JsonWriter writer, String name, String value) throws IOException {
        writer.n(name);
        if (value == null) writer.s();
        else writer.H(value);
    }

    private static void writeNullableLong(JsonWriter writer, String name, Long value) throws IOException {
        writer.n(name);
        if (value == null) writer.s();
        else writer.A(value);
    }

    private static void writeNullableNumber(JsonWriter writer, String name, Number value) throws IOException {
        writer.n(name);
        if (value == null) writer.s();
        else writer.D(value);
    }

    private static String readFixedAttachmentJson(JsonReader reader) throws IOException {
        String url = null;
        String proxyUrl = null;
        String filename = null;
        Integer width = null;
        Integer height = null;
        Long size = null;
        Long id = null;
        long flags = 0L;

        reader.b();
        while (reader.q()) {
            String name = reader.C();
            switch (name) {
                case "url":
                    url = readNullableString(reader);
                    break;
                case "proxy_url":
                    proxyUrl = readNullableString(reader);
                    break;
                case "filename":
                    filename = readNullableString(reader);
                    break;
                case "width":
                    width = readNullableInt(reader);
                    break;
                case "height":
                    height = readNullableInt(reader);
                    break;
                case "size":
                    size = readNullableLong(reader);
                    break;
                case "id":
                    id = readNullableLong(reader);
                    break;
                case "flags":
                    Long parsedFlags = readNullableLong(reader);
                    flags = parsedFlags == null ? 0L : parsedFlags;
                    break;
                default:
                    reader.U();
                    break;
            }
        }
        reader.f();

        if ((flags & IS_SPOILER_FLAG) != 0) {
            filename = spoilerFilename(filename);
        }

        StringWriter output = new StringWriter();
        JsonWriter writer = new JsonWriter(output);
        writer.c();
        writeNullableString(writer, "url", url);
        writeNullableLong(writer, "size", size);
        writeNullableLong(writer, "id", id);
        writeNullableString(writer, "proxy_url", proxyUrl);
        writeNullableString(writer, "filename", filename);
        writeNullableNumber(writer, "width", width);
        writeNullableNumber(writer, "height", height);
        writer.f();
        writer.close();
        return output.toString();
    }

    private static final class SpoilerAttachmentAdapter extends TypeAdapter<MessageAttachment> {
        private final TypeAdapter<MessageAttachment> delegate;

        private SpoilerAttachmentAdapter(TypeAdapter<MessageAttachment> delegate) {
            this.delegate = delegate;
        }

        @Override
        public MessageAttachment read(JsonReader reader) throws IOException {
            return delegate.read(new JsonReader(new StringReader(readFixedAttachmentJson(reader))));
        }

        @Override
        public void write(JsonWriter writer, MessageAttachment attachment) throws IOException {
            delegate.write(writer, attachment);
        }
    }
}
