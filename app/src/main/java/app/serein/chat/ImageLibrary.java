package app.serein.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ImageLibrary {
    static final List<String> MIME_TYPES = List.of("image/png", "image/jpeg", "image/webp");
    private final File directory;
    private final LruCache<String, Bitmap> previews = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };

    ImageLibrary(Context context) { directory = new File(context.getFilesDir(), "generated-images"); }

    GeneratedImage store(ImageGeneration.Request request, byte[] image) throws Exception {
        try {
            var metadata = bounds(image);
            String mime = metadata.outMimeType;
            Bitmap thumbnail = decodePreview(image, metadata);
            String id = UUID.randomUUID().toString().replace("-", "");
            GeneratedImage item = new GeneratedImage(id, request.provider().id, request.model(), request.size(), mime);
            AtomicFile target = new AtomicFile(file(id));
            byte[] sealed = Vault.seal("image_" + id, image);
            var output = target.startWrite();
            try {
                output.write(sealed);
                target.finishWrite(output);
            } catch (IOException | RuntimeException error) {
                target.failWrite(output);
                throw error;
            }
            previews.put(id, thumbnail);
            return item;
        } finally { Arrays.fill(image, (byte) 0); }
    }

    Bitmap preview(GeneratedImage image) throws Exception {
        Bitmap cached = previews.get(image.id());
        if (cached != null) return cached;
        byte[] clear = read(image);
        try {
            Bitmap bitmap = decodePreview(clear, bounds(clear));
            previews.put(image.id(), bitmap);
            return bitmap;
        } finally { Arrays.fill(clear, (byte) 0); }
    }

    private static Bitmap decodePreview(byte[] data, BitmapFactory.Options options) throws IOException {
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1300) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("生图结果无法解码。");
        return bitmap;
    }

    byte[] read(GeneratedImage image) throws Exception {
        byte[] sealed;
        try (var input = new AtomicFile(file(image.id())).openRead()) {
            sealed = input.readNBytes(ImageGeneration.MAX_IMAGE + 30);
        }
        if (sealed.length > ImageGeneration.MAX_IMAGE + 29) throw new IOException("Invalid encrypted image length");
        return Vault.unseal("image_" + image.id(), sealed);
    }

    private static BitmapFactory.Options bounds(byte[] image) throws IOException {
        var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, options);
        if (!MIME_TYPES.contains(options.outMimeType == null ? "" : options.outMimeType)
                || options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 4096 || options.outHeight > 4096) {
            throw new IOException("生图结果不是受支持的 PNG、JPEG 或 WebP 图片。");
        }
        return options;
    }

    private File file(String id) { return new File(directory, id + ".bin"); }

    void remove(String id) {
        previews.remove(id);
        new AtomicFile(file(id)).delete();
    }

    void prune(Set<String> keep, long latestOrphanTime) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!name.matches("[0-9a-f]{32}\\.bin") || file.lastModified() > latestOrphanTime) continue;
            String id = name.substring(0, 32);
            if (!keep.contains(id)) remove(id);
        }
    }
}
