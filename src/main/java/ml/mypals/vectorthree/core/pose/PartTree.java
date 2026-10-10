package ml.mypals.vectorthree.core.pose;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parts of a model and which part hangs from which, under names that are unique across the whole tree. The first
 * part (in depth-first order) called {@code head} keeps that name; a later one of the same name becomes
 * {@code head#2}, and so on, so names saved in keyframes keep meaning what they did.
 */
public final class PartTree {
    public static final PartTree EMPTY = new PartTree(new LinkedHashMap<>(), new int[0], List.of());

    private record Node(String raw, @Nullable String parent, List<String> children, int depth) {}

    private final Map<String, Node> nodes;
    private final List<String> order;
    private final int[] inputToOrder;
    private final List<String> roots;

    private PartTree(Map<String, Node> nodes, int[] inputToOrder, List<String> roots) {
        this.nodes = nodes;
        this.order = List.copyOf(nodes.keySet());
        this.inputToOrder = inputToOrder;
        this.roots = roots;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Parts are added in any order; a parent that is missing, itself or part of a cycle makes the part a root. */
    public static final class Builder {
        private final List<String> raws = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();

        private Builder() {}

        /** @return the index of the part, to be given as the parent of its children */
        public int add(String rawName, int parent) {
            raws.add(rawName);
            parents.add(parent);
            return raws.size() - 1;
        }

        public PartTree build() {
            int n = raws.size();
            List<List<Integer>> kids = new ArrayList<>();
            for (int i = 0; i < n; i++) kids.add(new ArrayList<>());
            List<Integer> top = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int p = parents.get(i);
                if (p < 0 || p >= n || p == i) top.add(i);
                else kids.get(p).add(i);
            }
            Map<String, Node> nodes = new LinkedHashMap<>();
            int[] names = new int[n];
            Map<String, Integer> used = new HashMap<>();
            Set<Integer> seen = new HashSet<>();
            List<String> roots = new ArrayList<>();
            String[] unique = new String[n];
            for (int root : top) visit(root, null, 0, kids, unique, used, seen, nodes, roots);
            // whatever no root reaches hangs in a cycle: the first of each becomes a root
            for (int i = 0; i < n; i++) {
                if (!seen.contains(i)) visit(i, null, 0, kids, unique, used, seen, nodes, roots);
            }
            List<String> order = new ArrayList<>(nodes.keySet());
            Map<String, Integer> position = new HashMap<>();
            for (int i = 0; i < order.size(); i++) position.put(order.get(i), i);
            for (int i = 0; i < n; i++) names[i] = position.get(unique[i]);
            return new PartTree(nodes, names, List.copyOf(roots));
        }

        private void visit(int index, @Nullable String parent, int depth, List<List<Integer>> kids, String[] unique,
                           Map<String, Integer> used, Set<Integer> seen, Map<String, Node> nodes, List<String> roots) {
            if (!seen.add(index)) return;
            String raw = raws.get(index);
            int count = used.merge(raw, 1, Integer::sum);
            String name = count == 1 ? raw : raw + "#" + count;
            while (nodes.containsKey(name)) {
                count = used.merge(raw, 1, Integer::sum);
                name = raw + "#" + count;
            }
            unique[index] = name;
            List<String> children = new ArrayList<>();
            nodes.put(name, new Node(raw, parent, children, depth));
            if (parent == null) roots.add(name);
            else nodes.get(parent).children().add(name);
            for (int child : kids.get(index)) visit(child, name, depth + 1, kids, unique, used, seen, nodes, roots);
        }
    }

    /**
     * The same parts under the same names with some hung from other parts: {@code parents} maps a part to its new
     * parent. A link that would make a part its own ancestor, or names a part this tree does not have, is left out.
     */
    public PartTree withParents(Map<String, String> parents) {
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (String name : order) parentOf.put(name, nodes.get(name).parent());
        for (Map.Entry<String, String> link : parents.entrySet()) {
            String child = link.getKey(), parent = link.getValue();
            if (!nodes.containsKey(child) || !nodes.containsKey(parent) || child.equals(parent)) continue;
            String before = parentOf.put(child, parent);
            boolean cycle = false;
            for (String at = parent; at != null && !cycle; at = parentOf.get(at)) cycle = at.equals(child);
            if (cycle) parentOf.put(child, before);
        }
        Map<String, List<String>> kids = new LinkedHashMap<>();
        List<String> top = new ArrayList<>();
        for (String name : order) kids.put(name, new ArrayList<>());
        for (String name : order) {
            String parent = parentOf.get(name);
            if (parent == null) top.add(name);
            else kids.get(parent).add(name);
        }
        Map<String, Node> next = new LinkedHashMap<>();
        for (String root : top) place(root, null, 0, kids, next);
        int[] inputs = new int[inputToOrder.length];
        List<String> nextOrder = new ArrayList<>(next.keySet());
        for (int i = 0; i < inputs.length; i++) inputs[i] = nextOrder.indexOf(order.get(inputToOrder[i]));
        return new PartTree(next, inputs, List.copyOf(top));
    }

    private void place(String name, @Nullable String parent, int depth, Map<String, List<String>> kids, Map<String, Node> out) {
        List<String> children = new ArrayList<>(kids.get(name));
        out.put(name, new Node(nodes.get(name).raw(), parent, children, depth));
        for (String child : children) place(child, name, depth + 1, kids, out);
    }

    /** Every part, parents before their children, siblings in the order they were added. */
    public List<String> names() {
        return order;
    }

    public int size() {
        return nodes.size();
    }

    public boolean contains(String name) {
        return nodes.containsKey(name);
    }

    public List<String> roots() {
        return roots;
    }

    public @Nullable String parent(String name) {
        Node node = nodes.get(name);
        return node == null ? null : node.parent();
    }

    public List<String> children(String name) {
        Node node = nodes.get(name);
        return node == null ? List.of() : List.copyOf(node.children());
    }

    public int depth(String name) {
        Node node = nodes.get(name);
        return node == null ? -1 : node.depth();
    }

    /** The name the part was added under, without any {@code #n} suffix. */
    public String rawName(String name) {
        Node node = nodes.get(name);
        return node == null ? name : node.raw();
    }

    /** The parts from the root down to (not including) {@code name}. */
    public List<String> ancestors(String name) {
        List<String> chain = new ArrayList<>();
        for (String at = parent(name); at != null; at = parent(at)) chain.addFirst(at);
        return chain;
    }

    /** Everything below {@code name}, parents before children. */
    public List<String> descendants(String name) {
        List<String> out = new ArrayList<>();
        collect(name, out);
        return out;
    }

    private void collect(String name, List<String> out) {
        Node node = nodes.get(name);
        if (node == null) return;
        for (String child : node.children()) {
            out.add(child);
            collect(child, out);
        }
    }

    /** The unique name of the {@code index}th part given to the builder. */
    public String nameOfInput(int index) {
        return order.get(inputToOrder[index]);
    }
}
