package app.serein.chat;

import java.util.Locale;

final class Appearance {
    enum Preset {
        SEREIN("雨松", 0, 0, 0),
        ASTRA("星雾", 0xff8582b8, 0xff524796, 0xffbcb4ff),
        PAPER("暖纸", 0xffd2ad86, 0xff963e24, 0xfff6a98e),
        OCEAN("海雾", 0xff83a9eb, 0xff285dd3, 0xffa9c4ff);

        final String label;
        final int base, lightAccent, darkAccent;

        Preset(String label, int base, int lightAccent, int darkAccent) {
            this.label = label;
            this.base = base;
            this.lightAccent = lightAccent;
            this.darkAccent = darkAccent;
        }

        static Preset read(String value) {
            for (Preset preset : values()) if (preset.name().equals(value)) return preset;
            return SEREIN;
        }
    }

    record Palette(int paper, int panel, int ink, int muted, int line, int tint, int accent, int onAccent) {}

    static Palette palette(boolean dark, Preset preset, String baseOverride, String accentOverride) {
        int paper = dark ? 0xff151c18 : 0xfff8faf7;
        int panel = dark ? 0xff1c2520 : 0xffffffff;
        int ink = dark ? 0xffe7eee9 : 0xff192722;
        int muted = dark ? 0xffafc1b5 : 0xff52655b;
        int line = dark ? 0xff3b4940 : 0xffdde5df;
        int tint = dark ? 0xff293f33 : 0xffe1eee6;
        int defaultAccent = dark ? 0xffabcdb9 : 0xff235543;
        int base = color(baseOverride, preset.base);
        if (base != 0) {
            paper = blend(dark ? 0xff15171c : 0xfffafaf9, base, dark ? .13f : .18f);
            panel = blend(dark ? 0xff202229 : 0xffffffff, base, dark ? .16f : .09f);
            line = blend(panel, ink, .20f);
            muted = readable(muted, ink, paper);
        }
        int accent = color(accentOverride, preset == Preset.SEREIN ? defaultAccent
                : dark ? preset.darkAccent : preset.lightAccent);
        boolean customized = preset != Preset.SEREIN || color(accentOverride, 0) != 0;
        if (customized || base != 0) tint = blend(paper, accent, dark ? .22f : .12f);
        int onAccent = customized ? readable(0xff000000, 0xffffffff, accent) : dark ? 0xff193023 : 0xffffffff;
        return new Palette(paper, panel, ink, muted, line, tint, accent, onAccent);
    }

    static int color(String value, int fallback) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) return fallback;
        return 0xff000000 | Integer.parseInt(value.substring(1), 16);
    }

    static String colorInput(String value) {
        String hex = value.strip();
        if (hex.isEmpty()) return "";
        if (!hex.startsWith("#")) hex = "#" + hex;
        if (color(hex, 0) == 0) throw new IllegalArgumentException("请输入六位十六进制颜色，留空恢复默认。");
        return hex.toUpperCase(Locale.ROOT);
    }

    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    static int readable(int preferred, int fallback, int surface) {
        if (contrast(preferred, surface) >= 4.5) return preferred;
        if (contrast(fallback, surface) >= 4.5) return fallback;
        return contrast(0xff000000, surface) >= 4.5 ? 0xff000000 : 0xffffffff;
    }

    static int glass(int fill, int ink, int opacity) {
        int alpha = Math.round(255 * clamp(opacity, 70, 100) / 100f);
        float weight = alpha / 255f;
        if (contrast(ink, blend(0xff000000, fill, weight)) < 4.5
                || contrast(ink, blend(0xffffffff, fill, weight)) < 4.5) return fill;
        return (alpha << 24) | (fill & 0xffffff);
    }

    static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(int color) {
        return .2126 * linear(color >> 16 & 0xff) + .7152 * linear(color >> 8 & 0xff) + .0722 * linear(color & 0xff);
    }

    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }

    static int blend(int a, int b, float weight) {
        return 0xff000000 | mix(a >> 16, b >> 16, weight) << 16 | mix(a >> 8, b >> 8, weight) << 8 | mix(a, b, weight);
    }

    private static int mix(int a, int b, float weight) {
        return Math.round((a & 0xff) * (1 - weight) + (b & 0xff) * weight);
    }
}
