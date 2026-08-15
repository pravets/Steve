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
 * Container menu for Steve's inventory, laid out as a double chest
 * (6 rows x 9 = 54 slots) so the vanilla {@code generic_54} chest texture
 * renders it without custom geometry.
 *
 * <p>Steve's slots are read-only (players can only TAKE items, never place
 * them). The last two rows (slots 36-53) are empty and take-only too; they
 * exist only so the menu matches the double-chest layout.</p>
 */
public class SteveMenu extends AbstractContainerMenu {

    private static final int STEVE_SLOTS = 54; // 6 rows x 9, double-chest layout
    private static final int PLAYER_SLOTS_START = STEVE_SLOTS;
    private static final int SLOT_COUNT = STEVE_SLOTS + 36;

    private final SteveInventory container;

    public SteveMenu(int containerId, Inventory playerInventory, SteveInventory container) {
        super(com.steve.ai.menu.SteveMenus.STEVE_MENU.get(), containerId);
        this.container = container;
        container.startOpen(playerInventory.player);

        // Steve's slots: 6 rows x 9, take-only (slots 36-53 read as EMPTY)
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new TakeOnlySlot(container, row * 9 + col, 8 + col * 18, 18 + row * 18));
            }
        }

        // Player inventory: 3 rows x 9 + hotbar (double-chest offsets)
        int playerOffset = (6 - 4) * 18; // 36
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                    8 + col * 18, 103 + playerOffset + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 161 + playerOffset));
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
                if (!this.moveItemStackTo(stack, PLAYER_SLOTS_START, SLOT_COUNT, true)) {
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
