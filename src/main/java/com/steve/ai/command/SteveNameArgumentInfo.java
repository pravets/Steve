package com.steve.ai.command;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Registry info for {@link SteveNameArgumentType}. The type is stateless
 * (no parameters), so serialization is a no-op and both network and JSON
 * deserialization hand back the same singleton template.
 */
public class SteveNameArgumentInfo implements ArgumentTypeInfo<SteveNameArgumentType, SteveNameArgumentInfo.Template> {

    public static final SteveNameArgumentInfo INSTANCE = new SteveNameArgumentInfo();

    private static final Template SINGLETON = new Template();

    private SteveNameArgumentInfo() {
    }

    public static SteveNameArgumentInfo getInstance() {
        return INSTANCE;
    }

    @Override
    public void serializeToNetwork(Template template, FriendlyByteBuf buffer) {
    }

    @Override
    public Template deserializeFromNetwork(FriendlyByteBuf buffer) {
        return SINGLETON;
    }

    @Override
    public void serializeToJson(Template template, JsonObject json) {
    }

    @Override
    public Template unpack(SteveNameArgumentType argumentType) {
        return SINGLETON;
    }

    public static class Template implements ArgumentTypeInfo.Template<SteveNameArgumentType> {

        @Override
        public SteveNameArgumentType instantiate(CommandBuildContext context) {
            return new SteveNameArgumentType();
        }

        @Override
        public ArgumentTypeInfo<SteveNameArgumentType, ?> type() {
            return INSTANCE;
        }
    }
}