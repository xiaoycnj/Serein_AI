package app.serein.chat;

final class AppearanceCheck {
    public static void main(String[] args) {
        var baseline = Appearance.palette(false, Appearance.Preset.SEREIN, "", "");
        require(baseline.paper() == 0xfff8faf7 && baseline.accent() == 0xff235543 && baseline.tint() == 0xffe1eee6,
                "old light defaults retained");
        var legacy = Appearance.palette(false, Appearance.Preset.read(""), "", "#33577B");
        require(legacy.accent() == 0xff33577b && legacy.paper() == baseline.paper(), "old accent settings retained");
        require(Appearance.colorInput(" 1ea4a0 ").equals("#1EA4A0"), "normalization");
        require(Appearance.colorInput(" ").isEmpty(), "empty restores preset");
        try {
            Appearance.colorInput("#01234567");
            throw new AssertionError("accepted a non-RGB value");
        } catch (IllegalArgumentException expected) {
            // Invalid colors never reach the saved palette.
        }
        for (var preset : Appearance.Preset.values()) {
            for (boolean dark : new boolean[]{false, true}) {
                for (String color : new String[]{"", "#FFFFFF", "#000000", "#777777", "#FF0000"}) {
                    var palette = Appearance.palette(dark, preset, color, color);
                    require(Appearance.contrast(palette.onAccent(), palette.accent()) >= 4.5, "send button contrast");
                    require(Appearance.contrast(palette.ink(), palette.paper()) >= 4.5, "base text contrast");
                    int fill = Appearance.color(color, palette.panel());
                    int ink = Appearance.readable(fill, palette.ink(), fill);
                    for (int amount : new int[]{70, 90, 100}) {
                        int glass = Appearance.glass(fill, ink, amount);
                        float alpha = (glass >>> 24) / 255f;
                        for (int backdrop : new int[]{0xff000000, 0xffffffff}) {
                            require(Appearance.contrast(ink, Appearance.blend(backdrop, glass, alpha)) >= 4.5,
                                    "composer text contrast with wallpaper");
                        }
                    }
                }
            }
        }
        require(Appearance.glass(0xffffffff, 0xff000000, 70) >>> 24 < 255, "glass permits transparent surface");
        System.out.println("PASS: appearance legacy defaults, hex overrides and readable custom or glass surfaces");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
