package com.steve.ai.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Server: a chunk of a recorded voice command (WAV bytes).
 * Chunks are sent in order; {@code last} marks the final chunk.
 */
public class ServerboundVoiceChunkPacket {

    public final byte[] chunk;
    public final int seq;
    public final boolean last;

    public ServerboundVoiceChunkPacket(byte[] chunk, int seq, boolean last) {
        this.chunk = chunk;
        this.seq = seq;
        this.last = last;
    }

    public static void encode(ServerboundVoiceChunkPacket packet, FriendlyByteBuf buf) {
        buf.writeByteArray(packet.chunk);
        buf.writeVarInt(packet.seq);
        buf.writeBoolean(packet.last);
    }

    public static ServerboundVoiceChunkPacket decode(FriendlyByteBuf buf) {
        return new ServerboundVoiceChunkPacket(buf.readByteArray(), buf.readVarInt(), buf.readBoolean());
    }
}
