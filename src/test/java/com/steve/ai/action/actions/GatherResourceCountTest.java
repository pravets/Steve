package com.steve.ai.action.actions;

import com.steve.ai.entity.SteveInventory;
import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quota counting for GatherResourceAction: the gathered amount is an
 * INVENTORY delta (pickup fact), never the block-break fact - drops lost in
 * water and logs spent on a pillar must not inflate the quota.
 *
 * Item tag bindings (#minecraft:logs) require a running server, so the any-log
 * matcher is simulated here with an explicit item set; the production matcher
 * lives in the instance method.
 */
class GatherResourceCountTest extends AbstractMinecraftTest {

    private static final Predicate<Item> ANY_LOG =
        Set.of(Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG,
            Items.STRIPPED_SPRUCE_LOG, Items.JUNGLE_LOG)::contains;

    @Test
    void anyLogCountsEveryLogType() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.OAK_LOG, 10));
        inv.addItem(new ItemStack(Items.BIRCH_LOG, 5));
        inv.addItem(new ItemStack(Items.STRIPPED_SPRUCE_LOG, 3));

        assertEquals(18, GatherResourceAction.countResource(inv, ANY_LOG));
    }

    @Test
    void anyLogIgnoresPillarMaterial() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.DIRT, 32));
        inv.addItem(new ItemStack(Items.COBBLESTONE, 16));
        inv.addItem(new ItemStack(Items.OAK_LOG, 4));

        assertEquals(4, GatherResourceAction.countResource(inv, ANY_LOG),
            "dirt/stone gathered for a pillar must never count as wood");
    }

    @Test
    void specificResourceCountsOnlyThatItem() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.OAK_LOG, 10));
        inv.addItem(new ItemStack(Items.BIRCH_LOG, 10));
        inv.addItem(new ItemStack(Items.DIRT, 10));

        assertEquals(10, GatherResourceAction.countResource(inv, item -> item == Items.OAK_LOG));
    }

    @Test
    void deltaSemanticsCountOnlyWhatWasAdded() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.OAK_LOG, 30));
        int baseline = GatherResourceAction.countResource(inv, item -> item == Items.OAK_LOG);

        inv.addItem(new ItemStack(Items.OAK_LOG, 20));

        assertEquals(20, GatherResourceAction.countResource(inv, item -> item == Items.OAK_LOG) - baseline,
            "\"mine 50 more\" = delta over what was already in the inventory");
    }

    @Test
    void spentLogsDropTheCountUntilReturned() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.OAK_LOG, 50));

        // 5 logs spent on a pillar mid-felling (they return via vacuum after
        // the pillar is dismantled)
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == Items.OAK_LOG) {
                inv.removeItem(i, 5);
                break;
            }
        }

        assertEquals(45, GatherResourceAction.countResource(inv, item -> item == Items.OAK_LOG),
            "logs currently spent on a pillar reduce the gathered count until dismantled");
    }

    @Test
    void nonMatchingItemsNeverCount() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.IRON_ORE, 8));
        inv.addItem(new ItemStack(Items.STICK, 12));

        assertEquals(0, GatherResourceAction.countResource(inv, ANY_LOG));
        assertEquals(0, GatherResourceAction.countResource(inv, item -> item == Items.OAK_LOG));
    }

    @Test
    void coalRequestCountsCoalItemsNotOreBlockItems() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.COAL, 5));
        inv.addItem(new ItemStack(Items.COAL_ORE, 3)); // should not count

        assertEquals(5, GatherResourceAction.countResource(inv, item -> item == Items.COAL));
    }

    @Test
    void stoneRequestCountsCobblestoneDrops() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.COBBLESTONE, 12));
        inv.addItem(new ItemStack(Items.STONE, 4)); // block item, not a drop

        assertEquals(12, GatherResourceAction.countResource(inv, item -> item == Items.COBBLESTONE));
    }

    @Test
    void deltaCountingUsesYieldMatcher() {
        SteveInventory inv = new SteveInventory(9);
        inv.addItem(new ItemStack(Items.COAL, 2));
        int baseline = GatherResourceAction.countResource(inv, item -> item == Items.COAL);

        inv.addItem(new ItemStack(Items.COAL, 3));

        assertEquals(3, GatherResourceAction.countResource(inv, item -> item == Items.COAL) - baseline);
    }
}
