package ru.pravets.vasyan.menu;

import ru.pravets.vasyan.VasyanMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Menu type registration for Steve's inventory menu.
 */
public final class VasyanMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, VasyanMod.MODID);

    public static final RegistryObject<MenuType<VasyanMenu>> STEVE_MENU =
        MENUS.register("vasyan_menu", () -> IForgeMenuType.create(VasyanMenu::fromNetwork));

    private VasyanMenus() {}
}
