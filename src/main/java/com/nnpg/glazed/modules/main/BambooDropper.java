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
        .defaultValue(0)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<Integer> clicksPerBuy = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-buy")
        .description("How many times to click the buy button each pass.")
        .defaultValue(16)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> reopenDelay = sgGeneral.add(new IntSetting.Builder()
        .name("reopen-delay")
        .description("Ticks to wait after sending the shop command.")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> dropBought = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-bought")
        .description("Throw all bamboo blocks instantly.")
        .defaultValue(true)
        .build()
    );

    private int delayCounter = 0;
    private boolean waitingForMenu = false;

    public BambooDropper() {
        super(GlazedAddon.CATEGORY, "bamboo-dropper", "Automatically buys and drops bamboo blocks instantly at maximum speed.");
    }

    @Override
    public void onActivate() {
        delayCounter = 0;
        waitingForMenu = false;
    }

    @Override
    public void onDeactivate() {
        delayCounter = 0;
        waitingForMenu = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;

        if (delayCounter > 0) {
            delayCounter--;
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
            delayCounter = delay.get();
            return;
        }

        // Spam klik beli secepat mungkin
        GlazedShop.spamClick(handler, buySlot, clicksPerBuy.get());
        delayCounter = delay.get();

        if (dropBought.get()) {
            dropAllBambooInstantly(handler);
        }
    }

    // Drop SEMUA slot bamboo sekaligus dalam satu tick (tanpa jeda/tanpa cooldown)
    private void dropAllBambooInstantly(ChestMenu handler) {
        int containerSlots = GlazedShop.containerSlotCount(handler);
        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
            if (handler.getSlot(slot).getItem().isEmpty()) continue;
            if (!handler.getSlot(slot).getItem().is(TARGET)) continue;
            
            // Eksekusi buang seluruh isi slot tanpa nunggu tick berikutnya
            mc.gameMode.handleInventoryMouseClick(handler.containerId, slot, 1, ClickType.THROW, mc.player);
        }
    }
}
