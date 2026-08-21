package ru.pravets.vasyan.voice;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.command.VasyanCommandDispatcher;
import ru.pravets.vasyan.config.VasyanConfig;
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

    /** Minimum interval between transcriptions per player (anti-spam guard). */
    private static final long STT_MIN_INTERVAL_MS = 3000;
    private static final Map<UUID, Long> LAST_STT_AT = new ConcurrentHashMap<>();

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
        if (!VasyanConfig.VOICE_ENABLED.get()) {
            return;
        }
        if (!player.server.isSameThread()) {
            player.server.execute(() -> onChunk(player, chunk, seq, last));
            return;
        }

        // A fresh recording always starts with seq=0: reset any abandoned buffer
        // (e.g. after a lost 'last' chunk) instead of ignoring new chunks.
        if (seq == 0) {
            PENDING.remove(player.getUUID());
        }
        PendingVoice pending = PENDING.computeIfAbsent(player.getUUID(), k -> new PendingVoice());
        if (seq <= pending.lastSeq) {
            return; // duplicate/out-of-order chunk - ignore
        }

        // Server-side size guard: the client auto-stops, but a hostile/buggy
        // client could stream forever - cap the total recording size.
        int maxBytes = VasyanConfig.VOICE_MAX_RECORDING_SECONDS.get() * 32000 * 2;
        if (pending.buffer.size() + chunk.length > maxBytes) {
            PENDING.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("§cVoice: recording too long, discarded"));
            return;
        }

        pending.lastSeq = seq;
        pending.buffer.writeBytes(chunk);

        if (last) {
            PENDING.remove(player.getUUID());
            // The client already sends a complete WAV (header included) - do
            // NOT wrap the buffer in WAV again.
            byte[] wav = pending.buffer.toByteArray();
            Long lastStt = LAST_STT_AT.get(player.getUUID());
            long now = System.currentTimeMillis();
            if (lastStt != null && now - lastStt < STT_MIN_INTERVAL_MS) {
                player.sendSystemMessage(Component.literal("§7Voice: too fast, try again in a second"));
                return;
            }
            LAST_STT_AT.put(player.getUUID(), now);
            player.sendSystemMessage(Component.literal("§7Recognizing voice command..."));
            transcribeAndDispatch(player, wav);
        }
    }

    /** Call once per server tick: drop abandoned recordings. */
    public static void tick() {
        long maxAge = VasyanConfig.VOICE_MAX_RECORDING_SECONDS.get() * 1000L + 5000L;
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> now - e.getValue().startedAtMillis > maxAge);
    }

    private static void transcribeAndDispatch(ServerPlayer player, byte[] wav) {
        MultipartSttClient.transcribe(wav)
            .whenComplete((text, error) -> player.server.execute(() -> {
                if (error != null) {
                    VasyanMod.LOGGER.warn("Voice transcription failed for {}: {}", player.getName().getString(),
                        String.valueOf(error.getMessage()));
                    player.sendSystemMessage(Component.literal("§cVoice recognition failed: "
                        + String.valueOf(error.getMessage())));
                    return;
                }
                String command = text == null ? "" : text.trim();
                if (command.isEmpty()) {
                    player.sendSystemMessage(Component.literal("§7Voice: nothing recognized"));
                    return;
                }
                player.sendSystemMessage(Component.literal("§7Voice: §f" + command));
                // Single dispatch path shared with /steve tell (panel K)
                VasyanCommandDispatcher.dispatch(player.createCommandSourceStack(), command);
            }));
    }
}
