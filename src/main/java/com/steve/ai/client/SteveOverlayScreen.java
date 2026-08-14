package com.steve.ai.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Invisible overlay screen that captures input for the Steve GUI
 * This prevents game controls from activating while typing
 */
public class SteveOverlayScreen extends Screen {
    
    public SteveOverlayScreen() {
        super(Component.literal("Steve AI"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Don't render anything - the SteveGUI renders via overlay
        // This screen is just to capture input
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Close dialog with ESC or Ctrl+K. Plain K must NOT close - it is used
        // for typing (e.g. the letter "k" in commands).
        boolean isCtrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || (keyCode == GLFW.GLFW_KEY_K && isCtrl)) {
            SteveGUI.toggle();
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
            return true;
        }

        return SteveGUI.handleKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // Pass character input to SteveGUI
        return SteveGUI.handleCharTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        SteveGUI.handleMouseClick(mouseX, mouseY, button);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        SteveGUI.handleMouseScroll(scrollDelta);
        return true;
    }

    @Override
    public void removed() {
        // Clean up when screen is closed
        if (SteveGUI.isOpen()) {
            SteveGUI.toggle();
        }
    }
}

