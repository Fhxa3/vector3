package ml.mypals.vectorthree.core.pose;

import java.util.Locale;

/** What a model part is, as far as its name says (head, arm, a hat or nose that goes with another part, ...). */
public enum PartRole {
    HEAD, TORSO, ARM, LEG, WING, TAIL, ATTACHMENT, OTHER;

    public static PartRole of(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (containsAny(n, "beak", "nose", "snout", "mouth", "jaw", "hat", "rim", "comb", "jacket", "mane", "horn",
                "antler", "ear", "wattle")) {
            return ATTACHMENT;
        }
        if (containsAny(n, "head", "skull")) return HEAD;
        if (containsAny(n, "body", "torso", "chest", "abdomen")) return TORSO;
        if (containsAny(n, "arm", "hand")) return ARM;
        if (containsAny(n, "leg", "foot", "hoof")) return LEG;
        if (n.contains("wing")) return WING;
        if (n.contains("tail")) return TAIL;
        return OTHER;
    }

    private static boolean containsAny(String s, String... parts) {
        for (String p : parts) {
            if (s.contains(p)) return true;
        }
        return false;
    }
}
