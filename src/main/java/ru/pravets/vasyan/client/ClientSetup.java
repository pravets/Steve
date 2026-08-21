package ru.pravets.vasyan.client;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.menu.VasyanMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-side setup for entity renderers and other client-only initialization
 */
@Mod.EventBusSubscriber(modid = VasyanMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    private static final ResourceLocation STEVE_TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(VasyanMenus.STEVE_MENU.get(), VasyanMenuScreen::new);
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {        event.registerEntityRenderer(VasyanMod.VASYAN_ENTITY.get(), context -> 
            new HumanoidMobRenderer<VasyanEntity, PlayerModel<VasyanEntity>>(
                context,
                new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false),
                0.5F
            ) {
                @Override
                public ResourceLocation getTextureLocation(VasyanEntity entity) {
                    return STEVE_TEXTURE;
                }
            }
        );    }
}

