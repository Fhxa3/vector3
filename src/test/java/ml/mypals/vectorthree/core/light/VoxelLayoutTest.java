package ml.mypals.vectorthree.core.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelLayoutTest {
    @Test
    void sectionsAWindowApartShareASlotButNotATag() {
        int near = VoxelLayout.slot(-3, 2, 7);
        int far = VoxelLayout.slot(-3 + VoxelLayout.GX, 2 + VoxelLayout.GY, 7 - VoxelLayout.GZ);

        assertEquals(near, far);
        assertNotEquals(VoxelLayout.tag(-3, 2, 7, 0) >>> 3, VoxelLayout.tag(-3 + VoxelLayout.GX, 2, 7, 0) >>> 3);
    }

    @Test
    void slotsStayInsideTheBuffer() {
        for (int x = -50; x < 50; x += 7)
            for (int y = -9; y < 30; y += 5)
                for (int z = -50; z < 50; z += 7) {
                    int slot = VoxelLayout.slot(x, y, z);
                    assertTrue(slot >= 0 && slot < VoxelLayout.SLOTS);
                }
    }

    @Test
    void tagKeepsFlagsApartFromTheHash() {
        int tag = VoxelLayout.tag(5, -2, 9, VoxelLayout.PRESENT | VoxelLayout.GLASS);

        assertEquals(5, tag & 7);
        assertEquals(VoxelLayout.hash(5, -2, 9) >>> 3, tag >>> 3);
    }

    @Test
    void occupancyBitsRoundTrip() {
        int[] words = new int[VoxelLayout.WORDS * 2];
        VoxelLayout.set(words, VoxelLayout.WORDS, 15, 3, 9);
        VoxelLayout.set(words, VoxelLayout.WORDS, 0, 15, 0);

        assertTrue(VoxelLayout.get(words, VoxelLayout.WORDS, 15, 3, 9));
        assertTrue(VoxelLayout.get(words, VoxelLayout.WORDS, 0, 15, 0));
        assertFalse(VoxelLayout.get(words, VoxelLayout.WORDS, 14, 3, 9));
        assertFalse(VoxelLayout.get(words, 0, 15, 3, 9));
    }

    @Test
    void glassPlaneIsSeparateFromSolidPlane() {
        int[] words = new int[VoxelLayout.WORDS];
        VoxelLayout.setGlass(words, 0, 4, 5, 6);

        assertTrue(VoxelLayout.getGlass(words, 0, 4, 5, 6));
        assertFalse(VoxelLayout.get(words, 0, 4, 5, 6));
    }

    @Test
    void keysDistinguishSections() {
        assertNotEquals(VoxelLayout.key(1, 2, 3), VoxelLayout.key(3, 2, 1));
        assertNotEquals(VoxelLayout.key(-1, 0, 0), VoxelLayout.key(1, 0, 0));
        assertEquals(VoxelLayout.key(-5, -3, 8), VoxelLayout.key(-5, -3, 8));
    }
}
