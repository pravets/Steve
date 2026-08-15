package com.steve.ai.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Server: request the list of active Steve names for the GUI panel.
 */
public record ServerboundRequestSteveListPacket() {

    public void encode(FriendlyByteBuf buf) {
        // no payload
    }

    public static ServerboundRequestSteveListPacket decode(FriendlyByteBuf buf) {
        return new ServerboundRequestSteveListPacket();
    }
}
