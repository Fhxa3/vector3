package ml.mypals.vectorthree.core.pose;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which model parts are joined to which although the model lists them side by side: a dragon's neck and tail
 * segments, a player's arms, legs and head, a hat and the head it sits on. Parts hanging from the same part are looked
 * at together, and, as a limb only turns at its own hinge, a joint is only ever made between such siblings:
 * <ul>
 *   <li>numbered segments of a neck, tail or spine (neck1, neck2, ...) chain one after another, and a head hangs from
 *       the last of the neck;</li>
 *   <li>a layer (a hat, sleeves, a jacket) hangs from the limb drawn at the same pivot, told apart by what it is
 *       called when several are drawn there;</li>
 *   <li>the rest of the limbs (head, arms, legs, wings, tail) and the first segment of a chain hang from the body.</li>
 * </ul>
 */
public final class PartJoints {
    /** Two pivots closer than this (in model units, 1/16 block) are the same place. */
    private static final float SAME_PIVOT = 0.8f;
    private static final Pattern NUMBERED = Pattern.compile("^(.*?)_?(\\d+)$");
    private static final String[] SEGMENTED = {"neck", "tail", "spine", "segment"};

    /** A part as the joints see it: its pivot at rest and how much there is of it. */
    public record Part(String name, @Nullable String parent, float x, float y, float z, double volume) {}

    private PartJoints() {}

    /** @return each joined part and the part it hangs from; parts not mentioned hang from what they already hang from */
    public static Map<String, String> infer(List<Part> parts) {
        Map<String, List<Part>> groups = new LinkedHashMap<>();
        for (Part part : parts) {
            if (part.parent() != null) groups.computeIfAbsent(part.parent(), p -> new ArrayList<>()).add(part);
        }
        Map<String, String> links = new LinkedHashMap<>();
        groups.values().forEach(group -> {
            if (group.size() > 1) join(group, links);
        });
        return links;
    }

    private static PartRole roleOf(Part part) {
        return PartRole.of(raw(part.name()));
    }

    private static String raw(String name) {
        int hash = name.indexOf('#');
        return hash < 0 ? name : name.substring(0, hash);
    }

    private static boolean limb(PartRole role) {
        return role != PartRole.ATTACHMENT && role != PartRole.OTHER;
    }

    private static void join(List<Part> group, Map<String, String> links) {
        Map<String, Part> byName = new HashMap<>();
        group.forEach(part -> byName.put(part.name(), part));
        Part torso = null;
        for (Part part : group) {
            if (roleOf(part) == PartRole.TORSO && (torso == null || part.volume() > torso.volume())) torso = part;
        }

        // 1. chains of numbered segments
        Map<String, TreeMap<Integer, Part>> series = new LinkedHashMap<>();
        for (Part part : group) {
            Matcher m = NUMBERED.matcher(raw(part.name()));
            if (!m.matches() || !segmented(m.group(1))) continue;
            series.computeIfAbsent(m.group(1), base -> new TreeMap<>()).put(Integer.parseInt(m.group(2)), part);
        }
        Map<String, String> chain = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<Integer, Part>> entry : series.entrySet()) {
            Part before = null;
            Part last = null;
            for (Part segment : entry.getValue().values()) {
                if (before != null) chain.put(segment.name(), before.name());
                else if (torso != null) chain.put(segment.name(), torso.name());
                before = segment;
                last = segment;
            }
            if (last != null && entry.getKey().contains("neck")) {
                for (Part part : group) {
                    if (roleOf(part) == PartRole.HEAD && !chain.containsKey(part.name()) && !isSegment(part, series)) {
                        chain.put(part.name(), last.name());
                    }
                }
            }
        }
        links.putAll(chain);

        // 2. a part named after another with more on the end (a wing's tip) goes on from it
        for (Part part : group) {
            if (links.containsKey(part.name()) || isSegment(part, series)) continue;
            Part longest = null;
            for (Part other : group) {
                if (other != part && !isSegment(other, series) && raw(part.name()).startsWith(raw(other.name()) + "_")
                        && (longest == null || raw(other.name()).length() > raw(longest.name()).length())) {
                    longest = other;
                }
            }
            if (longest != null) links.put(part.name(), longest.name());
        }

        // 3. layers, 4. the rest of the limbs
        for (Part part : group) {
            if (links.containsKey(part.name()) || isSegment(part, series)) continue;
            PartRole role = roleOf(part);
            if (!limb(role)) {
                Part under = layerUnder(part, group);
                if (under != null) links.put(part.name(), under.name());
            } else if (torso != null && part != torso && role != PartRole.TORSO) {
                links.put(part.name(), torso.name());
            }
        }
    }

    private static boolean segmented(String base) {
        for (String word : SEGMENTED) {
            if (base.contains(word)) return true;
        }
        return false;
    }

    private static boolean isSegment(Part part, Map<String, TreeMap<Integer, Part>> series) {
        return series.values().stream().anyMatch(s -> s.containsValue(part));
    }

    /** The limb a layer is drawn over: at its pivot, and the one it is named after when there are several. */
    private static @Nullable Part layerUnder(Part layer, List<Part> group) {
        List<Part> there = new ArrayList<>();
        for (Part other : group) {
            if (other != layer && limb(roleOf(other)) && near(layer, other)) there.add(other);
        }
        if (there.size() <= 1) return there.isEmpty() ? null : there.get(0);
        String name = raw(layer.name());
        PartRole wanted = name.contains("hat") ? PartRole.HEAD : name.contains("jacket") ? PartRole.TORSO
                : name.contains("sleeve") ? PartRole.ARM : name.contains("pants") ? PartRole.LEG : null;
        if (wanted == null) return null;
        String side = name.startsWith("left") ? "left" : name.startsWith("right") ? "right" : null;
        for (Part candidate : there) {
            boolean sameSide = side == null || raw(candidate.name()).startsWith(side);
            if (roleOf(candidate) == wanted && sameSide) return candidate;
        }
        return null;
    }

    private static boolean near(Part a, Part b) {
        float dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= SAME_PIVOT * SAME_PIVOT;
    }
}
