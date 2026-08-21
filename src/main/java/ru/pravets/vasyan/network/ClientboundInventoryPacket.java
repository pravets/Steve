package ru.pravets.vasyan.network;

import ru.pravets.vasyan.entity.VasyanInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: a Vasyan's inventory contents for the GUI panel.
 */
public record ClientboundInventoryPacket(String vasyanName, List<ItemStack> stacks) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(vasyanName, 64);
        buf.writeVarInt(stacks.size());
        for (ItemStack stack : stacks) {
            buf.writeNbt(stack.save(new CompoundTag()));
        }
    }

    public static ClientboundInventoryPacket decode(FriendlyByteBuf buf) {
        String vasyanName = buf.readUtf(64);
        int size = buf.readVarInt();
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            CompoundTag tag = buf.readNbt();
            ItemStack stack = tag != null ? ItemStack.of(tag) : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return new ClientboundInventoryPacket(vasyanName, stacks);
    }

    public static ClientboundInventoryPacket empty(String vasyanName) {
        return new ClientboundInventoryPacket(vasyanName, List.of());
    }

    public static ClientboundInventoryPacket fromInventory(String vasyanName, VasyanInventory inventory) {
        // Deep-copy every stack: the packet must own independent ItemStack
        // objects, because the live inventory can be mutated (grow/shrink) in
        // the server tick while encode() runs later on the network thread
        List<ItemStack> copy = new ArrayList<>(inventory.getStacks().size());
        for (ItemStack stack : inventory.getStacks()) {
            copy.add(stack.copy());
        }
        return new ClientboundInventoryPacket(vasyanName, copy);
    }
}
