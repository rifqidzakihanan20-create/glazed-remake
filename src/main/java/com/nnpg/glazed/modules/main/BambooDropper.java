package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.GlazedShop;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class BambooDropper extends Module {
    private static final Item TARGET = Items.BAMBOO_BLOCK;
    
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> shopCommand = sgGeneral.add(new StringSetting.Builder()
        .name("shop-command")
        .description("Command used to open the shop menu.")
        .defaultValue("/shop")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions in ticks.")
        .defaultValue(1)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Integer> clicksPerBuy = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-buy")
        .description("How many times to click the bamboo each pass.")
        .defaultValue(8)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> reopenDelay = sgGeneral.add(new IntSetting.Builder()
        .name("reopen-delay")
        .description("Ticks to wait after sending /shop before looking for the menu.")
        .defaultValue(20)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> dropBought = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-bought")
        .description("Throw the bamboo blocks on the floor after buying them.")
        .defaultValue(true)
        .build()
    );

    private int delayCounter = 0;

    public BambooDropper() {
        super(GlazedAddon.CATEGORY, "bamboo-dropper", "Automatically buys bamboo blocks from shop and drops them.");
    }

    @Override
    public void onActivate() {
        delayCounter = 0;
    }

    @Override
    public void onDeactivate() {
        delayCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        ChestMenu handler = GlazedShop.openContainer();

        // one chest now, nothing to navigate
        if (handler == null) {
            String cmd = shopCommand.get();
            if (!cmd.startsWith("/")) cmd = "/" + cmd;
            ChatUtils.sendPlayerMsg(cmd);
            delayCounter = reopenDelay.get();
            return;
        }

        int targetSlot = GlazedShop.findItemSlot(handler, TARGET);

        if (targetSlot < 0) {
            // menu open but empty, or its not the shop
            delayCounter = Math.max(1, delay.get());
            return;
        }

        GlazedShop.spamClick(handler, targetSlot, clicksPerBuy.get());
        delayCounter = Math.max(1, delay.get());

        if (dropBought.get()) dropBamboo(handler);
    }

    private void dropBamboo(ChestMenu handler) {
        int containerSlots = GlazedShop.containerSlotCount(handler);

        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
            if (handler.getSlot(slot).getItem().isEmpty()) continue;
            if (!handler.getSlot(slot).getItem().is(TARGET)) continue;

            // button 1 = drop the whole stack
            mc.gameMode.handleInventoryMouseClick(handler.containerId, slot, 1, ClickType.THROW, mc.player);
        }
    }
}
