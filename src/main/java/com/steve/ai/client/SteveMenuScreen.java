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
 * <p>Renders the vanilla single-chest background texture (generic_27) with a
 * stretched middle section so the 4-row container (36 slots) looks like a
 * regular chest - resource packs and container-styling mods that replace the
 * standard texture keep working. Slot rendering is handled by the base class.</p>
 */
public class SteveMenuScreen extends AbstractContainerScreen<SteveMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
        new ResourceLocation("textures/gui/container/generic_27.png");

    /** Container rows = 4, so the panel is 186 px tall (chest texture is 168). */
    private static final int PANEL_HEIGHT = 186;

    public SteveMenuScreen(SteveMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Top strip (17 px, header with border)
        graphics.blit(CHEST_TEXTURE, x, y, 0, 0, 176, 17, 176, 168);
        // Middle section stretched from the texture's plain area (17..151)
        graphics.blit(CHEST_TEXTURE, x, y + 17, 0, 17, 176, PANEL_HEIGHT - 34, 176, 134);
        // Bottom strip (17 px, footer border)
        graphics.blit(CHEST_TEXTURE, x, y + PANEL_HEIGHT - 17, 0, 151, 176, 17, 176, 168);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, 8, 6, 4210752);
        graphics.drawString(this.font, this.playerInventoryTitle, 8, this.imageHeight - 92, 4210752);
    }
}
