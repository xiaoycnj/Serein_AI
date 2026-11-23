package app.serein.chat;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class LocalChatFont {
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    static File file(Context context) { return new File(context.getFilesDir(), "chat-font.sfnt"); }

    static Typeface load(Context context) {
        File font = file(context);
        return font.isFile() ? new Typeface.Builder(font).build() : null;
    }

    static void importFont(Context context, Uri uri) throws IOException {
        File target = file(context);
        File temporary = File.createTempFile("chat-font-", ".sfnt", target.getParentFile());
        try {
            try (var input = context.getContentResolver().openInputStream(uri);
                 var output = Files.newOutputStream(temporary.toPath())) {
                if (input == null) throw new IOException("Missing font contents");
                byte[] header = input.readNBytes(4);
                if (header.length != 4) throw new IOException("Truncated font");
                int signature = java.nio.ByteBuffer.wrap(header).getInt();
                if (signature != 0x00010000 && signature != 0x4f54544f) throw new IOException("Unsupported font format");
                output.write(header);
                byte[] buffer = new byte[64 * 1024];
                int size = header.length;
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > MAX_BYTES) throw new IOException("Font exceeds the size limit");
                    output.write(buffer, 0, count);
                }
            }
            new Font.Builder(temporary).build();
            if (new Typeface.Builder(temporary).build() == null) throw new IOException("Invalid font face");
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
