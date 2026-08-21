package ru.pravets.vasyan.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Server: request a Vasyan's inventory for the GUI panel.
 */
public record ServerboundRequestInventoryPacket(String vasyanName) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(vasyanName, 64);
    }

    public static ServerboundRequestInventoryPacket decode(FriendlyByteBuf buf) {
        return new ServerboundRequestInventoryPacket(buf.readUtf(64));
    }
}
