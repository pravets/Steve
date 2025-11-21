package com.steve.ai.command;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

public class SteveCommands extends CommandBase {
    @Override
    public String getCommandName() { return "steve"; }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/steve <spawn|remove|list|stop|tell> ..."; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.addChatMessage(new ChatComponentText("Usage: /steve <spawn|remove|list|stop|tell> ..."));
            return;
        }
        String sub = args[0];
        SteveManager manager = SteveMod.getSteveManager();
        if (sub.equalsIgnoreCase("spawn")) {
            if (args.length < 2) {
                sender.addChatMessage(new ChatComponentText("Usage: /steve spawn <name>"));
                return;
            }
            String name = args[1];
            World world = sender instanceof EntityPlayer ? ((EntityPlayer) sender).worldObj : MinecraftServer.getServer().getEntityWorld();
            double x = sender.getPlayerCoordinates().posX + 2;
            double y = sender.getPlayerCoordinates().posY;
            double z = sender.getPlayerCoordinates().posZ + 2;
            SteveEntity steve = manager.spawnSteve(world, x, y, z, name);
            if (steve != null) {
                sender.addChatMessage(new ChatComponentText("Spawned Steve: " + name));
            } else {
                sender.addChatMessage(new ChatComponentText("Failed to spawn (already exists or max limit)."));
            }
        } else if (sub.equalsIgnoreCase("remove")) {
            if (args.length < 2) {
                sender.addChatMessage(new ChatComponentText("Usage: /steve remove <name>"));
                return;
            }
            String name = args[1];
            if (manager.removeSteve(name)) {
                sender.addChatMessage(new ChatComponentText("Removed Steve: " + name));
            } else {
                sender.addChatMessage(new ChatComponentText("Steve not found: " + name));
            }
        } else if (sub.equalsIgnoreCase("list")) {
            String s = "Active Steves: ";
            for (String n : manager.getSteveNames()) s += n + ", ";
            sender.addChatMessage(new ChatComponentText(s));
        } else if (sub.equalsIgnoreCase("stop")) {
            if (args.length < 2) {
                sender.addChatMessage(new ChatComponentText("Usage: /steve stop <name>"));
                return;
            }
            String name = args[1];
            SteveEntity steve = manager.getSteve(name);
            if (steve != null) {
                steve.getActionExecutor().stopCurrentAction();
                steve.getMemory().clearTaskQueue();
                sender.addChatMessage(new ChatComponentText("Stopped Steve: " + name));
            } else {
                sender.addChatMessage(new ChatComponentText("Steve not found: " + name));
            }
        } else if (sub.equalsIgnoreCase("tell")) {
            if (args.length < 3) {
                sender.addChatMessage(new ChatComponentText("Usage: /steve tell <name> <command>"));
                return;
            }
            String name = args[1];
            String cmd = join(args, 2);
            SteveEntity steve = manager.getSteve(name);
            if (steve != null) {
                new Thread(() -> steve.getActionExecutor().processNaturalLanguageCommand(cmd)).start();
            } else {
                sender.addChatMessage(new ChatComponentText("Steve not found: " + name));
            }
        }
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    public static void register(FMLServerStartingEvent event) {
        event.registerServerCommand(new SteveCommands());
    }

    private String join(String[] arr, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < arr.length; i++) {
            if (i > start) sb.append(" ");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
