package com.steve.ai;

import com.steve.ai.command.SteveCommands;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.registry.EntityRegistry;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.world.biome.BiomeGenBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = SteveMod.MODID, name = SteveMod.NAME, version = SteveMod.VERSION)
public class SteveMod {
    public static final String MODID = "steve";
    public static final String NAME = "Steve AI Mod";
    public static final String VERSION = "1.0.0-mc1.7.10";
    
    public static final Logger LOGGER = LogManager.getLogger(MODID);
    
    @Instance(MODID)
    public static SteveMod instance;
    
    private static SteveManager steveManager;
    
    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Steve AI Mod - Pre Initialization");
        
        // Load configuration
        SteveConfig.init(event.getSuggestedConfigurationFile());
        
        // Register Steve entity
        int entityId = EntityRegistry.findGlobalUniqueEntityId();
        EntityRegistry.registerGlobalEntityID(SteveEntity.class, "Steve", entityId);
        EntityRegistry.registerModEntity(
            SteveEntity.class,
            "Steve",
            entityId,
            this,
            80, // tracking range
            3,  // update frequency
            true // send velocity updates
        );
        
        // Optional: Add spawning in world (commented out for manual spawning only)
        // EntityRegistry.addSpawn(SteveEntity.class, 0, 0, 0, EnumCreatureType.creature, BiomeGenBase.getBiomeGenArray());
        
        LOGGER.info("Registered Steve entity with ID: " + entityId);
    }
    
    @EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("Steve AI Mod - Initialization");
        
        // Initialize Steve manager
        steveManager = new SteveManager();
        
        LOGGER.info("Steve AI Mod initialized successfully");
    }
    
    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // Register commands
        SteveCommands.register(event);
        LOGGER.info("Registered Steve commands");
    }
    
    public static SteveManager getSteveManager() {
        return steveManager;
    }
}
