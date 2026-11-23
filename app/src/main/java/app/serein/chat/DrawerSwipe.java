package app.serein.chat;

final class DrawerSwipe {
    enum Direction { NONE, OPEN, CLOSE }

    private Direction direction = Direction.NONE;
    private float startX, startY;
    private long startTime;
    private boolean captured;

    static boolean startsBetween(float x, float edge, float end, int systemInset) {
        return x >= Math.max(edge, systemInset) && x < end;
    }

    void start(Direction next, float x, float y, long time) {
        reset();
        direction = next;
        startX = x;
        startY = y;
        startTime = time;
    }

    boolean move(float x, float y, long time, float slop, float distance, long holdTimeout) {
        if (direction == Direction.NONE) return false;
        float forward = (x - startX) * (direction == Direction.OPEN ? 1 : -1);
        float vertical = Math.abs(y - startY);
        if (!captured && (time - startTime >= holdTimeout || forward <= -slop
                || (vertical >= slop && vertical >= Math.abs(forward)))) {
            cancel();
        } else if (!captured && forward >= distance && forward > vertical * 1.5f) {
            captured = true;
            return true;
        }
        return false;
    }

    Direction finish(float x, float y, float distance) {
        float forward = (x - startX) * (direction == Direction.OPEN ? 1 : -1);
        Direction result = captured && forward >= distance && forward > Math.abs(y - startY) * 1.5f
                ? direction : Direction.NONE;
        reset();
        return result;
    }

    boolean tracking() { return direction != Direction.NONE; }
    boolean captured() { return captured; }
    void cancel() { direction = Direction.NONE; }

    void reset() {
        cancel();
        captured = false;
    }
}
