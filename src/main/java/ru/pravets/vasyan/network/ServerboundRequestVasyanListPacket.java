package ru.pravets.vasyan.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Server: request the list of active Vasyan names for the GUI panel.
 */
public record ServerboundRequestVasyanListPacket() {

    public void encode(FriendlyByteBuf buf) {
        // no payload
    }

    public static ServerboundRequestVasyanListPacket decode(FriendlyByteBuf buf) {
        return new ServerboundRequestVasyanListPacket();
    }
}
