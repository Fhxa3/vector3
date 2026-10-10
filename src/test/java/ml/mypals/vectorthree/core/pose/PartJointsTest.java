package ml.mypals.vectorthree.core.pose;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartJointsTest {
    private static PartJoints.Part part(String name, float x, float y, float z, double volume) {
        return new PartJoints.Part(name, "root", x, y, z, volume);
    }

    @Test
    void playerLimbsAndLayersHangFromTheirBodyAndLimbs() {
        List<PartJoints.Part> parts = List.of(
                part("head", 0, 0, 0, 512), part("hat", 0, 0, 0, 800), part("body", 0, 0, 0, 576),
                part("jacket", 0, 0, 0, 700), part("right_arm", -5, 2, 0, 192), part("left_arm", 5, 2, 0, 192),
                part("left_sleeve", 5, 2, 0, 250), part("right_leg", -1.9f, 12, 0, 192),
                part("left_leg", 1.9f, 12, 0, 192), part("left_pants", 1.9f, 12, 0, 250));
        Map<String, String> links = PartJoints.infer(parts);

        assertEquals("body", links.get("head"));
        assertEquals("head", links.get("hat"));
        assertEquals("body", links.get("jacket"));
        assertEquals("body", links.get("left_arm"));
        assertEquals("left_arm", links.get("left_sleeve"));
        assertEquals("left_leg", links.get("left_pants"));
        assertFalse(links.containsKey("body"));
    }

    @Test
    void dragonSegmentsChainFromTheBody() {
        List<PartJoints.Part> parts = new ArrayList<>(List.of(part("body", 0, 0, 0, 5000), part("head", 0, 0, 0, 900),
                part("left_wing", 12, 0, 0, 300), part("left_wing_tip", 56, 0, 0, 100)));
        for (int i = 1; i <= 5; i++) parts.add(part("neck" + i, 0, 0, 0, 100));
        for (int i = 1; i <= 12; i++) parts.add(part("tail" + i, 0, 0, 0, 100));
        Map<String, String> links = PartJoints.infer(parts);

        assertEquals("body", links.get("neck1"));
        assertEquals("neck1", links.get("neck2"));
        assertEquals("neck4", links.get("neck5"));
        assertEquals("neck5", links.get("head"));
        assertEquals("body", links.get("tail1"));
        assertEquals("tail11", links.get("tail12"));
        assertEquals("body", links.get("left_wing"));
        assertEquals("left_wing", links.get("left_wing_tip"));
    }

    @Test
    void numberedLegsStayIndependentOfEachOther() {
        List<PartJoints.Part> parts = List.of(part("head", 0, 0, -4, 200), part("body", 0, 0, 0, 800),
                part("leg1", -2, 0, 0, 80), part("leg2", 2, 0, 0, 80));
        Map<String, String> links = PartJoints.infer(parts);

        assertEquals("body", links.get("leg1"));
        assertEquals("body", links.get("leg2"));
    }

    @Test
    void partsWithoutABodyAreLeftAlone() {
        List<PartJoints.Part> parts = List.of(part("left_arm", 5, 2, 0, 100), part("right_arm", -5, 2, 0, 100));
        assertTrue(PartJoints.infer(parts).isEmpty());
    }

    @Test
    void joiningNeverCrossesParents() {
        List<PartJoints.Part> parts = List.of(new PartJoints.Part("body", "root", 0, 0, 0, 800),
                new PartJoints.Part("head", "other", 0, 0, 0, 200));
        assertTrue(PartJoints.infer(parts).isEmpty());
    }
}
