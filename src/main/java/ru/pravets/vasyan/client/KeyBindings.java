package ru.pravets.vasyan.client;

import com.mojang.blaze3d.platform.InputConstants;
import ru.pravets.vasyan.VasyanMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = VasyanMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyBindings {
    
    public static final String KEY_CATEGORY = "key.categories.steve";
    
    public static KeyMapping TOGGLE_GUI;
    public static KeyMapping VOICE_PTT;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        TOGGLE_GUI = new KeyMapping(
            "key.steve.toggle_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, // K key
            KEY_CATEGORY
        );
        event.register(TOGGLE_GUI);

        VOICE_PTT = new KeyMapping(
            "key.steve.voice_ptt",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V, // V key - push-to-talk
            KEY_CATEGORY
        );
        event.register(VOICE_PTT);
    }
}

