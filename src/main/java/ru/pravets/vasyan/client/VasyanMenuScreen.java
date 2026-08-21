package ru.pravets.vasyan.client;

import ru.pravets.vasyan.menu.VasyanMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screen for Steve's inventory menu.
 *
 * <p>Renders exactly like a vanilla single chest: the {@code generic_54}
 * texture is blitted with the same two slices the vanilla {@code ChestScreen}
 * uses for single chests (upper 71px + lower 96px at texture y=126). Resource
 * packs / container styling mods that replace the standard texture keep
 * working.</p>
 */
public class VasyanMenuScreen extends AbstractContainerScreen<VasyanMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
        new ResourceLocation("textures/gui/container/generic_54.png");

    public VasyanMenuScreen(VasyanMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 168;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // Vanilla single-chest slices: upper (0..71) + lower (texture y 126..222)
        graphics.blit(CHEST_TEXTURE, x, y, 0, 0, this.imageWidth, 71);
        graphics.blit(CHEST_TEXTURE, x, y + 71, 0, 126, this.imageWidth, 96);
    }
}
