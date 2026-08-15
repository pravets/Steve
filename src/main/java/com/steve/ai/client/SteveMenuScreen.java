package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.menu.SteveMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.AABB;

/**
 * Simple screen for Steve's inventory menu. No vanilla texture fits a
 * 4-row container, so the background is a plain translucent panel;
 * item icons and slot contents are rendered by the base class.
 */
public class SteveMenuScreen extends AbstractContainerScreen<SteveMenu> {

    public SteveMenuScreen(SteveMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 184;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // Panel background + header bar
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xC0121212);
        graphics.fill(x, y, x + this.imageWidth, y + 17, 0xD03A3A3A);
        // Separator line above the player inventory
        graphics.fill(x, y + 93, x + this.imageWidth, y + 95, 0xFF3A3A3A);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, 8, 6, 0xFFFFFF);
        graphics.drawString(this.font, this.playerInventoryTitle, 8, this.imageHeight - 92, 0xFFFFFF);
        // Hint that Steve's slots are take-only
        graphics.drawString(this.font, Component.literal("\u00a77take only"), 8, this.imageHeight - 102, 0xFFFFFF);
    }
}
