package app.serein.chat;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

final class FileText {
    private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log");
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    static final class Rejected extends IOException {
        Rejected(String message) { super(message); }
        Rejected(String message, Exception cause) { super(message, cause); }
    }

    static String extension(String name) {
        return name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static String extract(String name, byte[] raw) throws Exception {
        return extract(name, raw, Xml::newPullParser);
    }

    static String extract(String name, byte[] raw, Supplier<XmlPullParser> parser) throws Exception {
        String suffix = extension(name);
        String text;
        if (TEXT.contains(suffix)) text = decode(raw);
        else if (suffix.equals("docx")) text = wordText(raw, parser);
        else throw new Rejected("请选择文本、CSV、JSON、DOCX 或受支持的图片。");
        text = text.strip();
        if (text.isEmpty()) throw new Rejected("文件里没有可读取的文字。");
        if (text.length() > ChatFile.MAX_TEXT) throw new Rejected("文件超过 60,000 个字符，请拆分后导入。");
        return text;
    }

    static String decode(byte[] raw) throws IOException {
        try {
            String text;
            if (raw.length >= 2 && ((raw[0] == (byte) 0xff && raw[1] == (byte) 0xfe)
                    || (raw[0] == (byte) 0xfe && raw[1] == (byte) 0xff))) {
                text = decode(raw, StandardCharsets.UTF_16);
            } else {
                try { text = decode(raw, StandardCharsets.UTF_8); }
                catch (CharacterCodingException invalidUtf8) { text = decode(raw, Charset.forName("GB18030")); }
            }
            if (text.startsWith("\ufeff")) text = text.substring(1);
            if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw new Rejected("文件包含无法读取的文本字符。");
            }
            return text;
        } catch (CharacterCodingException error) { throw new Rejected("无法识别文件编码。", error); }
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String wordText(byte[] raw, Supplier<XmlPullParser> parser) throws Exception {
        // Extract only document.xml. Never unpack or resolve links from the archive.
        try (var zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            byte[] buffer = new byte[8192];
            int expanded = 0;
            int count = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 500) throw new Rejected("DOCX 包含过多条目。");
                if (entry.getName().equals("word/document.xml")) {
                    byte[] xml = zip.readNBytes(2_000_001);
                    if (xml.length > 2_000_000) throw new Rejected("DOCX 正文过大。");
                    return wordXml(xml, parser.get());
                }
                for (int n; (n = zip.read(buffer)) != -1;) {
                    expanded += n;
                    if (expanded > 10_000_000) throw new Rejected("DOCX 解压体积过大。");
                }
            }
        }
        throw new Rejected("无法读取 DOCX 正文。");
    }

    private static String wordXml(byte[] xml, XmlPullParser parser) throws Exception {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        }
        parser.setInput(new ByteArrayInputStream(xml), null);
        StringBuilder text = new StringBuilder();
        boolean readingText = false;
        for (int event; (event = parser.nextToken()) != XmlPullParser.END_DOCUMENT;) {
            if (event == XmlPullParser.DOCDECL || parser.getDepth() > 128) throw new Rejected("DOCX 的 XML 结构不受支持。");
            if (event == XmlPullParser.START_TAG && WORD.equals(parser.getNamespace())) {
                switch (parser.getName()) {
                    case "t" -> readingText = true;
                    case "tab" -> text.append('\t');
                    case "br" -> text.append('\n');
                }
            } else if (event == XmlPullParser.END_TAG && WORD.equals(parser.getNamespace())) {
                if (parser.getName().equals("t")) readingText = false;
                if (parser.getName().equals("p")) text.append('\n');
            } else if (readingText && (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT
                    || event == XmlPullParser.ENTITY_REF) && parser.getText() != null) {
                text.append(parser.getText());
            }
            if (text.length() > ChatFile.MAX_TEXT) throw new Rejected("DOCX 文字过长，请拆分后导入。");
        }
        return text.toString();
    }
}
