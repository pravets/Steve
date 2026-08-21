package ru.pravets.vasyan.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: the list of active Steve names for the GUI panel.
 */
public record ClientboundVasyanListPacket(List<String> steveNames) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(steveNames.size());
        for (String name : steveNames) {
            buf.writeUtf(name, 64);
        }
    }

    public static ClientboundVasyanListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> names = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            names.add(buf.readUtf(64));
        }
        return new ClientboundVasyanListPacket(names);
    }
}
