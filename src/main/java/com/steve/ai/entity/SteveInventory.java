package com.steve.ai.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Steve's inventory: a fixed-size list of ItemStacks (default 36 slots,
 * matching a player's main inventory) with NBT persistence.
 *
 * <p>ItemStacks are stored as full stacks (they merge on add, no slot-based
 * layout yet - that keeps the logic simple and the data compact).</p>
 */
public class SteveInventory {

    public static final int DEFAULT_SIZE = 36;
    private static final String NBT_KEY = "Inventory";
    private static final String NBT_SIZE = "Size";

    private final List<ItemStack> stacks;
    private final int maxSize;

    public SteveInventory() {
        this(DEFAULT_SIZE);
    }

    public SteveInventory(int maxSize) {
        this.maxSize = maxSize;
        this.stacks = new ArrayList<>();
    }

    /**
     * Attempts to add the given stack (or part of it) to the inventory.
     * Returns what could NOT be stored (empty stack if everything fit).
     */
    public ItemStack addItem(ItemStack incoming) {
        if (incoming.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remainder = incoming.copy();

        // Merge into existing stacks of the same item first
        for (ItemStack stack : stacks) {
            if (remainder.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameTags(stack, remainder)) {
                int space = stack.getMaxStackSize() - stack.getCount();
                if (space > 0) {
                    int move = Math.min(space, remainder.getCount());
                    stack.grow(move);
                    remainder.shrink(move);
                }
            }
        }

        // Open new stacks while there is room
        while (!remainder.isEmpty() && stacks.size() < maxSize) {
            int perStack = Math.min(remainder.getCount(), remainder.getMaxStackSize());
            ItemStack newStack = remainder.copy();
            newStack.setCount(perStack);
            stacks.add(newStack);
            remainder.shrink(perStack);
        }

        return remainder;
    }

    /**
     * Whether the inventory can hold at least one more item.
     */
    public boolean hasFreeSpace() {
        if (stacks.size() < maxSize) {
            return true;
        }
        for (ItemStack stack : stacks) {
            if (stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return stacks.isEmpty();
    }

    /**
     * Total count of items of the given type across all stacks.
     */
    public int countItem(Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Total number of items across all stacks.
     */
    public int getTotalCount() {
        int total = 0;
        for (ItemStack stack : stacks) {
            total += stack.getCount();
        }
        return total;
    }

    public int getStacksCount() {
        return stacks.size();
    }

    public int getMaxSize() {
        return maxSize;
    }

    public List<ItemStack> getStacks() {
        return stacks;
    }

    /**
     * Removes and returns everything (used for handing items over to a player).
     */
    public List<ItemStack> takeAll() {
        List<ItemStack> taken = new ArrayList<>(stacks);
        stacks.clear();
        return taken;
    }

    public void saveToNBT(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            list.add(stack.save(new CompoundTag()));
        }
        tag.put(NBT_KEY, list);
        tag.putInt(NBT_SIZE, maxSize);
    }

    public void loadFromNBT(CompoundTag tag) {
        stacks.clear();
        ListTag list = tag.getList(NBT_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
    }

    public String toDisplayString() {
        if (stacks.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(stack.getHoverName().getString()).append(" x").append(stack.getCount());
        }
        return sb.toString();
    }
}
