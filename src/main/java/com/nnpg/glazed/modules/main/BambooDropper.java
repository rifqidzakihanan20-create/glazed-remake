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
        .defaultValue("/shops")
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

    // Rentang diperluas: min 0 (paling kenceng/brutal) sampai 20 (paling lambat/aman)
    private final Setting<Integer> dropDelay = sgGeneral.add(new IntSetting.Builder()
        .name("drop-delay")
        .description("Drop delay in ticks (0 = instant/fastest, 20 = slowest).")
        .defaultValue(2)
        .min(0)
        .max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> clicksPerBuy = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-buy")
        .description("How many times to click the buy button each pass.")
        .defaultValue(8)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> reopenDelay = sgGeneral.add(new IntSetting.Builder()
        .name("reopen-delay")
        .description("Ticks to wait after sending the shop command.")
        .defaultValue(15)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> dropBought = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-bought")
        .description("Throw the bamboo blocks on the floor automatically as a full stack.")
        .defaultValue(true)
        .build()
    );

    private int delayCounter = 0;
    private boolean waitingForMenu = false;
    private int dropCooldown = 0;

    public BambooDropper() {
        super(GlazedAddon.CATEGORY, "bamboo-dropper", "Automatically buys and drops bamboo blocks with adjustable stress test speeds.");
    }

    @Override
    public void onActivate() {
        delayCounter = 0;
        waitingForMenu = false;
        dropCooldown = 0;
    }

    @Override
    public void onDeactivate() {
        delayCounter = 0;
        waitingForMenu = false;
        dropCooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        if (dropCooldown > 0) {
            dropCooldown--;
            return;
        }

        ChestMenu handler = GlazedShop.openContainer();

        if (handler == null) {
            if (!waitingForMenu) {
                String cmd = shopCommand.get();
                if (!cmd.startsWith("/")) cmd = "/" + cmd;
                ChatUtils.sendPlayerMsg(cmd);
                waitingForMenu = true;
                delayCounter = reopenDelay.get();
            }
            return;
        }

        waitingForMenu = false;

        int buySlot = GlazedShop.findItemSlot(handler, TARGET);

        if (buySlot < 0) {
            delayCounter = Math.max(0, delay.get());
            return;
        }

        GlazedShop.spamClick(handler, buySlot, clicksPerBuy.get());
        delayCounter = Math.max(0, delay.get());

        if (dropBought.get()) {
            dropBambooStackStressTest(handler);
        }
    }

    private void dropBambooStackStressTest(ChestMenu handler) {
        int containerSlots = GlazedShop.containerSlotCount(handler);
        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
            if (handler.getSlot(slot).getItem().isEmpty()) continue;
            if (!handler.getSlot(slot).getItem().is(TARGET)) continue;
            
            mc.gameMode.handleInventoryMouseClick(handler.containerId, slot, 1, ClickType.THROW, mc.player);
            
            // Kalau diset 0, dia bakal nge-drop tanpa jeda tick (paling kenceng/brutal)
            // Kalau diset tinggi (misal 10-20), jedanya bakal kerasa lambat banget
            dropCooldown = dropDelay.get();
            break;
        }
    }
}
