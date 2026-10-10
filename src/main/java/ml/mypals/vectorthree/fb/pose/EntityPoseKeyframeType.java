package ml.mypals.vectorthree.fb.pose;

import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartTree;
import ml.mypals.vectorthree.mc.pose.EntityPoses;

import ml.mypals.vectorthree.mc.shape.entity.ShapeEntities;
import ml.mypals.vectorthree.mc.shape.model.TexturedObjShape;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImInt;
import ml.mypals.vectorthree.fb.editor.EntityPicker;
import ml.mypals.vectorthree.fb.editor.VectorIcons;
import ml.mypals.vectorthree.fb.custom.CustomKeyframeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Poses a living entity's model parts (head, arms, legs, ...); keyframes blend part by part. */
public final class EntityPoseKeyframeType extends CustomKeyframeType<EntityPose> {
    public static final EntityPoseKeyframeType INSTANCE = new EntityPoseKeyframeType();

    private EntityPoseKeyframeType() {
        super("vector3_entity_pose", "vector3.keyframe_type.entity_pose", EntityPose.class);
    }

    @Override public String icon() { return VectorIcons.icon(VectorIcons.POSE_TRACK, null); }

    @Override protected EntityPose createValue() { return new EntityPose(null, EntityPose.Mode.ABSOLUTE, Map.of()); }

    @Override protected EntityPose sanitize(EntityPose value) { return new EntityPose(value.entity(), value.mode(), value.parts()); }

    @Override protected EntityPose lerp(EntityPose from, EntityPose to, double amount) { return from.lerp(to, (float) amount); }

    @Override protected void apply(EntityPose value, KeyframeHandler handler) { EntityPoses.request(value); }

    @Override
    protected EntityPose edit(EntityPose value) {
        EntityPose edited = value;
        UUID picked = EntityPicker.combo(I18n.get("vector3.entity_pose.entity"), edited.entity(), true);
        if (!Objects.equals(picked, edited.entity())) edited = edited.withEntity(picked);

        String[] modes = {I18n.get("vector3.entity_pose.mode.absolute"), I18n.get("vector3.entity_pose.mode.additive")};
        ImInt mode = new ImInt(edited.mode().ordinal());
        if (ImGui.combo(I18n.get("vector3.entity_pose.mode"), mode, modes)) edited = edited.withMode(EntityPose.Mode.values()[mode.get()]);
        if (ImGui.isItemHovered()) ImGui.setTooltip(I18n.get("vector3.entity_pose.mode.tooltip"));
        if (edited.entity() == null) return edited;

        TexturedObjShape obj = ShapeEntities.objShape(edited.entity());
        Entity entity = ShapeEntities.resolve(edited.entity());
        Map<String, EntityPose.Limb> rest = new LinkedHashMap<>();
        if (obj != null) obj.partNames().forEach(name -> rest.put(name, EntityPose.Limb.NONE));
        else rest.putAll(EntityPoses.restPose(entity));

        Map<String, EntityPose.Limb> snapshot = obj != null ? null : EntityPoses.takeSnapshot(edited.entity());
        if (snapshot != null) {
            // Captured values are absolute; in additive mode the captured rotations start from zero instead.
            boolean zeroed = edited.mode() == EntityPose.Mode.ADDITIVE;
            Map<String, EntityPose.Limb> captured = new LinkedHashMap<>();
            snapshot.forEach((name, limb) -> captured.put(name, zeroed ? EntityPose.Limb.NONE.withRotate(true) : limb));
            edited = edited.withParts(captured);
        }
        if (obj == null) {
            ImGui.beginDisabled(entity == null || EntityPoses.snapshotPending(edited.entity()));
            if (ImGui.button(I18n.get("vector3.entity_pose.capture"))) EntityPoses.requestSnapshot(edited.entity());
            ImGui.endDisabled();
            if (ImGui.isItemHovered(imgui.moulberry90.flag.ImGuiHoveredFlags.AllowWhenDisabled)) {
                ImGui.setTooltip(I18n.get(entity == null ? "vector3.entity_pose.not_loaded" : "vector3.entity_pose.capture.tooltip"));
            }
        }

        PartTree tree = obj != null ? obj.partTree() : EntityPoses.tree(entity);
        if (tree.size() == 0 && edited.parts().isEmpty()) {
            ImGui.textDisabled(I18n.get("vector3.entity_pose.no_parts"));
            return edited;
        }
        return PartTreeEditor.render(edited, tree, rest);
    }

    // "right_hind_leg" -> "Right Hind Leg"; the common part names are translated.
    public static String label(String part) {
        String key = "vector3.entity_pose.part." + part;
        if (Language.getInstance().has(key)) return I18n.get(key);
        StringBuilder text = new StringBuilder();
        for (String word : part.split("_")) {
            if (word.isEmpty()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return text.toString();
    }
}
