package com.click.data.local;

import android.app.Application;
import android.content.Context;
import com.click.BuildConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CandidateDiagnosticLogTest {
    @Test public void diagnosticReleaseWorksWithoutMakingTheAppDebuggable() throws Exception {
        if (!CandidateDiagnosticLog.enabled()) {
            assertFalse(BuildConfig.DEBUG);
            assertFalse(BuildConfig.CANDIDATE_DIAGNOSTICS);
            CandidateDiagnosticLog disabled = new CandidateDiagnosticLog(RuntimeEnvironment.getApplication());
            disabled.record(new JSONObject().put("stage", "collected"));
            assertFalse(new File(RuntimeEnvironment.getApplication().getNoBackupFilesDir(),
                    "settings-diagnostics/current.jsonl").exists());
            try { disabled.export(); fail("Disabled release must not export diagnostics"); }
            catch (IllegalStateException expected) { }
            return;
        }
        if (!BuildConfig.DEBUG) assertTrue(BuildConfig.CANDIDATE_DIAGNOSTICS);
        CandidateDiagnosticLog log = new CandidateDiagnosticLog(RuntimeEnvironment.getApplication());
        log.record(new JSONObject().put("stage", "collected").put("display_label_count", 1));
        String exported = new String(Files.readAllBytes(log.export().toPath()), StandardCharsets.UTF_8);
        assertTrue(exported.contains("settings-candidates.v1"));
        assertTrue(exported.contains("display_label_count"));
        assertTrue(exported.contains("version_code"));
    }
    @Test public void journalRotatesAndDoesNotEnterAndroidBackup() throws Exception {
        org.junit.Assume.assumeTrue(CandidateDiagnosticLog.enabled());
        Context context = RuntimeEnvironment.getApplication();
        CandidateDiagnosticLog log = new CandidateDiagnosticLog(context);
        String padding = "x".repeat(3000);
        for (int i = 0; i < 260; i++) log.record(new JSONObject().put("index", i).put("padding", padding));
        File current = new File(context.getNoBackupFilesDir(), "settings-diagnostics/current.jsonl");
        File previous = new File(context.getNoBackupFilesDir(), "settings-diagnostics/previous.jsonl");
        assertTrue(previous.exists());
        assertTrue(current.length() <= CandidateDiagnosticLog.MAX_BYTES);
        assertTrue(previous.length() <= CandidateDiagnosticLog.MAX_BYTES);
        String exported = new String(Files.readAllBytes(log.export().toPath()), StandardCharsets.UTF_8);
        assertTrue(exported.contains("\"index\":259"));
        assertFalse(exported.contains("\"index\":0,"));
        assertTrue(log.export().length() < 2L * CandidateDiagnosticLog.MAX_BYTES + 4096);
        assertFalse(new File(context.getFilesDir(), "settings-diagnostics").exists());
    }
    @Test public void sharedFileGrantsAccessOnlyToTheExportDirectory() throws Exception {
        org.junit.Assume.assumeTrue(CandidateDiagnosticLog.enabled());
        Context context = RuntimeEnvironment.getApplication();
        CandidateDiagnosticLog log = new CandidateDiagnosticLog(context);
        log.record(new JSONObject().put("stage", "selection"));
        android.content.pm.ProviderInfo provider = context.getPackageManager().resolveContentProvider(
                context.getPackageName() + ".diagnostic-files", android.content.pm.PackageManager.GET_META_DATA);
        assertNotNull(provider);
        assertFalse(provider.exported);
        assertTrue(provider.grantUriPermissions);
        int roots = 0;
        try (android.content.res.XmlResourceParser paths = context.getResources().getXml(
                provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))) {
            while (paths.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (paths.getEventType() != org.xmlpull.v1.XmlPullParser.START_TAG || "paths".equals(paths.getName())) continue;
                roots++;
                assertEquals("cache-path", paths.getName());
                assertEquals("settings-diagnostics/", paths.getAttributeValue(null, "path"));
            }
        }
        assertEquals(1, roots);
        // AndroidX's canonical-path check assumes Android/Unix '/' separators.
        // Verify the actual content URI on Unix CI; Windows still checks the manifest scope above.
        if (File.separatorChar == '\\') return;
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                context.getPackageName() + ".diagnostic-files", log.export());
        assertEquals("content", uri.getScheme());
        assertEquals(context.getPackageName() + ".diagnostic-files", uri.getAuthority());
        try {
            androidx.core.content.FileProvider.getUriForFile(context, uri.getAuthority(),
                    new File(context.getNoBackupFilesDir(), "settings-diagnostics/current.jsonl"));
            fail("Private journal must not be directly shareable");
        } catch (IllegalArgumentException expected) { }
    }
}
