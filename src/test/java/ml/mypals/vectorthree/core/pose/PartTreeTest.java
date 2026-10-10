package ml.mypals.vectorthree.core.pose;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartTreeTest {
    @Test
    void keepsHierarchyAndDepthFirstOrder() {
        PartTree.Builder b = PartTree.builder();
        int root = b.add("root", -1);
        int body = b.add("body", root);
        int head = b.add("head", root);
        b.add("hat", head);
        b.add("left_arm", body);
        PartTree tree = b.build();

        assertEquals(List.of("root", "body", "left_arm", "head", "hat"), tree.names());
        assertEquals(List.of("root"), tree.roots());
        assertNull(tree.parent("root"));
        assertEquals("head", tree.parent("hat"));
        assertEquals(List.of("body", "head"), tree.children("root"));
        assertEquals(2, tree.depth("hat"));
        assertEquals(List.of("root", "head"), tree.ancestors("hat"));
        assertEquals(List.of("body", "left_arm", "head", "hat"), tree.descendants("root"));
    }

    @Test
    void duplicateNamesGetSuffixesAndTheFirstKeepsItsName() {
        PartTree.Builder b = PartTree.builder();
        int root = b.add("root", -1);
        int a = b.add("segment", root);
        int c = b.add("segment", a);
        int d = b.add("segment", c);
        PartTree tree = b.build();

        assertEquals(List.of("root", "segment", "segment#2", "segment#3"), tree.names());
        assertEquals("segment", tree.nameOfInput(a));
        assertEquals("segment#2", tree.nameOfInput(c));
        assertEquals("segment#3", tree.nameOfInput(d));
        assertEquals("segment", tree.rawName("segment#3"));
        assertEquals("segment#2", tree.parent("segment#3"));
    }

    @Test
    void namesDoNotDependOnInsertionOrder() {
        PartTree.Builder b = PartTree.builder();
        int late = b.add("leg", 2);
        int early = b.add("leg", 2);
        b.add("root", -1);
        PartTree tree = b.build();

        assertEquals(List.of("root", "leg", "leg#2"), tree.names());
        assertEquals("leg", tree.nameOfInput(late));
        assertEquals("leg#2", tree.nameOfInput(early));
    }

    @Test
    void badParentsMakeRoots() {
        PartTree.Builder b = PartTree.builder();
        b.add("a", 7);
        b.add("b", 1);
        b.add("c", 2);
        PartTree tree = b.build();

        assertEquals(3, tree.size());
        assertTrue(tree.contains("a"));
        assertTrue(tree.roots().contains("a"));
        assertEquals(tree.size(), tree.names().size());
    }

    @Test
    void cyclesAreBrokenAtTheirFirstPart() {
        PartTree.Builder b = PartTree.builder();
        b.add("x", 1);
        b.add("y", 0);
        PartTree tree = b.build();

        assertEquals(List.of("x"), tree.roots());
        assertEquals("x", tree.parent("y"));
    }

    @Test
    void unknownNamesAreHarmless() {
        PartTree tree = PartTree.EMPTY;
        assertFalse(tree.contains("head"));
        assertEquals(-1, tree.depth("head"));
        assertEquals(List.of(), tree.children("head"));
        assertEquals(List.of(), tree.descendants("head"));
    }
}
