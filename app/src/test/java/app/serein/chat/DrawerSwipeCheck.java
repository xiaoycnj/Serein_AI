package app.serein.chat;

import static app.serein.chat.DrawerSwipe.Direction.*;

final class DrawerSwipeCheck {
    public static void main(String[] args) {
        DrawerSwipe swipe = new DrawerSwipe();
        require(!DrawerSwipe.startsBetween(12, 0, 100, 24), "system Back area");
        require(DrawerSwipe.startsBetween(24, 0, 100, 24), "inside safe strip");
        require(!DrawerSwipe.startsBetween(28, 32, 100, 24), "outside content");
        require(!DrawerSwipe.startsBetween(100, 0, 100, 24), "past strip");

        swipe.start(OPEN, 30, 100, 0);
        require(!move(swipe, 62, 102, 100), "short motion");
        require(move(swipe, 90, 106, 160), "right swipe");
        require(!move(swipe, 100, 106, 170), "capture once");
        require(swipe.finish(100, 106, 52) == OPEN, "open");
        require(!swipe.tracking() && !swipe.captured(), "reset after completion");

        swipe.start(CLOSE, 220, 100, 0);
        require(move(swipe, 160, 108, 140), "left swipe");
        require(swipe.finish(142, 108, 52) == CLOSE, "close");

        swipe.start(OPEN, 30, 100, 0);
        require(!move(swipe, 34, 126, 65), "vertical scroll wins");
        require(!move(swipe, 140, 130, 160), "scroll cannot turn into open");
        swipe.start(OPEN, 30, 100, 0);
        require(!move(swipe, 95, 110, 600), "long press not intercepted");
        swipe.start(CLOSE, 220, 100, 0);
        require(!move(swipe, 240, 100, 40), "wrong direction");
        require(!move(swipe, 140, 100, 160), "reversed gesture remains rejected");

        swipe.start(OPEN, 30, 100, 0);
        require(move(swipe, 98, 110, 150), "capture before cancellation");
        swipe.cancel();
        require(swipe.captured(), "cannot return an intercepted sequence to children");
        require(swipe.finish(140, 110, 52) == NONE, "cancelled swipe");
        swipe.start(OPEN, 30, 100, 0);
        require(move(swipe, 98, 110, 150), "capture before direction reversal");
        require(swipe.finish(60, 110, 52) == NONE, "no action after pulling back");
        System.out.println("PASS: drawer swipe direction, edge safety, scroll/hold rejection, cancellation and recovery");
    }

    private static boolean move(DrawerSwipe swipe, float x, float y, long time) {
        return swipe.move(x, y, time, 8, 52, 500);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
