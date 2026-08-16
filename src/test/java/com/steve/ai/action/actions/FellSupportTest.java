package com.steve.ai.action.actions;

import com.steve.ai.entity.SteveInventory;
import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FellSupportTest extends AbstractMinecraftTest {

    // ---- findSolidPillarBlock ----

    @Test
    void findsDirtForPillar() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.DIRT, 32));

        ItemStack pillar = FellSupport.findSolidPillarBlock(null, null, inventory, Blocks.OAK_LOG);

        assertFalse(pillar.isEmpty());
        assertEquals(Items.DIRT, pillar.getItem());
    }

    @Test
    void refusesToBurnLogs() {
        SteveInventory inventory = new SteveInventory(9);
        inventory.addItem(new ItemStack(Items.OAK_LOG, 32));

        assertTrue(FellSupport.findSolidPillarBlock(null, null, inventory, Blocks.OAK_LOG).isEmpty());
    }

    @Test
    void emptyInventoryHasNoPillarBlock() {
        SteveInventory inventory = new SteveInventory(9);
        assertTrue(FellSupport.findSolidPillarBlock(null, null, inventory, Blocks.OAK_LOG).isEmpty());
    }

    // ---- collectConnectedLogs ----

    @Test
    void collectsCrossComponent() {
        Set<BlockPos> logs = Set.of(
            new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(0, -1, 0)
        );
        List<BlockPos> component = FellSupport.collectConnectedLogs(
            new BlockPos(0, 0, 0), logs::contains, 200);

        assertEquals(4, component.size());
        assertTrue(component.containsAll(logs));
    }

    @Test
    void isolatesFromAnotherTree() {
        Set<BlockPos> logs = Set.of(new BlockPos(0, 0, 0), new BlockPos(10, 0, 0));
        List<BlockPos> component = FellSupport.collectConnectedLogs(
            new BlockPos(0, 0, 0), logs::contains, 200);

        assertEquals(1, component.size());
        assertEquals(new BlockPos(0, 0, 0), component.get(0));
    }

    @Test
    void capsComponentSize() {
        Set<BlockPos> logs = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            logs.add(new BlockPos(i, 0, 0));
        }
        List<BlockPos> component = FellSupport.collectConnectedLogs(
            new BlockPos(0, 0, 0), logs::contains, 3);

        assertEquals(3, component.size());
    }

    @Test
    void followsBranch() {
        Set<BlockPos> logs = Set.of(
            new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(1, 0, 1)
        );
        List<BlockPos> component = FellSupport.collectConnectedLogs(
            new BlockPos(0, 0, 0), logs::contains, 200);

        assertEquals(3, component.size());
    }

    @Test
    void startThatIsNotLogYieldsEmpty() {
        List<BlockPos> component = FellSupport.collectConnectedLogs(
            new BlockPos(5, 5, 5), p -> false, 200);
        assertTrue(component.isEmpty());
    }

    // ---- hasNearbyBlock ----

    @Test
    void findsBlockWithinRadius() {
        Set<BlockPos> leaves = Set.of(new BlockPos(0, 0, 0), new BlockPos(2, 1, 0));
        assertTrue(FellSupport.hasNearbyBlock(new BlockPos(1, 0, 0), leaves::contains, 3));
    }

    @Test
    void noBlockNearbyReturnsFalse() {
        Set<BlockPos> leaves = Set.of(new BlockPos(10, 0, 0));
        assertFalse(FellSupport.hasNearbyBlock(new BlockPos(0, 0, 0), leaves::contains, 3));
    }

    @Test
    void zeroRadiusChecksOnlyCenter() {
        Set<BlockPos> leaves = Set.of(new BlockPos(0, 0, 0));
        assertTrue(FellSupport.hasNearbyBlock(new BlockPos(0, 0, 0), leaves::contains, 0));
        assertFalse(FellSupport.hasNearbyBlock(new BlockPos(1, 0, 0), leaves::contains, 0));
    }
}
