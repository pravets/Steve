package ru.pravets.vasyan.client;

import ru.pravets.vasyan.VasyanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles client-side events, including disabling the narrator and checking key presses
 */
@Mod.EventBusSubscriber(modid = "steve", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEventHandler {
    
    private static boolean narratorDisabled = false;
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        
        if (!narratorDisabled && mc.options != null) {
            mc.options.narrator().set(NarratorStatus.OFF);
            mc.options.save();
            narratorDisabled = true;
        }
        
        if (KeyBindings.TOGGLE_GUI != null && KeyBindings.TOGGLE_GUI.consumeClick()) {            VasyanGUI.toggle();
        }

        // Voice push-to-talk (V): toggle recording, then auto-stop on timeout
        if (KeyBindings.VOICE_PTT != null && KeyBindings.VOICE_PTT.consumeClick()) {
            if (!ru.pravets.vasyan.config.VasyanConfig.VOICE_ENABLED.get()) {
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                        "§cVoice commands disabled - enable [voice] enabled=true and set sttApiKey in config"), false);
            } else if (!VoiceRecorder.toggle() && mc.player != null) {
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§cVoice: microphone unavailable"), false);
            }
        }
        VoiceRecorder.checkAutoStop();
    }
}
