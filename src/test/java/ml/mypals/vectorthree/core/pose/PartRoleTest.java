package ml.mypals.vectorthree.core.pose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartRoleTest {
    @Test
    void classifiesByName() {
        assertEquals(PartRole.HEAD, PartRole.of("head"));
        assertEquals(PartRole.ATTACHMENT, PartRole.of("hat"));
        assertEquals(PartRole.ATTACHMENT, PartRole.of("right_ear"));
        assertEquals(PartRole.TORSO, PartRole.of("body"));
        assertEquals(PartRole.ARM, PartRole.of("left_arm"));
        assertEquals(PartRole.LEG, PartRole.of("right_hind_leg"));
        assertEquals(PartRole.WING, PartRole.of("left_wing_tip"));
        assertEquals(PartRole.TAIL, PartRole.of("tail"));
        assertEquals(PartRole.OTHER, PartRole.of("bone3"));
    }
}
