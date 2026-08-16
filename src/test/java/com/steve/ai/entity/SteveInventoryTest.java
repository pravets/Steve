package com.steve.ai.entity;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.DataVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SteveInventory (add/merge/capacity/NBT logic).
 *
 * Vanilla Items require Minecraft's registries, which are initialized via
 * SharedConstants.setVersion + Bootstrap.bootStrap() before the tests.
 */
class SteveInventoryTest {

    @BeforeAll
    static void bootstrap() {
        try {
            SharedConstants.setVersion(new WorldVersion() {
                @Override
                public String getName() { return "1.20.1"; }
                @Override
                public String getId() { return "1.20.1"; }
                @Override
                public DataVersion getDataVersion() { return new DataVersion(3465); }
                @Override
                public int getProtocolVersion() { return 765; }
                @Override
                public int getPackVersion(PackType type) { return 15; }
                @Override
                public Date getBuildTime() { return new Date(0); }
                @Override
                public boolean isStable() { return true; }
            });
        } catch (IllegalStateException e) {
            // Version already set by another test class in the same JVM.
        }
        try {
            Bootstrap.bootStrap();
        } catch (IllegalStateException e) {
            // Already bootstrapped by another test class in the same JVM.
        }
    }

    @Test
    void addItemMergesIntoExistingStack() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));
        inventory.addItem(new ItemStack(Items.OAK_LOG, 20));

        assertEquals(1, inventory.getStacksCount(), "Same item must merge into one stack");
        assertEquals(30, inventory.countItem(Items.OAK_LOG));
        assertEquals(30, inventory.getTotalCount());
    }

    @Test
    void addItemCreatesNewStacksForDifferentItems() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));
        inventory.addItem(new ItemStack(Items.IRON_INGOT, 5));

        assertEquals(2, inventory.getStacksCount());
        assertEquals(10, inventory.countItem(Items.OAK_LOG));
        assertEquals(5, inventory.countItem(Items.IRON_INGOT));
    }

    @Test
    void addItemReturnsRemainderWhenInventoryIsFull() {
        SteveInventory inventory = new SteveInventory(2);
        assertTrue(inventory.addItem(new ItemStack(Items.OAK_LOG, 64)).isEmpty());
        assertTrue(inventory.addItem(new ItemStack(Items.OAK_LOG, 64)).isEmpty());

        ItemStack remainder = inventory.addItem(new ItemStack(Items.OAK_LOG, 10));
        assertEquals(10, remainder.getCount(), "Nothing fits - full stack must be returned");
        assertEquals(128, inventory.getTotalCount());
    }

    @Test
    void addItemSplitsOversizedStackAcrossSlots() {
        SteveInventory inventory = new SteveInventory(9);
        // 150 oak logs in a 64-max stack: 64 + 64 + 22
        ItemStack remainder = inventory.addItem(new ItemStack(Items.OAK_LOG, 150));

        assertTrue(remainder.isEmpty());
        assertEquals(3, inventory.getStacksCount());
        assertEquals(150, inventory.countItem(Items.OAK_LOG));
    }

    @Test
    void addItemReturnsPartialRemainderWhenCapacityRunsOut() {
        SteveInventory inventory = new SteveInventory(1);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 60));

        // Only 4 fit into the existing stack's remaining space, 56 must come back
        ItemStack remainder = inventory.addItem(new ItemStack(Items.OAK_LOG, 60));
        assertEquals(56, remainder.getCount());
        assertEquals(64, inventory.getTotalCount());
    }

    @Test
    void hasFreeSpaceReflectsCapacity() {
        SteveInventory inventory = new SteveInventory(1);
        assertTrue(inventory.hasFreeSpace());
        inventory.addItem(new ItemStack(Items.OAK_LOG, 64));
        assertFalse(inventory.hasFreeSpace(), "Single full stack - no free space");
    }

    @Test
    void countItemIsZeroForUnknownItem() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 3));
        assertEquals(0, inventory.countItem(Items.DIAMOND));
    }

    @Test
    void takeAllClearsInventory() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));
        inventory.addItem(new ItemStack(Items.IRON_INGOT, 5));

        var taken = inventory.takeAll();
        assertEquals(2, taken.size());
        assertTrue(inventory.isEmpty());
        assertEquals(0, inventory.getTotalCount());
    }

    @Test
    void getStacksIsUnmodifiable() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 1));

        assertThrows(UnsupportedOperationException.class,
            () -> inventory.getStacks().clear());
    }

    @Test
    void nbtRoundTripPreservesContents() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));
        inventory.addItem(new ItemStack(Items.IRON_INGOT, 5));

        CompoundTag tag = new CompoundTag();
        inventory.saveToNBT(tag);

        SteveInventory loaded = new SteveInventory(9);
        loaded.loadFromNBT(tag);

        assertEquals(2, loaded.getStacksCount());
        assertEquals(10, loaded.countItem(Items.OAK_LOG));
        assertEquals(5, loaded.countItem(Items.IRON_INGOT));
    }

    @Test
    void loadFromNBTClampsToMaxSize() {
        // Craft a tag with more entries than maxSize
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < 40; i++) {
            list.add(new ItemStack(Items.OAK_LOG, 1).save(new CompoundTag()));
        }
        tag.put("Inventory", list);

        SteveInventory loaded = new SteveInventory(9);
        loaded.loadFromNBT(tag);
        assertEquals(9, loaded.getStacksCount(),
            "Load must clamp to maxSize even with oversized NBT");
    }

    // ==================== Container (chest menu) access ====================

    @Test
    void containerGetItemReturnsEmptyForEmptySlot() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));

        assertFalse(inventory.getItem(0).isEmpty());
        assertTrue(inventory.getItem(5).isEmpty(), "Empty slot reads as EMPTY");
        assertTrue(inventory.getItem(-1).isEmpty());
        assertEquals(9, inventory.getContainerSize());
    }

    @Test
    void containerRemoveItemTakesPartialStack() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));

        ItemStack taken = inventory.removeItem(0, 3);
        assertEquals(3, taken.getCount());
        assertEquals(7, inventory.countItem(Items.OAK_LOG));
        assertEquals(1, inventory.getStacksCount());
    }

    @Test
    void containerRemoveItemClearsSlotWithoutShifting() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));
        inventory.addItem(new ItemStack(Items.DIAMOND, 2)); // slot 1

        ItemStack taken = inventory.removeItem(0, 64);
        assertEquals(10, taken.getCount());
        assertTrue(inventory.getItem(0).isEmpty(), "Slot 0 must be empty after removal");
        assertTrue(inventory.getItem(1).is(Items.DIAMOND),
            "Slot 1 must keep DIAMOND - indices must stay stable");
        assertEquals(2, inventory.countItem(Items.DIAMOND));
    }

    @Test
    void containerSetItemPlacesAndReplaces() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));

        // Replace slot 0
        inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        assertEquals(5, inventory.countItem(Items.IRON_INGOT));
        assertEquals(0, inventory.countItem(Items.OAK_LOG));

        // Write into an empty slot beyond the last non-empty one
        inventory.setItem(1, new ItemStack(Items.DIAMOND, 2));
        assertEquals(2, inventory.countItem(Items.DIAMOND));
        assertEquals(2, inventory.getStacksCount());

        // Clearing slot 0 must NOT shift DIAMOND out of slot 1
        inventory.setItem(0, ItemStack.EMPTY);
        assertTrue(inventory.getItem(0).isEmpty());
        assertTrue(inventory.getItem(1).is(Items.DIAMOND));
        assertEquals(1, inventory.getStacksCount());
        assertEquals(0, inventory.countItem(Items.IRON_INGOT));
    }

    @Test
    void setItemIntoEmptySlotBeyondStacksIsNotLost() {
        // Regression: with a compact list, placing into slot 5 while only
        // slot 0 is occupied silently dropped the item (cursor was consumed).
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 10));

        inventory.setItem(5, new ItemStack(Items.DIAMOND, 3));

        assertTrue(inventory.getItem(5).is(Items.DIAMOND),
            "Item placed into an empty slot must be stored, not dropped");
        assertEquals(3, inventory.countItem(Items.DIAMOND));
        assertEquals(2, inventory.getStacksCount());
    }

    @Test
    void stillValidWithoutOwnerReturnsTrue() {
        SteveInventory inventory = new SteveInventory(9);
        assertTrue(inventory.stillValid(null),
            "Detached inventory (unit tests) must be considered valid");
    }
}