package com.steve.ai.action.actions;

import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceBlocksTest extends AbstractMinecraftTest {

    @Test
    void stackSizeForMatchesItemStackSize() {
        assertEquals(64, ResourceBlocks.stackSizeFor(Blocks.OAK_LOG));
        assertEquals(64, ResourceBlocks.stackSizeFor(Blocks.BIRCH_LOG));
        assertEquals(64, ResourceBlocks.stackSizeFor(Blocks.COBBLESTONE));
        assertEquals(16, ResourceBlocks.stackSizeFor(Blocks.OAK_SIGN), "signs stack to 16");
        assertEquals(64, ResourceBlocks.stackSizeFor(null));
    }

    @Test
    void resolvesOreShorthand() {
        assertEquals(Blocks.IRON_ORE, ResourceBlocks.parseBlock("iron"));
        assertEquals(Blocks.DIAMOND_ORE, ResourceBlocks.parseBlock("diamond"));
        assertEquals(Blocks.COAL_ORE, ResourceBlocks.parseBlock("coal"));
        assertEquals(Blocks.GOLD_ORE, ResourceBlocks.parseBlock("gold"));
        assertEquals(Blocks.COPPER_ORE, ResourceBlocks.parseBlock("copper"));
    }

    @Test
    void resolvesWoodAndSurfaceBlocks() {
        assertEquals(Blocks.OAK_LOG, ResourceBlocks.parseBlock("oak_log"));
        assertEquals(Blocks.OAK_LOG, ResourceBlocks.parseBlock("wood"));
        assertEquals(Blocks.STONE, ResourceBlocks.parseBlock("stone"));
        assertEquals(Blocks.DIRT, ResourceBlocks.parseBlock("dirt"));
    }

    @Test
    void resolvesNamespacedAndSpacedInput() {
        assertEquals(Blocks.OAK_LOG, ResourceBlocks.parseBlock("minecraft:oak_log"));
        assertEquals(Blocks.IRON_ORE, ResourceBlocks.parseBlock("iron ore"));
    }

    @Test
    void rejectsUnknownAndEmpty() {
        assertNull(ResourceBlocks.parseBlock("unobtainium"));
        assertNull(ResourceBlocks.parseBlock(""));
        assertNull(ResourceBlocks.parseBlock(null));
    }
}
