package com.steve.ai.action.actions;

import com.steve.ai.test.McTestBootstrap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceBlocksTest {

    @BeforeAll
    static void bootstrap() {
        McTestBootstrap.bootstrap();
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
