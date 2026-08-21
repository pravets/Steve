package com.steve.ai.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: the list of active Steve names for the GUI panel.
 */
public record ClientboundSteveListPacket(List<String> steveNames) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(steveNames.size());
        for (String name : steveNames) {
            buf.writeUtf(name, 64);
        }
    }

    public static ClientboundSteveListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> names = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            names.add(buf.readUtf(64));
        }
        return new ClientboundSteveListPacket(names);
    }
}
