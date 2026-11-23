package com.click.data.local;

import android.content.Context;
import android.os.Build;
import com.click.BuildConfig;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Small device-local journal; no uploads, credentials, screen text or screenshots. */
public final class CandidateDiagnosticLog {
    static final int MAX_BYTES = 256 * 1024;
    private final Context context;

    public CandidateDiagnosticLog(Context context) { this.context = context.getApplicationContext(); }
    public static boolean enabled() { return BuildConfig.DEBUG || BuildConfig.CANDIDATE_DIAGNOSTICS; }

    private File directory() { return new File(context.getNoBackupFilesDir(), "settings-diagnostics"); }
    private File current() { return new File(directory(), "current.jsonl"); }
    private File previous() { return new File(directory(), "previous.jsonl"); }

    public void record(JSONObject report) {
        if (!enabled()) return;
        try {
            report.put("recorded_at_ms", System.currentTimeMillis());
            byte[] bytes = (report.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8192) return;
            synchronized (CandidateDiagnosticLog.class) {
                Files.createDirectories(directory().toPath());
                if (current().length() + bytes.length > MAX_BYTES) {
                    Files.move(current().toPath(), previous().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.write(current().toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            android.util.Log.i("ClickCandidates", report.toString());
        } catch (Exception ignored) { /* Diagnostics must not change execution behavior. */ }
    }

    public File export() throws Exception {
        if (!enabled()) throw new IllegalStateException("Diagnostics disabled");
        synchronized (CandidateDiagnosticLog.class) {
            if (!current().isFile()) throw new java.io.FileNotFoundException("No diagnostics yet");
            File destination = new File(context.getCacheDir(), "settings-diagnostics/settings-candidates.jsonl");
            Files.createDirectories(destination.getParentFile().toPath());
            JSONObject header = new JSONObject().put("schema", "settings-candidates.v1")
                    .put("version_code", BuildConfig.VERSION_CODE).put("version_name", BuildConfig.VERSION_NAME)
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("android_sdk", Build.VERSION.SDK_INT);
            try (OutputStream out = Files.newOutputStream(destination.toPath())) {
                out.write((header.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                if (previous().isFile()) exportRecords(previous(), out);
                exportRecords(current(), out);
            }
            return destination;
        }
    }

    private static void exportRecords(File source, OutputStream out) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject record;
                try { record = new JSONObject(line); }
                catch (JSONException incomplete) { continue; }
                // Older crash stacks can contain arbitrary multiline exception messages.
                // Keep their timestamp and OS reason when sharing a journal after an upgrade.
                if (record.optInt("crash_format") < CrashJournal.FORMAT_VERSION) {
                    switch (record.optString("event")) {
                        case "app_crash" -> {
                            record.remove("stack");
                            record.remove("thread");
                            line = record.toString();
                        }
                        case "app_exit" -> {
                            record.remove("description");
                            record.remove("process");
                            line = record.toString();
                        }
                    }
                }
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
