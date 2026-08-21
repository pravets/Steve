package com.steve.ai.network;

import com.steve.ai.SteveMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Simple network channel for client <-> server communication
 * (e.g. the GUI panel requesting a Steve's inventory).
 */
public final class SteveNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(SteveMod.MODID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private SteveNetworking() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++,
            ServerboundRequestInventoryPacket.class,
            ServerboundRequestInventoryPacket::encode,
            ServerboundRequestInventoryPacket::decode,
            SteveNetworking::handleRequestInventory,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id++,
            ClientboundInventoryPacket.class,
            ClientboundInventoryPacket::encode,
            ClientboundInventoryPacket::decode,
            SteveNetworking::handleInventory,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(id++,
            ServerboundRequestSteveListPacket.class,
            ServerboundRequestSteveListPacket::encode,
            ServerboundRequestSteveListPacket::decode,
            SteveNetworking::handleRequestSteveList,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id++,
            ClientboundSteveListPacket.class,
            ClientboundSteveListPacket::encode,
            ClientboundSteveListPacket::decode,
            SteveNetworking::handleSteveList,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(id++,
            ServerboundVoiceChunkPacket.class,
            ServerboundVoiceChunkPacket::encode,
            ServerboundVoiceChunkPacket::decode,
            SteveNetworking::handleVoiceChunk,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void handleVoiceChunk(ServerboundVoiceChunkPacket packet,
                                         Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender != null) {
                com.steve.ai.voice.VoiceCommandHandler.onChunk(sender, packet.chunk, packet.seq, packet.last);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRequestSteveList(ServerboundRequestSteveListPacket packet,
                                               Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            ClientboundSteveListPacket response = new ClientboundSteveListPacket(
                SteveMod.getSteveManager().getSteveNames());
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), response);
        });
        ctx.setPacketHandled(true);
    }

    private static void handleSteveList(ClientboundSteveListPacket packet,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.steve.ai.client.SteveGUI.setSteveList(packet.steveNames())));
        ctx.setPacketHandled(true);
    }

    private static void handleRequestInventory(ServerboundRequestInventoryPacket packet,
                                               Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            var steve = SteveMod.getSteveManager().getSteve(packet.steveName());
            if (steve == null) {
                return;
            }
            ClientboundInventoryPacket response =
                ClientboundInventoryPacket.fromInventory(steve.getSteveName(), steve.getInventory());
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), response);
        });
        ctx.setPacketHandled(true);
    }

    private static void handleInventory(ClientboundInventoryPacket packet,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.steve.ai.client.SteveGUI.setInventoryView(packet.steveName(), packet.stacks())));
        ctx.setPacketHandled(true);
    }
}
