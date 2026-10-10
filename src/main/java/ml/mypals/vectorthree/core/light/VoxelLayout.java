package ml.mypals.vectorthree.core.light;

/**
 * How the GPU copy of the world's occluding blocks is laid out. The copy is a window of {@code GX x GY x GZ}
 * 16-block sections around the camera; a section lives in the slot its coordinates wrap to, and a tag per slot
 * says which section the slot currently holds. The GLSL in {@code include/light_voxel.glsl} mirrors this.
 */
public final class VoxelLayout {
    public static final int GX = 24, GY = 12, GZ = 24;
    public static final int SLOTS = GX * GY * GZ;
    /** 32-bit words of one plane of occupancy bits in a section: 16 * 16 * 16 bits. */
    public static final int PLANE = 128;
    /** A section holds two planes: opaque blocks, then see-through full blocks such as glass. */
    public static final int WORDS = PLANE * 2;
    /** Tag flags: the slot holds the section it is tagged for; it has any occluder; it has see-through blocks. */
    public static final int PRESENT = 1, FILLED = 2, GLASS = 4;
    public static final int MAX_BOXES = 64;

    private VoxelLayout() {}

    public static int hash(int sx, int sy, int sz) {
        return sx * 73856093 ^ sy * 19349663 ^ sz * 83492791;
    }

    public static int tag(int sx, int sy, int sz, int flags) {
        return hash(sx, sy, sz) & ~7 | flags;
    }

    public static int slot(int sx, int sy, int sz) {
        return (Math.floorMod(sy, GY) * GZ + Math.floorMod(sz, GZ)) * GX + Math.floorMod(sx, GX);
    }

    public static long key(int sx, int sy, int sz) {
        return (sx & 0x3FFFFFFL) | (sz & 0x3FFFFFFL) << 26 | (sy & 0xFFFL) << 52;
    }

    /** Bit index of a block inside its section; word {@code bit >> 5}, bit {@code bit & 31}. */
    public static int bit(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    public static void set(int[] words, int offset, int x, int y, int z) {
        int bit = bit(x, y, z);
        words[offset + (bit >> 5)] |= 1 << (bit & 31);
    }

    public static void setGlass(int[] words, int offset, int x, int y, int z) {
        set(words, offset + PLANE, x, y, z);
    }

    public static boolean getGlass(int[] words, int offset, int x, int y, int z) {
        return get(words, offset + PLANE, x, y, z);
    }

    public static boolean get(int[] words, int offset, int x, int y, int z) {
        int bit = bit(x, y, z);
        return (words[offset + (bit >> 5)] >>> (bit & 31) & 1) != 0;
    }
}
