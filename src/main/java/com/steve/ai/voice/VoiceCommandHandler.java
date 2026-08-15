package com.steve.ai.voice;

import com.steve.ai.SteveMod;
import com.steve.ai.command.CommandDispatcher;
import com.steve.ai.config.SteveConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side voice command pipeline: collects WAV chunks per player,
 * transcribes the assembled audio via an OpenAI-compatible STT endpoint,
 * and dispatches the resulting text as a normal chat command.
 */
@Mod.EventBusSubscriber(modid = "steve", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VoiceCommandHandler {

    /** In-progress recording per player. */
    private static final Map<UUID, PendingVoice> PENDING = new ConcurrentHashMap<>();

    private VoiceCommandHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tick();
        }
    }

    private static final class PendingVoice {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final long startedAtMillis = System.currentTimeMillis();
        int lastSeq = -1;
    }

    public static void onChunk(ServerPlayer player, byte[] chunk, int seq, boolean last) {
        if (!SteveConfig.VOICE_ENABLED.get()) {
            return;
        }
        if (!player.server.isSameThread()) {
            player.server.execute(() -> onChunk(player, chunk, seq, last));
            return;
        }

        PendingVoice pending = PENDING.computeIfAbsent(player.getUUID(), k -> new PendingVoice());
        if (seq <= pending.lastSeq) {
            return; // duplicate/out-of-order chunk - ignore
        }
        pending.lastSeq = seq;
        pending.buffer.writeBytes(chunk);

        if (last) {
            PENDING.remove(player.getUUID());
            byte[] wav = WavBuilder.buildWav(pending.buffer.toByteArray());
            player.sendSystemMessage(Component.literal("§7Recognizing voice command..."));
            transcribeAndDispatch(player, wav);
        }
    }

    /** Call once per server tick: drop abandoned recordings. */
    public static void tick() {
        long maxAge = SteveConfig.VOICE_MAX_RECORDING_SECONDS.get() * 1000L + 5000L;
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> now - e.getValue().startedAtMillis > maxAge);
    }

    private static void transcribeAndDispatch(ServerPlayer player, byte[] wav) {
        MultipartSttClient.transcribe(wav)
            .whenComplete((text, error) -> player.server.execute(() -> {
                if (error != null) {
                    SteveMod.LOGGER.warn("Voice transcription failed for {}: {}", player.getName().getString(), error.toString());
                    player.sendSystemMessage(Component.literal("§cVoice recognition failed: " + error.getMessage()));
                    return;
                }
                String command = text == null ? "" : text.trim();
                if (command.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§7Voice: nothing recognized"));
                    return;
                }
                player.sendSystemMessage(Component.literal("§7Voice: §f" + command));
                CommandDispatcher.dispatch(player, command);
            }));
    }
}
