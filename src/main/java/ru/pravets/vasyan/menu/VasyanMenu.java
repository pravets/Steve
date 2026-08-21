package ru.pravets.vasyan.menu;

import ru.pravets.vasyan.entity.VasyanInventory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for Steve's inventory, laid out as a vanilla single chest
 * (3 rows x 9 = 27 slots) so the {@code generic_54} texture renders it with
 * the exact vanilla single-chest blits - no custom geometry.
 *
 * <p>Steve's slots are read-only (players can only TAKE items, never place
 * them).</p>
 */
public class VasyanMenu extends AbstractContainerMenu {

    private static final int STEVE_SLOTS = 27; // 3 rows x 9, single-chest layout
    private static final int PLAYER_SLOTS_START = STEVE_SLOTS;
    private static final int SLOT_COUNT = STEVE_SLOTS + 36;

    private final VasyanInventory container;

    public VasyanMenu(int containerId, Inventory playerInventory, VasyanInventory container) {
        super(ru.pravets.vasyan.menu.VasyanMenus.STEVE_MENU.get(), containerId);
        this.container = container;
        container.startOpen(playerInventory.player);

        // Steve's slots: 3 rows x 9, take-only
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new TakeOnlySlot(container, row * 9 + col, 8 + col * 18, 18 + row * 18));
            }
        }

        // Player inventory: 3 rows x 9 + hotbar (single-chest offsets)
        int playerOffset = (3 - 4) * 18; // -18
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
    public static VasyanMenu fromNetwork(int containerId, Inventory playerInventory, FriendlyByteBuf extra) {
        return new VasyanMenu(containerId, playerInventory, new VasyanInventory());
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
