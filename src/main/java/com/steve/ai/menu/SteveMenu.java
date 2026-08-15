package com.steve.ai.menu;

import com.steve.ai.entity.SteveInventory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for Steve's inventory. Steve's 36 slots are read-only
 * (players can only TAKE items, never place them), the player's own
 * inventory is shown below for direct transfers.
 */
public class SteveMenu extends AbstractContainerMenu {

    private static final int STEVE_SLOTS = 36; // 4 rows x 9

    private final SteveInventory container;

    public SteveMenu(int containerId, Inventory playerInventory, SteveInventory container) {
        super(com.steve.ai.menu.SteveMenus.STEVE_MENU.get(), containerId);
        this.container = container;

        checkContainerSize(container, STEVE_SLOTS);
        container.startOpen(playerInventory.player);

        // Steve's slots: 4 rows x 9, take-only
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new TakeOnlySlot(container, row * 9 + col, 8 + col * 18, 18 + row * 18));
            }
        }

        // Player inventory: 3 rows x 9 + hotbar
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 161));
        }
    }

    /**
     * Client-side factory. The client container starts empty - the server
     * synchronizes the real slot contents right after the menu opens
     * (vanilla slot sync), so no Steve lookup is needed here.
     */
    public static SteveMenu fromNetwork(int containerId, Inventory playerInventory, FriendlyByteBuf extra) {
        return new SteveMenu(containerId, playerInventory, new SteveInventory());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < STEVE_SLOTS) {
                // Steve's slot -> player inventory
                if (!this.moveItemStackTo(stack, STEVE_SLOTS, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Player slot -> Steve's slots; blocked by mayPlace() = false
                if (!this.moveItemStackTo(stack, 0, STEVE_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }

    /**
     * Slot that allows picking items up but never placing them.
     */
    private static class TakeOnlySlot extends Slot {
        TakeOnlySlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return true;
        }
    }
}
