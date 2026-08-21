package ru.pravets.vasyan.testutil;

import ru.pravets.vasyan.config.VasyanConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.DataVersion;
import org.junit.jupiter.api.BeforeAll;

import java.util.Date;

/**
 * Base class for tests that need the Minecraft registries. Bootstraps the
 * game with a single {@link WorldVersion} (1.20.1, protocol 763) and calls
 * {@link Bootstrap#bootStrap()} exactly once per test class. Subclasses do
 * not need to duplicate the setup; the bootstrapping is idempotent in case
 * several test classes run in the same JVM.
 */
public abstract class AbstractMinecraftTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        try {
            SharedConstants.setVersion(new WorldVersion() {
                @Override
                public String getName() { return "1.20.1"; }

                @Override
                public String getId() { return "1.20.1"; }

                @Override
                public DataVersion getDataVersion() { return new DataVersion(3465); }

                @Override
                public int getProtocolVersion() { return 763; }

                @Override
                public int getPackVersion(PackType type) { return 15; }

                @Override
                public Date getBuildTime() { return new Date(0); }

                @Override
                public boolean isStable() { return true; }
            });
        } catch (IllegalStateException e) {
            // Version already set by another test class in the same JVM.
        }
        try {
            Bootstrap.bootStrap();
        } catch (IllegalStateException e) {
            // Already bootstrapped by another test class in the same JVM.
        }
        try {
            CommentedConfig config = CommentedConfig.inMemory();
            VasyanConfig.SPEC.acceptConfig(config);
        } catch (IllegalStateException e) {
            // Configuration already accepted by another test class in the same JVM.
        }
    }
}
