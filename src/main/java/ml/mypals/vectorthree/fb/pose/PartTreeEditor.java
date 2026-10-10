package ml.mypals.vectorthree.fb.pose;

import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.flag.ImGuiTreeNodeFlags;
import imgui.moulberry90.type.ImString;
import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartTree;
import ml.mypals.vectorthree.fb.Editors;
import ml.mypals.vectorthree.fb.channel.ChannelRows;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A pose's parts as the tree they hang in: each part opens to its rotation and offset, followed by the parts hanging
 * from it. Parts the keyframe has but the model does not (the entity is not loaded, or is another model) follow the
 * tree in a list of their own.
 */
final class PartTreeEditor {
    private static final ImString FILTER = new ImString(64);
    private static boolean editedOnly;
    private static int bulk;
    private static @Nullable String lastFocus;

    private final PartTree tree;
    private final Map<String, EntityPose.Limb> rest;
    private final boolean additive;
    private final String needle = FILTER.get().trim().toLowerCase(Locale.ROOT);
    private final int bulkOpen = bulk;
    private final Set<String> reveal = new HashSet<>();
    private final Map<String, Boolean> shown = new HashMap<>();
    private EntityPose pose;

    private PartTreeEditor(EntityPose pose, PartTree tree, Map<String, EntityPose.Limb> rest) {
        this.pose = pose;
        this.tree = tree;
        this.rest = rest;
        this.additive = pose.mode() == EntityPose.Mode.ADDITIVE;
    }

    static EntityPose render(EntityPose pose, PartTree tree, Map<String, EntityPose.Limb> rest) {
        toolbar();
        PartTreeEditor editor = new PartTreeEditor(pose, tree, rest);
        bulk = 0;
        String focus = Editors.POSE_GIZMO.part();
        if (focus != null && !focus.equals(lastFocus)) editor.reveal.addAll(tree.ancestors(focus));
        lastFocus = focus;

        for (String root : tree.roots()) editor.node(root, 0);
        List<String> unmatched = pose.parts().keySet().stream().filter(name -> !tree.contains(name)).toList();
        if (!unmatched.isEmpty()) {
            ImGui.separator();
            ImGui.textDisabled(I18n.get("vector3.entity_pose.unmatched"));
            for (String name : unmatched) editor.node(name, 0);
        }
        return editor.pose;
    }

    private static void toolbar() {
        ImGui.setNextItemWidth(Math.max(80, ImGui.getContentRegionAvailX() * 0.45f));
        ImGui.inputTextWithHint("##part_filter", I18n.get("vector3.entity_pose.filter"), FILTER);
        ImGui.sameLine();
        if (ImGui.checkbox(I18n.get("vector3.entity_pose.edited_only"), editedOnly)) editedOnly = !editedOnly;
        if (ImGui.button(I18n.get("vector3.entity_pose.expand_all"))) bulk = 1;
        ImGui.sameLine();
        if (ImGui.button(I18n.get("vector3.entity_pose.collapse_all"))) bulk = -1;
    }

    // A part is shown when it matches, or something hanging from it does.
    private boolean visible(String name) {
        if (needle.isEmpty() && !editedOnly) return true;
        Boolean known = shown.get(name);
        if (known != null) return known;
        boolean match = (needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
                || EntityPoseKeyframeType.label(name).toLowerCase(Locale.ROOT).contains(needle))
                && (!editedOnly || pose.parts().getOrDefault(name, EntityPose.Limb.NONE).active());
        for (String child : tree.children(name)) match |= visible(child);
        shown.put(name, match);
        return match;
    }

    private void node(String name, int depth) {
        if (!visible(name)) return;
        List<String> children = tree.children(name);
        EntityPose.Limb limb = pose.parts().getOrDefault(name, EntityPose.Limb.NONE);

        ImGui.pushID(name);
        // Picks the part for the viewport gizmo.
        if (ImGui.radioButton("##gizmo", name.equals(Editors.POSE_GIZMO.part()))) Editors.POSE_GIZMO.focus(name);
        if (ImGui.isItemHovered()) ImGui.setTooltip(I18n.get("vector3.entity_pose.gizmo_part"));
        ImGui.sameLine();

        if (bulkOpen != 0) ImGui.setNextItemOpen(bulkOpen > 0);
        else if (reveal.contains(name)) ImGui.setNextItemOpen(true);
        else if (depth == 0 || limb.active()) ImGui.setNextItemOpen(true, ImGuiCond.Once);
        String label = EntityPoseKeyframeType.label(name) + (limb.active() ? " •" : "");
        boolean open = ImGui.treeNodeEx("##node", ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.SpanAvailWidth, label);
        if (ImGui.isItemHovered() && !name.equals(label)) ImGui.setTooltip(name);
        contextMenu(name, children);
        if (open) {
            limbRows(name);
            for (String child : children) node(child, depth + 1);
            ImGui.treePop();
        }
        ImGui.popID();
    }

    private void contextMenu(String name, List<String> children) {
        if (!ImGui.beginPopupContextItem("##part_menu")) return;
        if (ImGui.menuItem(I18n.get("vector3.entity_pose.clear_part"))) clear(Set.of(name));
        ImGui.beginDisabled(children.isEmpty());
        if (ImGui.menuItem(I18n.get("vector3.entity_pose.clear_subtree"))) {
            Set<String> all = new HashSet<>(tree.descendants(name));
            all.add(name);
            clear(all);
        }
        ImGui.endDisabled();
        ImGui.endPopup();
    }

    private void clear(Set<String> names) {
        Map<String, EntityPose.Limb> kept = new LinkedHashMap<>(pose.parts());
        kept.keySet().removeAll(names);
        pose = pose.withParts(kept);
    }

    private void limbRows(String name) {
        EntityPose.Limb limb = pose.parts().getOrDefault(name, EntityPose.Limb.NONE);

        // Rotation and offset switch on separately, so another track can drive the channel left off here.
        if (ImGui.checkbox("##rotate", limb.rotate())) {
            // A part new to this keyframe starts at rest (or at zero when adding on top); otherwise the values stay.
            EntityPose.Limb atRest = rest.getOrDefault(name, EntityPose.Limb.NONE);
            EntityPose.Limb start = pose.parts().containsKey(name) ? limb
                    : additive ? limb.withRotation(0, 0, 0) : limb.withRotation(atRest.xRot(), atRest.yRot(), atRest.zRot());
            limb = start.withRotate(!limb.rotate());
            pose = pose.withPart(name, limb);
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(I18n.get("vector3.entity_pose.rotate.tooltip"));
        ImGui.sameLine();
        float[] rotation = {limb.xRot(), limb.yRot(), limb.zRot()};
        ImGui.beginDisabled(!limb.rotate());
        if (ImGui.dragFloat3(I18n.get("vector3.entity_pose.rotation"), rotation, 0.5f)) {
            limb = limb.withRotation(rotation[0], rotation[1], rotation[2]);
            pose = pose.withPart(name, limb);
        }
        ChannelRows.mark(name + ":rotate");
        ImGui.endDisabled();

        if (ImGui.checkbox("##move", limb.move())) {
            limb = limb.withMove(!limb.move());
            pose = pose.withPart(name, limb);
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(I18n.get("vector3.entity_pose.move.tooltip"));
        ImGui.sameLine();
        float[] offset = {limb.x(), limb.y(), limb.z()};
        ImGui.beginDisabled(!limb.move());
        if (ImGui.dragFloat3(I18n.get("vector3.entity_pose.offset"), offset, 0.05f)) {
            limb = limb.withOffset(offset[0], offset[1], offset[2]);
            pose = pose.withPart(name, limb);
        }
        ChannelRows.mark(name + ":move");
        ImGui.endDisabled();
    }
}
