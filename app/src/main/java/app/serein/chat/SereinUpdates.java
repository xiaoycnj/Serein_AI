package app.serein.chat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class SereinUpdates {
    private static final String REPO = "xiaoycnj/Serein_AI";
    private static final String API = "https://api.github.com/repos/" + REPO;
    private static final long APK_LIMIT = 64_000_000;
    private static final int API_LIMIT = 1_500_000;
    private static final Pattern VERSION = Pattern.compile("v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:-alpha)?");

    record Release(String version, URI asset, long bytes, String sha256) {}

    interface Progress {
        void downloaded(long bytes);
        void verifying();
    }

    static Release latest(Context context) throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        byte[] data = json(API + "/releases?per_page=30");
        return select(new JSONArray(new String(data, StandardCharsets.UTF_8)), installed.versionName);
    }

    static Release select(JSONArray releases, String installed) {
        long highest = version(installed);
        if (highest < 0) return null;
        Release candidate = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft")) continue;
            String tag = release.optString("tag_name");
            long order = version(tag);
            if (order <= highest) continue;
            String name = tag.replaceFirst("^v", "").replaceFirst("-alpha$", "");
            String filename = "Serein-Alpha-" + name + ".apk";
            URI asset = URI.create("https://github.com/" + REPO + "/releases/download/" + tag + "/" + filename);
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject row = assets.optJSONObject(j);
                if (row == null || !filename.equals(row.optString("name")) || !"uploaded".equals(row.optString("state"))
                        || !asset.toString().equals(row.optString("browser_download_url"))) continue;
                long bytes = row.optLong("size");
                String hash = row.optString("digest");
                if (!hash.startsWith("sha256:") || !validDigest(bytes, hash.substring(7))) continue;
                highest = order;
                candidate = new Release(name, asset, bytes, hash.substring(7));
                break;
            }
        }
        return candidate;
    }

    private static long version(String value) {
        if (value == null) return -1;
        var match = VERSION.matcher(value);
        if (!match.matches()) return -1;
        return Long.parseLong(match.group(1)) * 100_000_000L
                + Long.parseLong(match.group(2)) * 10_000L + Long.parseLong(match.group(3));
    }

    static File apk(Context context) { return new File(context.getCacheDir(), "updates/Serein.apk"); }

    static void download(Context context, Release release, ModelApi.Cancellation cancellation, Progress progress) throws Exception {
        File target = apk(context);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建更新缓存。");
        File temporary = File.createTempFile("download-", ".apk", parent);
        try {
            cancellation.check();
            HttpsURLConnection connection = downloadConnection(release.asset());
            try {
                cancellation.attach(connection);
                try (var input = connection.getInputStream(); var output = Files.newOutputStream(temporary.toPath())) {
                    copyVerified(input, output, release, cancellation, progress);
                }
            } finally {
                cancellation.detach();
                connection.disconnect();
            }
            PackageInfo verified = verifyApk(context, temporary);
            if (version(verified.versionName) != version(release.version())) {
                throw new IOException("APK 版本与发布标签或系统版本不匹配。");
            }
            cancellation.check();
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            saveReceipt(context, release);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    private static AtomicFile receipt(Context context) {
        return new AtomicFile(new File(context.getCacheDir(), "app-update-receipt.json"));
    }

    private static void saveReceipt(Context context, Release release) throws JSONException, IOException {
        var metadata = new JSONObject().put("version", release.version()).put("bytes", release.bytes())
                .put("sha256", release.sha256()).toString().getBytes(StandardCharsets.UTF_8);
        var receipt = receipt(context);
        var output = receipt.startWrite();
        try {
            output.write(metadata);
            receipt.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            receipt.failWrite(output);
            throw error;
        }
    }

    static PackageInfo verifyCachedApk(Context context) throws Exception {
        File apk = apk(context);
        if (!apk.isFile()) throw new IOException("APK 缓存已移除，请重新下载。");
        JSONObject metadata;
        try (var input = receipt(context).openRead()) {
            byte[] raw = input.readNBytes(1025);
            if (raw.length > 1024) throw new IOException("APK 校验记录无效。");
            metadata = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        }
        verifyCachedBytes(apk, metadata.getLong("bytes"), metadata.getString("sha256"));
        PackageInfo verified = verifyApk(context, apk);
        long expectedVersion = version(metadata.getString("version"));
        if (expectedVersion < 0 || version(verified.versionName) != expectedVersion) {
            throw new IOException("APK 缓存版本与下载记录不匹配。");
        }
        return verified;
    }

    static void verifyCachedBytes(File apk, long bytes, String sha256) throws Exception {
        if (!validDigest(bytes, sha256) || apk.length() != bytes) throw new IOException("APK 缓存大小或校验记录无效。");
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(apk.toPath()), digest)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > bytes) throw new IOException("APK 缓存大小与下载记录不一致。");
            }
            if (total != bytes || !sha256.equals(hex(digest.digest()))) throw new IOException("APK 缓存校验失败。");
        }
    }

    private static boolean validDigest(long bytes, String sha256) {
        return bytes > 0 && bytes < APK_LIMIT && sha256.matches("[0-9a-f]{64}");
    }

    static void copyVerified(InputStream input, OutputStream output, Release release, ModelApi.Cancellation cancellation,
                             Progress progress) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = 0;
        long lastReport = System.nanoTime();
        byte[] buffer = new byte[32 * 1024];
        progress.downloaded(0);
        cancellation.check();
        for (int count; (count = input.read(buffer)) != -1;) {
            cancellation.check();
            total += count;
            if (total > release.bytes()) throw new IOException("APK 大小与发布信息不一致。");
            output.write(buffer, 0, count);
            sha.update(buffer, 0, count);
            long now = System.nanoTime();
            if (total == release.bytes() || now - lastReport >= 100_000_000L) {
                progress.downloaded(total);
                lastReport = now;
            }
        }
        cancellation.check();
        progress.verifying();
        if (total != release.bytes() || !release.sha256().equals(hex(sha.digest()))) throw new IOException("APK 校验失败。");
    }

    static PackageInfo verifyApk(Context context, File apk) throws Exception {
        var manager = context.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo next = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (next == null || !context.getPackageName().equals(next.packageName)
                || next.getLongVersionCode() <= installed.getLongVersionCode()) {
            throw new IOException("APK 包名、版本或文件格式不符合更新要求。");
        }
        Signature[] current = installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners();
        Signature[] incoming = next.signingInfo == null ? null : next.signingInfo.getApkContentsSigners();
        if (current == null || incoming == null || current.length == 0
                || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(incoming)))) {
            throw new IOException("APK 签名与当前安装版本不同。");
        }
        if (next.applicationInfo == null || next.applicationInfo.minSdkVersion > android.os.Build.VERSION.SDK_INT) {
            throw new IOException("APK 与当前系统版本不兼容。");
        }
        return next;
    }

    static synchronized ModelCatalog refreshCatalog(Context context) throws Exception {
        byte[] response = json(API + "/contents/app/src/main/assets/model-catalog.json?ref=main");
        byte[] raw = catalogBytes(new JSONObject(new String(response, StandardCharsets.UTF_8)));
        ModelCatalog validated = ModelCatalog.parse(raw);
        AtomicFile cache = catalogFile(context);
        try (var existing = cache.getBaseFile().exists() ? cache.openRead() : context.getAssets().open("model-catalog.json")) {
            if (Arrays.equals(existing.readNBytes(API_LIMIT + 1), raw)) return null;
        }
        var output = cache.startWrite();
        try {
            output.write(raw);
            cache.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            cache.failWrite(output);
            throw error;
        }
        return validated;
    }

    static AtomicFile catalogFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "model-catalog-update.json"));
    }

    static byte[] catalogBytes(JSONObject response) throws Exception {
        int size = response.optInt("size");
        String sha = response.optString("sha");
        if (!"base64".equals(response.optString("encoding")) || size <= 0 || size > ModelCatalog.MAX_BYTES
                || !sha.matches("[0-9a-f]{40}")) throw new IOException("模型目录的发布信息无效。");
        byte[] raw = Base64.getDecoder().decode(response.getString("content").replace("\n", "").replace("\r", ""));
        var sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + raw.length + "\0").getBytes(StandardCharsets.UTF_8));
        if (raw.length != size || !sha.equals(hex(sha1.digest(raw)))) throw new IOException("模型目录校验失败。");
        return raw;
    }

    private static byte[] json(String endpoint) throws IOException {
        var connection = connect(URI.create(endpoint), "application/vnd.github+json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("暂时无法读取 GitHub 发布信息。");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(API_LIMIT + 1);
                if (bytes.length > API_LIMIT) throw new IOException("GitHub 响应超过大小限制。");
                return bytes;
            }
        } finally { connection.disconnect(); }
    }

    private static HttpsURLConnection downloadConnection(URI initial) throws IOException {
        URI url = initial;
        for (int hops = 0; hops <= 3; hops++) {
            if (!allowedDownload(url)) throw new IOException("APK 下载地址不属于 GitHub 发布服务。");
            var connection = connect(url, "application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status == 200) return connection;
                if (status < 300 || status >= 400) throw new IOException("APK 下载失败（HTTP " + status + "）。");
                String next = connection.getHeaderField("Location");
                if (next == null) throw new IOException("APK 下载地址已失效。");
                url = url.resolve(next);
            } catch (IOException | RuntimeException error) {
                connection.disconnect();
                throw error;
            }
            connection.disconnect();
        }
        throw new IOException("APK 下载重定向次数过多。");
    }

    static boolean allowedDownload(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        return "github.com".equals(uri.getHost()) && uri.getRawPath().startsWith("/" + REPO + "/releases/download/")
                || "release-assets.githubusercontent.com".equals(uri.getHost())
                || "objects.githubusercontent.com".equals(uri.getHost());
    }

    private static HttpsURLConnection connect(URI url, String accept) throws IOException {
        var connection = (HttpsURLConnection) url.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "Serein-Android");
        return connection;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
