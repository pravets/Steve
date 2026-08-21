package com.steve.ai.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Server: request a Steve's inventory for the GUI panel.
 */
public record ServerboundRequestInventoryPacket(String steveName) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(steveName, 64);
    }

    public static ServerboundRequestInventoryPacket decode(FriendlyByteBuf buf) {
        return new ServerboundRequestInventoryPacket(buf.readUtf(64));
    }
}
