package app.serein.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** User-imported copies only. Attachment content and the library index are encrypted separately. */
final class FileSpace {
    static final int MAX_LIBRARY = 128;
    private static final long MAX_LIBRARY_BYTES = 100L * 1024 * 1024;
    private static final Map<String, String> IMAGES = Map.of("png", "image/png", "jpg", "image/jpeg",
            "jpeg", "image/jpeg", "webp", "image/webp");
    private final Vault vault;
    private final File directory;
    private List<ChatFile> catalog;

    FileSpace(Context context, Vault vault) {
        this.vault = vault;
        directory = new File(context.getFilesDir(), "chat-files");
        try { catalog = ChatFile.readList(new JSONArray(vault.read("file_space", "[]")), MAX_LIBRARY); }
        catch (JSONException | IllegalArgumentException error) { throw new IllegalStateException("无法读取本机资料库", error); }
    }

    synchronized List<ChatFile> library() { return catalog; }

    synchronized ChatFile copyToChat(ChatFile source) throws Exception {
        if (!catalog.contains(source)) throw new FileNotFoundException("资料已移除。");
        byte[] content = read(source);
        try {
            ChatFile item = new ChatFile(newId(), source.name(), source.mime(), source.bytes(), source.characters());
            store(item, content, false);
            return item;
        } finally { Arrays.fill(content, (byte) 0); }
    }

    ChatFile importFile(Context context, Uri source, boolean inLibrary) throws Exception {
        var resolver = context.getContentResolver();
        String name = "";
        try (var cursor = resolver.query(source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = ChatFile.cleanName(cursor.getString(0));
        }
        if (name.isBlank()) throw new FileText.Rejected("所选文件没有文件名。");
        String mime = IMAGES.get(FileText.extension(name));
        if (inLibrary && mime != null) throw new FileText.Rejected("请在聊天输入框添加图片。资料库支持文本和 DOCX。");
        int limit = mime == null ? ChatFile.MAX_BYTES : ChatFile.MAX_IMAGE;
        byte[] raw;
        try (var input = resolver.openInputStream(source)) {
            if (input == null) throw new IOException("无法读取所选文件。");
            raw = input.readNBytes(limit + 1);
        }
        byte[] content = raw;
        try {
            if (raw.length == 0 || raw.length > limit) {
                throw new FileText.Rejected(mime == null ? "请选择 5 MiB 以内的非空文件。" : "请选择 2 MiB 以内的非空图片。");
            }
            String id = newId();
            int characters = 0;
            if (mime == null) {
                String text = FileText.extract(name, raw);
                content = text.getBytes(StandardCharsets.UTF_8);
                characters = text.length();
                mime = "text/plain";
            } else {
                var bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                if (!mime.equals(bounds.outMimeType) || bounds.outWidth < 1 || bounds.outHeight < 1
                        || bounds.outWidth > 4096 || bounds.outHeight > 4096) {
                    throw new FileText.Rejected("图片格式不匹配，或尺寸超过 4096 像素。");
                }
            }
            ChatFile item = new ChatFile(id, name, mime, raw.length, characters);
            store(item, content, inLibrary);
            return item;
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (content != raw) Arrays.fill(content, (byte) 0);
        }
    }

    private synchronized void store(ChatFile item, byte[] content, boolean inLibrary) throws Exception {
        if (inLibrary && (catalog.size() >= MAX_LIBRARY
                || catalog.stream().mapToLong(ChatFile::bytes).sum() + item.bytes() > MAX_LIBRARY_BYTES)) {
            throw new FileText.Rejected("资料库已达容量上限，请先移除部分文件。");
        }
        AtomicFile target = file(item.id());
        byte[] packed = Vault.seal("chat_file_" + item.id(), content);
        var output = target.startWrite();
        try {
            output.write(packed);
            target.finishWrite(output);
        } catch (IOException | RuntimeException error) { target.failWrite(output); throw error; }
        if (inLibrary) {
            List<ChatFile> next = new ArrayList<>(catalog);
            next.add(0, item);
            try { saveCatalog(next); }
            catch (RuntimeException error) { target.delete(); throw error; }
        }
    }

    synchronized byte[] read(ChatFile item) throws Exception {
        int limit = item.image() ? item.bytes() : 4 * ChatFile.MAX_TEXT;
        byte[] packed;
        try (var input = file(item.id()).openRead()) { packed = input.readNBytes(limit + 30); }
        if (packed.length > limit + 29) throw new IOException("附件大小与记录不一致。");
        return Vault.unseal("chat_file_" + item.id(), packed);
    }

    String text(ChatFile item) throws Exception {
        if (item.image()) throw new IllegalArgumentException("Image has no extracted text");
        byte[] clear = read(item);
        try { return new String(clear, StandardCharsets.UTF_8); }
        finally { Arrays.fill(clear, (byte) 0); }
    }

    synchronized String libraryText(ChatFile item) throws Exception {
        if (!catalog.contains(item)) throw new FileNotFoundException("资料已移除。");
        return text(item);
    }

    Bitmap previewImage(ChatFile item) throws Exception {
        if (!item.image()) throw new IllegalArgumentException("Expected an image file");
        byte[] data = read(item);
        try {
            var options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1300) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) throw new IOException("无法预览图片。");
            return bitmap;
        } finally { Arrays.fill(data, (byte) 0); }
    }

    synchronized boolean exists(ChatFile item) { return file(item.id()).getBaseFile().isFile(); }

    synchronized void removeLibrary(ChatFile item) {
        if (!catalog.contains(item)) return;
        List<ChatFile> next = new ArrayList<>(catalog);
        next.remove(item);
        saveCatalog(next);
        file(item.id()).delete();
    }

    synchronized void removeOrphan(String id) {
        if (catalog.stream().noneMatch(item -> item.id().equals(id))) file(id).delete();
    }

    synchronized void prune(Set<String> keep, long cutoff) {
        var files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!name.matches("[0-9a-f]{32}\\.bin") || file.lastModified() > cutoff) continue;
            String id = name.substring(0, 32);
            if (!keep.contains(id)) removeOrphan(id);
        }
    }

    private AtomicFile file(String id) {
        if (!ChatFile.validId(id)) throw new IllegalArgumentException("Invalid file id");
        return new AtomicFile(new File(directory, id + ".bin"));
    }

    private static String newId() { return UUID.randomUUID().toString().replace("-", ""); }

    private void saveCatalog(List<ChatFile> next) {
        try { vault.write("file_space", ChatFile.writeList(next).toString()); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        catalog = List.copyOf(next);
    }
}
