package app.serein.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class Wallpaper {
    static File file(Context context) {
        return new File(context.getFilesDir(), "wallpaper.jpg");
    }

    static void importImage(Context context, Uri uri) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        Bitmap image = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            float ratio = Math.min(1f, 1536f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        AtomicFile target = new AtomicFile(file(context));
        try {
            FileOutputStream stream = target.startWrite();
            try {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 85, stream)) throw new IOException("Image encoding failed");
                target.finishWrite(stream);
            } catch (IOException | RuntimeException error) {
                target.failWrite(stream);
                throw error;
            }
        } finally {
            image.recycle();
        }
    }
}
