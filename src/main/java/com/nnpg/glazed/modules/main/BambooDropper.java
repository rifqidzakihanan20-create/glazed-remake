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

    // Fitur baru: Setting untuk mengedit command shop tujuan secara fleksibel
    private final Setting<String> shopCommand = sgGeneral.add(new StringSetting.Builder()
        .name("shop-command")
        .description("Command used to open the shop menu (e.g. /shops, /shop).")
        .defaultValue("/shops")
        .build()
    );

    // Delay diperlambat dan diset default aman agar lolos anti-cheat Kratos
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions in ticks (higher = safer).")
        .defaultValue(3)
        .min(1)
        .max(20)
        .build()
    );

    // Dibatasi kecil agar tidak terdeteksi FastClick / AutoClicker
    private final Setting<Integer> clicksPerBuy = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-buy")
        .description("How many times to click the buy button each pass.")
        .defaultValue(2)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> reopenDelay = sgGeneral.add(new IntSetting.Builder()
        .name("reopen-delay")
        .description("Ticks to wait after sending the shop command.")
        .defaultValue(20)
        .min(5)
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
        super(GlazedAddon.CATEGORY, "bamboo-dropper", "Automatically buys bamboo blocks from the shop and drops full stacks safely.");
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

        // Mencari slot item target (BAMBOO_BLOCK) langsung
        int buySlot = GlazedShop.findItemSlot(handler, TARGET);

        if (buySlot < 0) {
            delayCounter = Math.max(2, delay.get());
            return;
        }

        // Klik tombol beli dengan aman (terhindar dari deteksi auto-clicker Kratos)
        GlazedShop.spamClick(handler, buySlot, clicksPerBuy.get());
        delayCounter = Math.max(3, delay.get());

        if (dropBought.get()) {
            dropBambooStackSafely(handler);
        }
    }

    // Fungsi drop 1 stack penuh secara bertahap agar aman dari anti-cheat
    private void dropBambooStackSafely(ChestMenu handler) {
        int containerSlots = GlazedShop.containerSlotCount(handler);
        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
            if (handler.getSlot(slot).getItem().isEmpty()) continue;
            if (!handler.getSlot(slot).getItem().is(TARGET)) continue;
            
            // Parameter button = 1 dengan ClickType.THROW melemparkan 1 stack penuh dari slot tersebut
            mc.gameMode.handleInventoryMouseClick(handler.containerId, slot, 1, ClickType.THROW, mc.player);
            dropCooldown = 3; // Jeda 3 tick antar drop stack agar terlihat natural di mata Kratos
            break;
        }
    }
}
