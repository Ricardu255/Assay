package io.assay.opentrace;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends OpenAI Agents SDK span exports to a JSONL file without importing the SDK.
 *
 * <p>The SDK calls a processor through {@code on_*_end} hooks and hands it a span it knows how to
 * {@code export()} — a method that only exists when the optional {@code openai-agents} package is on the
 * classpath. This port keeps the same hook surface but takes the already-exported object directly as a
 * {@code Map}, so the framework never needs that dependency to read the resulting file.
 */
public final class OpenAiTraceProcessor implements AutoCloseable {

    private final Path path;
    private final Object lock = new Object();
    private BufferedWriter handle;

    public OpenAiTraceProcessor(Path path) {
        this.path = path;
        Path parent = path.getParent();
        if (parent != null) {
            AtomicFiles.createDirectories(parent);
        }
        try {
            this.handle = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public void onTraceStart(Object trace) {
    }

    public void onTraceEnd(Object trace) {
    }

    public void onSpanStart(Object span) {
    }

    /** Receives an already-exported span object, matching {@code on_span_end}. */
    public void onSpanEnd(Object span) {
        if (!(span instanceof Map)) {
            throw new IllegalArgumentException("OpenAI span export must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> exported = (Map<String, Object>) span;
        String encoded = Json.write(new LinkedHashMap<>(exported));
        synchronized (lock) {
            try {
                handle.write(encoded + "\n");
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    public void forceFlush() {
        synchronized (lock) {
            try {
                handle.flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                handle.flush();
                handle.close();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    /**
     * Registers this processor with the OpenAI Agents SDK.
     *
     * <p>That needs the SDK on the classpath, which the framework deliberately does not depend on, so this
     * can only explain what is missing. Construct the processor directly and call
     * {@link #onSpanEnd(Object)} from whatever pipeline exports the spans.
     */
    public static OpenAiTraceProcessor install(Path path) {
        throw new IllegalStateException(
                "installing an OpenAI trace processor requires the optional openai-agents package");
    }
}
