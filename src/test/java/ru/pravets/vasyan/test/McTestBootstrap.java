package ru.pravets.vasyan.test;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.DataVersion;

import java.util.Date;

/**
 * Idempotent Minecraft bootstrap for unit tests: SharedConstants.setVersion
 * throws on a second call, so all test classes share this one helper.
 */
public final class McTestBootstrap {

    private static boolean done = false;

    private McTestBootstrap() {}

    public static synchronized void bootstrap() {
        if (done) {
            return;
        }
        done = true;
        try {
            SharedConstants.setVersion(new WorldVersion() {
                @Override
                public String getName() { return "1.20.1"; }
                @Override
                public String getId() { return "1.20.1"; }
                @Override
                public DataVersion getDataVersion() { return new DataVersion(3465); }
                @Override
                public int getProtocolVersion() { return 765; }
                @Override
                public Date getBuildTime() { return new Date(0); }
                @Override
                public int getPackVersion(PackType type) { return 15; }
                @Override
                public boolean isStable() { return true; }
            });
        } catch (IllegalStateException e) {
            // Version already set by another test class - fine
        }
        Bootstrap.bootStrap();
    }
}
