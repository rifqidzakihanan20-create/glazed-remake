package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Locale;

/**
 * ShopMacroBuySell — Glazed Addon for Meteor Client
 *
 * Alur kerja:
 *   1. /shop
 *   2. Cari ikon kategori (End Stone → The End, dll.) → klik
 *   3. Cari item target di daftar shop → klik (buka sub-menu harga / jumlah)
 *   4. Klik tombol beli sebanyak mungkin (Green Glass = konfirmasi)
 *   5. Ulangi sampai inventory penuh
 *   6. Tutup GUI → /sell all <itemTarget>
 *   7. Kembali ke langkah 1
 */
public class ShopMacroBuySell extends Module {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // ─── Enum kategori shop ───────────────────────────────────────────────────
    public enum ShopCategory {
        THE_END("The End", Items.END_STONE),
        NETHER("Nether", Items.NETHERRACK),
        FARMING("Farming", Items.WHEAT),
        MINING("Mining", Items.IRON_ORE),
        OVERWORLD("Overworld", Items.GRASS_BLOCK);

        public final String label;
        public final Item iconItem;

        ShopCategory(String label, Item iconItem) {
            this.label = label;
            this.iconItem = iconItem;
        }
    }

    // ─── Enum item yang bisa dibeli ───────────────────────────────────────────
    public enum ShopItem {
        CHORUS_FRUIT("Chorus Fruit", Items.CHORUS_FRUIT, "chorus_fruit"),
        BLAZE_ROD("Blaze Rod", Items.BLAZE_ROD, "blaze_rod"),
        GHAST_TEAR("Ghast Tear", Items.GHAST_TEAR, "ghast_tear"),
        ENDER_PEARL("Ender Pearl", Items.ENDER_PEARL, "ender_pearl"),
        SUGAR_CANE("Sugar Cane", Items.SUGAR_CANE, "sugar_cane"),
        WHEAT("Wheat", Items.WHEAT, "wheat"),
        IRON_INGOT("Iron Ingot", Items.IRON_INGOT, "iron_ingot"),
        GOLD_INGOT("Gold Ingot", Items.GOLD_INGOT, "gold_ingot"),
        COAL("Coal", Items.COAL, "coal"),
        DIAMOND("Diamond", Items.DIAMOND, "diamond");

        public final String label;
        public final Item mcItem;
        public final String sellName;   // nama untuk /sell all <sellName>

        ShopItem(String label, Item mcItem, String sellName) {
            this.label = label;
            this.mcItem = mcItem;
            this.sellName = sellName;
        }
    }

    // ─── Stage state machine ──────────────────────────────────────────────────
    private enum Stage {
        NONE,
        OPEN_SHOP,          // kirim /shop
        WAIT_SHOP_GUI,      // tunggu GUI terbuka
        CLICK_CATEGORY,     // klik ikon kategori (End Stone, dll.)
        WAIT_CATEGORY_GUI,  // tunggu sub-GUI kategori
        CLICK_ITEM,         // klik item yang mau dibeli
        WAIT_ITEM_GUI,      // tunggu sub-GUI item (pilihan jumlah)
        BUY_MAX,            // klik tombol beli maksimal (atau konfirmasi hijau)
        WAIT_BUY,           // tunggu server memproses
        CHECK_FULL,         // cek apakah inventory penuh
        CLOSE_SHOP,         // tutup GUI
        WAIT_CLOSE,         // tunggu GUI tertutup
        SELL_ITEMS,         // kirim /sell all ...
        WAIT_SELL           // cooldown singkat sebelum loop ulang
    }

    private Stage stage = Stage.NONE;
    private long stageStart = 0;

    // ─── Timing (ms) ─────────────────────────────────────────────────────────
    private static final long TICK_OPEN_SHOP    = 300;
    private static final long TICK_CATEGORY     = 600;
    private static final long TICK_ITEM         = 500;
    private static final long TICK_BUY          = 300;
    private static final long TICK_CHECK        = 250;
    private static final long TICK_CLOSE        = 400;
    private static final long TICK_SELL         = 800;
    private static final long TIMEOUT_GUI       = 4000;  // batas tunggu GUI terbuka

    private int buyLoopCount = 0;
    private static final int MAX_BUY_LOOPS = 10; // maks loop beli sebelum paksa jual

    // ─── Settings ─────────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    /** Kategori shop yang akan dibuka (tentukan ikon penanda-nya) */
    private final Setting<ShopCategory> shopCategory = sgGeneral.add(
        new EnumSetting.Builder<ShopCategory>()
            .name("shop-category")
            .description("Kategori shop yang akan dibuka (ikon penanda di GUI /shop).")
            .defaultValue(ShopCategory.THE_END)
            .build()
    );

    /** Item yang ingin dibeli */
    private final Setting<ShopItem> targetItem = sgGeneral.add(
        new EnumSetting.Builder<ShopItem>()
            .name("target-item")
            .description("Item yang akan dibeli dari shop lalu dijual.")
            .defaultValue(ShopItem.CHORUS_FRUIT)
            .build()
    );

    /** Apakah langsung jual setelah beli? */
    private final Setting<Boolean> autoSell = sgGeneral.add(
        new BoolSetting.Builder()
            .name("auto-sell")
            .description("Otomatis /sell all setelah inventory penuh.")
            .defaultValue(true)
            .build()
    );

    /** Perintah sell kustom (jika null maka pakai /sell all <sellName>) */
    private final Setting<String> customSellCmd = sgAdvanced.add(
        new StringSetting.Builder()
            .name("custom-sell-command")
            .description("Perintah jual kustom. Kosongkan untuk pakai default: /sell all <item>.")
            .defaultValue("")
            .build()
    );

    private final Setting<Boolean> notifications = sgAdvanced.add(
        new BoolSetting.Builder()
            .name("notifications")
            .description("Tampilkan notifikasi di chat.")
            .defaultValue(true)
            .build()
    );

    /** Cooldown antar siklus (ms) */
    private final Setting<Integer> cycleCooldown = sgAdvanced.add(
        new IntSetting.Builder()
            .name("cycle-cooldown-ms")
            .description("Jeda (ms) setelah jual sebelum loop berikutnya.")
            .defaultValue(500)
            .min(100)
            .sliderMax(3000)
            .build()
    );

    // ─── Constructor ──────────────────────────────────────────────────────────
    public ShopMacroBuySell() {
        super(GlazedAddon.CATEGORY, "shop-macro-buy-sell",
              "Macro otomatis beli item dari shop lalu jual, berulang terus.");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        stage       = Stage.OPEN_SHOP;
        stageStart  = System.currentTimeMillis();
        buyLoopCount = 0;
        notify("✅ ShopMacroBuySell aktif! Target: §a" + targetItem.get().label
               + " §7via §e" + shopCategory.get().label);
    }

    @Override
    public void onDeactivate() {
        stage = Stage.NONE;
        notify("🛑 ShopMacroBuySell dinonaktifkan.");
    }

    // ─── Main tick handler ────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        switch (stage) {

            /* ── 1. Kirim /shop ─────────────────────────────────────────── */
            case OPEN_SHOP -> {
                ChatUtils.sendPlayerMsg("/shop");
                advance(Stage.WAIT_SHOP_GUI, now);
            }

            /* ── 2. Tunggu GUI utama /shop terbuka ──────────────────────── */
            case WAIT_SHOP_GUI -> {
                if (now - stageStart < TICK_OPEN_SHOP) return;
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    advance(Stage.CLICK_CATEGORY, now);
                } else if (timedOut(now, TIMEOUT_GUI)) {
                    notify("⚠ GUI /shop tidak terbuka, coba lagi...");
                    advance(Stage.OPEN_SHOP, now);
                }
            }

            /* ── 3. Klik ikon kategori (misal End Stone) ────────────────── */
            case CLICK_CATEGORY -> {
                if (now - stageStart < TICK_CATEGORY) return;
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    ScreenHandler handler = screen.getScreenHandler();
                    Item categoryIcon = shopCategory.get().iconItem;

                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && stack.getItem() == categoryIcon) {
                            clickSlot(handler, slot.id, 0);
                            advance(Stage.WAIT_CATEGORY_GUI, now);
                            return;
                        }
                    }
                    // Fallback: cari via nama
                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && stack.getName().getString()
                                .toLowerCase(Locale.ROOT)
                                .contains(shopCategory.get().label.toLowerCase(Locale.ROOT))) {
                            clickSlot(handler, slot.id, 0);
                            advance(Stage.WAIT_CATEGORY_GUI, now);
                            return;
                        }
                    }
                }
                if (timedOut(now, TIMEOUT_GUI)) {
                    notify("⚠ Kategori §e" + shopCategory.get().label + " §7tidak ditemukan, restart...");
                    safeClose();
                    advance(Stage.OPEN_SHOP, now);
                }
            }

            /* ── 4. Tunggu sub-GUI kategori terbuka ─────────────────────── */
            case WAIT_CATEGORY_GUI -> {
                if (now - stageStart < TICK_CATEGORY) return;
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    advance(Stage.CLICK_ITEM, now);
                } else if (timedOut(now, TIMEOUT_GUI)) {
                    notify("⚠ GUI kategori tidak terbuka, restart...");
                    advance(Stage.OPEN_SHOP, now);
                }
            }

            /* ── 5. Klik item target ─────────────────────────────────────── */
            case CLICK_ITEM -> {
                if (now - stageStart < TICK_ITEM) return;
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    ScreenHandler handler = screen.getScreenHandler();
                    Item target = targetItem.get().mcItem;

                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && stack.getItem() == target) {
                            clickSlot(handler, slot.id, 0);
                            advance(Stage.WAIT_ITEM_GUI, now);
                            return;
                        }
                    }
                    // Fallback cari via nama
                    String targetName = targetItem.get().label.toLowerCase(Locale.ROOT);
                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && stack.getName().getString()
                                .toLowerCase(Locale.ROOT).contains(targetName)) {
                            clickSlot(handler, slot.id, 0);
                            advance(Stage.WAIT_ITEM_GUI, now);
                            return;
                        }
                    }
                }
                if (timedOut(now, TIMEOUT_GUI)) {
                    notify("⚠ Item §a" + targetItem.get().label + " §7tidak ditemukan di GUI, restart...");
                    safeClose();
                    advance(Stage.OPEN_SHOP, now);
                }
            }

            /* ── 6. Tunggu sub-GUI item (pilihan jumlah/konfirmasi) ──────── */
            case WAIT_ITEM_GUI -> {
                if (now - stageStart < TICK_ITEM) return;
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    advance(Stage.BUY_MAX, now);
                } else if (timedOut(now, TIMEOUT_GUI)) {
                    notify("⚠ GUI item tidak terbuka, restart...");
                    advance(Stage.OPEN_SHOP, now);
                }
            }

            /* ── 7. Klik tombol beli maksimal ───────────────────────────── */
            case BUY_MAX -> {
                if (now - stageStart < TICK_BUY) return;
                if (mc.currentScreen instanceof GenericContainerScreen screen) {
                    ScreenHandler handler = screen.getScreenHandler();

                    // Prioritas 1: cari Green Glass (konfirmasi / beli)
                    if (clickFirstMatch(handler, Items.LIME_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE)) {
                        buyLoopCount++;
                        advance(Stage.WAIT_BUY, now);
                        return;
                    }

                    // Prioritas 2: cari slot dengan nama yang mengandung "64" atau "max" atau "bulk"
                    for (Slot slot : handler.slots) {
                        ItemStack stack = slot.getStack();
                        if (stack.isEmpty()) continue;
                        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
                        if (name.contains("64") || name.contains("max") || name.contains("bulk")
                                || name.contains("stack") || name.contains("beli")) {
                            // Klik berkali-kali untuk beli sebanyak mungkin
                            for (int i = 0; i < 8; i++) {
                                clickSlot(handler, slot.id, 0);
                            }
                            buyLoopCount++;
                            advance(Stage.WAIT_BUY, now);
                            return;
                        }
                    }
                }
                // Jika tidak ada tombol ditemukan, kembali ke klik item
                advance(Stage.CLICK_ITEM, now);
            }

            /* ── 8. Tunggu server proses pembelian ──────────────────────── */
            case WAIT_BUY -> {
                if (now - stageStart < TICK_BUY) return;
                advance(Stage.CHECK_FULL, now);
            }

            /* ── 9. Cek apakah inventory penuh ─────────────────────────── */
            case CHECK_FULL -> {
                if (now - stageStart < TICK_CHECK) return;
                boolean full = isInventoryFull();
                boolean maxLoop = buyLoopCount >= MAX_BUY_LOOPS;

                if (full || maxLoop) {
                    if (full)    notify("📦 Inventory penuh! Menjual...");
                    if (maxLoop) notify("🔁 Sudah " + MAX_BUY_LOOPS + "x beli, lanjut jual...");
                    advance(Stage.CLOSE_SHOP, now);
                } else {
                    // Lanjut beli lagi
                    advance(Stage.BUY_MAX, now);
                }
            }

            /* ── 10. Tutup GUI shop ──────────────────────────────────────── */
            case CLOSE_SHOP -> {
                safeClose();
                advance(Stage.WAIT_CLOSE, now);
            }

            /* ── 11. Tunggu GUI tertutup ────────────────────────────────── */
            case WAIT_CLOSE -> {
                if (now - stageStart < TICK_CLOSE) return;
                if (mc.currentScreen == null) {
                    if (autoSell.get()) {
                        advance(Stage.SELL_ITEMS, now);
                    } else {
                        // Tanpa auto-sell, langsung loop lagi
                        buyLoopCount = 0;
                        advance(Stage.OPEN_SHOP, now);
                    }
                } else if (timedOut(now, 3000)) {
                    safeClose();
                    stageStart = now; // reset timer
                }
            }

            /* ── 12. Jual semua item ─────────────────────────────────────── */
            case SELL_ITEMS -> {
                String cmd = buildSellCommand();
                ChatUtils.sendPlayerMsg(cmd);
                notify("💰 Menjual dengan: §e" + cmd);
                advance(Stage.WAIT_SELL, now);
            }

            /* ── 13. Cooldown sebelum loop ulang ────────────────────────── */
            case WAIT_SELL -> {
                if (now - stageStart < cycleCooldown.get()) return;
                buyLoopCount = 0;
                advance(Stage.OPEN_SHOP, now);
            }

            case NONE -> { /* idle */ }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void advance(Stage next, long now) {
        stage      = next;
        stageStart = now;
    }

    private boolean timedOut(long now, long limit) {
        return now - stageStart > limit;
    }

    private void clickSlot(ScreenHandler handler, int slotId, int button) {
        mc.interactionManager.clickSlot(handler.syncId, slotId, button, SlotActionType.PICKUP, mc.player);
    }

    /** Klik slot pertama yang itemnya cocok dengan salah satu dari daftar item. */
    private boolean clickFirstMatch(ScreenHandler handler, Item... items) {
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            for (Item item : items) {
                if (stack.getItem() == item) {
                    // Klik berkali-kali untuk memaksimalkan jumlah pembelian
                    for (int i = 0; i < 16; i++) {
                        clickSlot(handler, slot.id, 0);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private void safeClose() {
        if (mc.player != null) {
            mc.player.closeHandledScreen();
        }
    }

    private boolean isInventoryFull() {
        if (mc.player == null) return false;
        // Cek slot 9-35 (inventory utama, bukan hotbar)
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        // Cek juga hotbar (0-8)
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private String buildSellCommand() {
        String custom = customSellCmd.get().trim();
        if (!custom.isEmpty()) {
            return custom.startsWith("/") ? custom : "/" + custom;
        }
        return "/sell all " + targetItem.get().sellName;
    }

    private void notify(String msg) {
        if (notifications.get()) {
            ChatUtils.info(msg);
        }
    }
}
