package ru.pravets.vasyan.command;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Registry info for {@link VasyanNameArgumentType}. The type is stateless
 * (no parameters), so serialization is a no-op and both network and JSON
 * deserialization hand back the same singleton template.
 */
public final class VasyanNameArgumentInfo implements ArgumentTypeInfo<VasyanNameArgumentType, VasyanNameArgumentInfo.Template> {

    public static final VasyanNameArgumentInfo INSTANCE = new VasyanNameArgumentInfo();

    private static final Template SINGLETON = new Template();

    private VasyanNameArgumentInfo() {
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
    public Template unpack(VasyanNameArgumentType argumentType) {
        return SINGLETON;
    }

    public static class Template implements ArgumentTypeInfo.Template<VasyanNameArgumentType> {

        @Override
        public VasyanNameArgumentType instantiate(CommandBuildContext context) {
            return new VasyanNameArgumentType();
        }

        @Override
        public VasyanNameArgumentInfo type() {
            return INSTANCE;
        }
    }
}