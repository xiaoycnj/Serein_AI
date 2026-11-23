package app.serein.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

final class UpdateImageCheck {
    public static void main(String[] args) throws Exception {
        updates();
        downloadProgress();
        images();
        imageTransport();
        System.out.println("PASS: release ordering and integrity, catalog validation, image protocol and versioned attachments");
    }

    private static void updates() throws Exception {
        String base = "https://github.com/xiaoycnj/Serein_AI/releases/download/";
        JSONArray releases = new JSONArray().put(release("0.0.9", base, true))
                .put(release("0.0.11", "https://example.net/", true))
                .put(release("0.0.14", base, false)).put(release("0.0.10", base, true));
        var candidate = SereinUpdates.select(releases, "0.0.8-alpha");
        require(candidate != null && candidate.version().equals("0.0.10") && candidate.sha256().length() == 64,
                "select numerically newest eligible signed-release asset with GitHub digest");
        require(SereinUpdates.select(releases, "0.0.10-alpha") == null, "exclude current and older releases");
        require(!SereinUpdates.allowedDownload(URI.create("https://github.com/other/repo/releases/download/v1/x.apk"))
                && !SereinUpdates.allowedDownload(URI.create("http://release-assets.githubusercontent.com/a"))
                && SereinUpdates.allowedDownload(URI.create("https://release-assets.githubusercontent.com/a")), "confined artifact downloads");

        byte[] raw = """
                {"schema":1,"models":{"id":{"label":"Test","publisher":"Serein","icon":"openai","inputLimit":8000}}}
                """.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + raw.length + "\0").getBytes(StandardCharsets.UTF_8));
        var record = new JSONObject().put("encoding", "base64").put("size", raw.length)
                .put("content", Base64.getEncoder().encodeToString(raw)).put("sha", java.util.HexFormat.of().formatHex(sha.digest(raw)));
        var catalog = ModelCatalog.parse(SereinUpdates.catalogBytes(record));
        require(catalog.find("id").inputLimit() == 8000, "valid model metadata loads");
        try {
            SereinUpdates.catalogBytes(record.put("sha", "a".repeat(40)));
            throw new AssertionError("modified catalog accepted");
        } catch (IOException expected) {}
        try {
            ModelCatalog.parse("{\"schema\":2,\"models\":{}}".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("unknown catalog format accepted");
        } catch (org.json.JSONException expected) {}
    }

    private static JSONObject release(String version, String base, boolean digest) throws Exception {
        String tag = "v" + version + "-alpha";
        String name = "Serein-Alpha-" + version + ".apk";
        var asset = new JSONObject().put("name", name).put("state", "uploaded").put("size", 1024)
                .put("browser_download_url", base + tag + "/" + name);
        if (digest) asset.put("digest", "sha256:" + "a".repeat(64));
        return new JSONObject().put("tag_name", tag).put("prerelease", true).put("assets", new JSONArray().put(asset));
    }

    private static void downloadProgress() throws Exception {
        byte[] packageBytes = new byte[80_000];
        packageBytes[0] = 17;
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packageBytes));
        var release = new SereinUpdates.Release("0.0.17", URI.create("https://github.com"), packageBytes.length, hash);
        var transferred = new java.util.ArrayList<Long>();
        var phases = new java.util.ArrayList<String>();
        var events = new SereinUpdates.Progress() {
            @Override public void downloaded(long bytes) { transferred.add(bytes); phases.add("download"); }
            @Override public void verifying() { phases.add("verify"); }
        };
        var copied = new ByteArrayOutputStream();
        SereinUpdates.copyVerified(new ByteArrayInputStream(packageBytes), copied, release, new ModelApi.Cancellation(), events);
        require(java.util.Arrays.equals(packageBytes, copied.toByteArray()), "download retains exact bytes");
        require(transferred.get(0) == 0L && transferred.get(transferred.size() - 1) == packageBytes.length
                && phases.get(phases.size() - 1).equals("verify"), "progress reports real byte counts before verification");
        for (int i = 1; i < transferred.size(); i++) require(transferred.get(i) >= transferred.get(i - 1), "monotonic progress");
        byte[] changed = packageBytes.clone();
        changed[0] = 18;
        rejectDownload(changed, release, new ModelApi.Cancellation(), events);
        rejectDownload(java.util.Arrays.copyOf(packageBytes, packageBytes.length - 1), release, new ModelApi.Cancellation(), events);
        rejectDownload(java.util.Arrays.copyOf(packageBytes, packageBytes.length + 1), release, new ModelApi.Cancellation(), events);
        var cancelled = new ModelApi.Cancellation();
        cancelled.cancel();
        phases.clear();
        rejectDownload(packageBytes, release, cancelled, events);
        require(phases.equals(List.of("download")), "cancelled download does not report verification");

        var cached = Files.createTempFile("serein-update-check-", ".apk");
        try {
            Files.write(cached, packageBytes);
            SereinUpdates.verifyCachedBytes(cached.toFile(), release.bytes(), release.sha256());
            Files.write(cached, changed);
            try {
                SereinUpdates.verifyCachedBytes(cached.toFile(), release.bytes(), release.sha256());
                throw new AssertionError("A modified cache passed installation verification");
            } catch (IOException expected) {}
        } finally { Files.deleteIfExists(cached); }
    }

    private static void rejectDownload(byte[] data, SereinUpdates.Release release, ModelApi.Cancellation cancellation,
                                       SereinUpdates.Progress events) throws Exception {
        try {
            SereinUpdates.copyVerified(new ByteArrayInputStream(data), java.io.OutputStream.nullOutputStream(), release, cancellation, events);
            throw new AssertionError("Invalid or cancelled download was accepted");
        } catch (IOException expected) {}
    }

    private static void images() throws Exception {
        var request = ImageGeneration.payload("draw a test square", "test-image", "1024x1024");
        require(request.length() == 4 && request.getInt("n") == 1 && !request.has("messages"),
                "one image request sends prompt and explicit parameters");
        require(!ImageGeneration.payload("draw", "test-image", "").has("size"), "provider default dimensions omitted");
        require(java.util.Arrays.equals(ImageGeneration.decode("AQID"), new byte[]{1, 2, 3}), "base64 bytes returned");
        try {
            ImageGeneration.imageUrl("https://gateway.example/v1", "https://localhost/secret");
            throw new AssertionError("unexpected image download origin accepted");
        } catch (IOException expected) {}
        require(ImageGeneration.imageUrl("https://gateway.example/v1", "https://gateway.example/image.png?token=sample")
                .getHost().equals("gateway.example"), "same-origin image URL allowed");

        var disk = new java.util.concurrent.atomic.AtomicReference<>("{\"history\":[],\"active\":\"\"}");
        var store = new ChatStore(disk.get(), disk::set);
        var image = new GeneratedImage("ab".repeat(16), "provider", "image-model", "1024x1024", "image/png");
        store.append(-1, "draw", "已生成图片", "image-model", null, null, ChatHistory.ReplyState.COMPLETE, null, image);
        var original = store.active;
        store.fork(original, 0);
        store.delete(original);
        var restored = new ChatStore(disk.get(), disk::set);
        var refs = new HashSet<String>();
        restored.active.history.collectImageIds(refs);
        require(refs.contains(image.id()) && restored.active.history.turn(0).image().equals(image), "image survives branch and reload");
    }

    private static void imageTransport() throws Exception {
        var opened = new java.util.ArrayList<MockImageConnection>();
        URL.setURLStreamHandlerFactory(protocol -> !protocol.equals("https") ? null : new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                var connection = new MockImageConnection(url);
                opened.add(connection);
                return connection;
            }
        });
        var provider = new Providers.Provider("provider", "image", "https://gateway.example/v1",
                List.of(new Providers.Key("test", "fixture-key")), List.of(), 0);
        var request = new ImageGeneration.Request(provider, "configured-image-model", "");
        byte[] result = ImageGeneration.generate(request, "draw a square", new ModelApi.Cancellation());
        require(java.util.Arrays.equals(result, new byte[]{1, 2, 3}) && opened.size() == 2,
                "image returns data from one generation request and one download");
        var submit = opened.get(0);
        var download = opened.get(1);
        require(submit.getURL().getPath().equals("/v1/images/generations")
                && submit.getRequestMethod().equals("POST")
                && "Bearer fixture-key".equals(submit.getRequestProperty("Authorization"))
                && new JSONObject(submit.sent.toString(StandardCharsets.UTF_8)).getString("prompt").equals("draw a square"),
                "image request authenticates only to the configured generation endpoint");
        require(download.getRequestProperty("Authorization") == null && !download.getInstanceFollowRedirects()
                && submit.closed && download.closed, "result download is unauthenticated, bounded, and closed");
    }

    private static final class MockImageConnection extends HttpsURLConnection {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean closed;

        MockImageConnection(URL url) { super(url); }
        @Override public void connect() {}
        @Override public void disconnect() { closed = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return 200; }
        @Override public java.io.OutputStream getOutputStream() { return sent; }
        @Override public java.io.InputStream getInputStream() {
            byte[] data = getURL().getPath().endsWith("generations") ?
                    "{\"data\":[{\"url\":\"https://gateway.example/result.png\"}]}".getBytes(StandardCharsets.UTF_8)
                    : new byte[]{1, 2, 3};
            return new ByteArrayInputStream(data);
        }
        @Override public String getCipherSuite() { return "test"; }
        @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
