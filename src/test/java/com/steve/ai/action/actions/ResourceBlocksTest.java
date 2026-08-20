package com.steve.ai.action.actions;

import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void woodRequestsAreDetected() {
        assertTrue(ResourceBlocks.isWoodRequest("wood"));
        assertTrue(ResourceBlocks.isWoodRequest("tree"));
        assertTrue(ResourceBlocks.isWoodRequest("logs"));
        assertTrue(ResourceBlocks.isWoodRequest("дерево"));
        assertTrue(ResourceBlocks.isWoodRequest("брёвна"));
        assertFalse(ResourceBlocks.isWoodRequest("iron_ore"));
        assertFalse(ResourceBlocks.isWoodRequest("oak_log"), "concrete log type is NOT a wood request");
        assertFalse(ResourceBlocks.isWoodRequest("birch_wood"), "concrete wood block is NOT a wood request");
        assertFalse(ResourceBlocks.isWoodRequest("cobblestone"));
        assertFalse(ResourceBlocks.isWoodRequest(null));
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

    @Test
    void coalYieldCountsCoalItemNotOreBlock() {
        ResourceBlocks.ResourceYield yield = ResourceBlocks.yieldFor("coal");
        assertTrue(yield.miningBlocks().contains(Blocks.COAL_ORE));
        assertTrue(yield.miningBlocks().contains(Blocks.DEEPSLATE_COAL_ORE));
        assertTrue(yield.itemMatcher().test(Items.COAL));
        assertFalse(yield.itemMatcher().test(Items.RAW_IRON));
        assertEquals(Items.COAL, yield.representativeItem());
    }

    @Test
    void stoneYieldMinesStoneAndCountsCobblestone() {
        ResourceBlocks.ResourceYield yield = ResourceBlocks.yieldFor("stone");
        assertTrue(yield.miningBlocks().contains(Blocks.STONE));
        assertTrue(yield.miningBlocks().contains(Blocks.COBBLESTONE));
        assertTrue(yield.itemMatcher().test(Items.COBBLESTONE));
        assertEquals(Items.COBBLESTONE, yield.representativeItem());
    }

    @Test
    void cobblestoneYieldMinesStoneToo() {
        ResourceBlocks.ResourceYield yield = ResourceBlocks.yieldFor("cobblestone");
        assertTrue(yield.miningBlocks().contains(Blocks.STONE));
        assertTrue(yield.miningBlocks().contains(Blocks.COBBLESTONE));
        assertTrue(yield.itemMatcher().test(Items.COBBLESTONE));
    }

    @Test
    void ironYieldCountsRawIron() {
        ResourceBlocks.ResourceYield yield = ResourceBlocks.yieldFor("iron");
        assertTrue(yield.miningBlocks().contains(Blocks.IRON_ORE));
        assertTrue(yield.itemMatcher().test(Items.RAW_IRON));
        assertFalse(yield.itemMatcher().test(Items.IRON_ORE));
    }

    @Test
    void unknownResourceReturnsNull() {
        assertNull(ResourceBlocks.yieldFor("unobtainium"));
    }

    @Test
    void fallbackYieldUsesBlockAsItem() {
        ResourceBlocks.ResourceYield yield = ResourceBlocks.yieldFor("oak_log");
        assertTrue(yield.miningBlocks().contains(Blocks.OAK_LOG));
        assertTrue(yield.itemMatcher().test(Items.OAK_LOG));
    }
}
