package com.steve.ai.client;

import com.steve.ai.menu.SteveMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screen for Steve's inventory menu.
 *
 * <p>Uses the vanilla double-chest texture ({@code generic_54}) and the exact
 * same blit as the vanilla {@code ChestScreen} double-chest rendering, so the
 * menu looks like a regular large chest and resource packs / container
 * styling mods that replace the standard texture keep working.</p>
 */
public class SteveMenuScreen extends AbstractContainerScreen<SteveMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
        new ResourceLocation("textures/gui/container/generic_54.png");

    public SteveMenuScreen(SteveMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // Identical to ChestScreen's double-chest pass: whole texture, no slicing
        graphics.blit(CHEST_TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);
    }
}
