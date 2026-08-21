package com.steve.ai.menu;

import com.steve.ai.SteveMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Menu type registration for Steve's inventory menu.
 */
public final class SteveMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, SteveMod.MODID);

    public static final RegistryObject<MenuType<SteveMenu>> STEVE_MENU =
        MENUS.register("steve_menu", () -> IForgeMenuType.create(SteveMenu::fromNetwork));

    private SteveMenus() {}
}
