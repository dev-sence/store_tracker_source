package sence.playf.storetracker.config;

public record TrackedPosition(String dimension, int x, int y, int z) {
    public boolean matches(String dimension, int x, int y, int z) {
        return this.dimension.equals(dimension) && this.x == x && this.y == y && this.z == z;
    }
}
