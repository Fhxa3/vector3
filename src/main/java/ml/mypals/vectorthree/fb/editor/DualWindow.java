package ml.mypals.vectorthree.fb.editor;

import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiHoveredFlags;
import ml.mypals.vectorthree.mc.window.SdlDisplays;
import ml.mypals.vectorthree.mc.window.SecondWindow;
import net.minecraft.client.resources.language.I18n;

public final class DualWindow {
    private DualWindow() {}

    public static void renderMenu() {
        boolean multi = SdlDisplays.count() > 1;
        boolean open = SecondWindow.isOpen();
        if (!multi && open) SecondWindow.close();
        if (!multi) ImGui.beginDisabled();
        if (ImGui.menuItem(I18n.get("vector3.dualwindow.title"), "", open)) {
            if (open) SecondWindow.close();
            else SecondWindow.open();
        }
        if (!multi) ImGui.endDisabled();
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip(I18n.get(!multi ? "vector3.dualwindow.single" : SecondWindow.failed() ? "vector3.dualwindow.failed" : "vector3.dualwindow.tooltip"));
        }
    }
}
