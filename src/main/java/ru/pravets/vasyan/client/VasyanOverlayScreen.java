package ru.pravets.vasyan.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Invisible overlay screen that captures input for the Vasyan GUI
 * This prevents game controls from activating while typing
 */
public class VasyanOverlayScreen extends Screen {
    
    public VasyanOverlayScreen() {
        super(Component.literal("Vasyan AI"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Don't render anything - the VasyanGUI renders via overlay
        // This screen is just to capture input
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Close dialog with ESC or Ctrl+K. Plain K must NOT close - it is used
        // for typing (e.g. the letter "k" in commands).
        boolean isCtrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || (keyCode == GLFW.GLFW_KEY_K && isCtrl)) {
            VasyanGUI.toggle();
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
            return true;
        }

        return VasyanGUI.handleKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // Pass character input to VasyanGUI
        return VasyanGUI.handleCharTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        VasyanGUI.handleMouseClick(mouseX, mouseY, button);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        VasyanGUI.handleMouseScroll(scrollDelta);
        return true;
    }

    @Override
    public void removed() {
        // Clean up when screen is closed
        if (VasyanGUI.isOpen()) {
            VasyanGUI.toggle();
        }
    }
}

