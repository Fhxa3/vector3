package ml.mypals.vectorthree.mc.pose;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartTree;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;

/** A posable model seen as a tree of named parts, whatever it is made of (vanilla model parts, GeckoLib bones). */
public interface PartRig {
    PartTree tree();

    /** Writes each enabled channel of the pose's parts into the model. */
    void apply(EntityPose pose);

    /** The model's pose as absolute limbs: every rotation, and the offsets of parts moved from their rest position. */
    Map<String, EntityPose.Limb> read();

    /** Every part at rest (channels disabled), parents first. */
    Map<String, EntityPose.Limb> rest();

    /** Each part's frame as the model is set up now, drawn with {@code entityMatrix} from a camera at {@code camera}. */
    Map<String, EntityPoses.Frame> frames(Matrix4f entityMatrix, Vec3 camera);

    /**
     * Moves {@code stack} down the tree to the part: through every part it hangs from, and, with {@code self}, the
     * part's own pivot and rotation too.
     */
    void transform(String part, PoseStack stack, boolean self);
}
