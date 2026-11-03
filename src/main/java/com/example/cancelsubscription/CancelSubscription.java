package com.example.cancelsubscription;

import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.function.Supplier;
import java.util.Random;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Comedy subscription mod. Single-player / integrated-server edition.
 * All charges use in-game emeralds, never real money.
 */
@Mod(value = "cancelsubscription", dist = Dist.CLIENT)
public class CancelSubscription {
    // Air PRO items: real inventory items, deliberately no placeable blocks.
    private static final DeferredRegister.Items AIR_ITEMS =
        DeferredRegister.createItems("cancelsubscription");

    private static class VanishingItem extends Item {
        VanishingItem(Properties properties) { super(properties); }
        @Override
        public InteractionResult useOn(UseOnContext context) {
            if (!context.getLevel().isClientSide()) {
                if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
                    context.getItemInHand().shrink(1);
                }
                if (context.getPlayer() != null) {
                    context.getPlayer().displayClientMessage(
                        Component.literal("§7[空气 PRO] 放置成功：您获得了真正的空气。"), true);
                }
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
    }

    private static DeferredItem<Item> airItem(String id) {
        return AIR_ITEMS.registerItem(id, VanishingItem::new);
    }

    private static final DeferredItem<Item> VERTICAL_SLAB = airItem("vertical_slab");
    private static final DeferredItem<Item> HORIZONTAL_LADDER = airItem("horizontal_ladder");
    private static final DeferredItem<Item> WATER_SLAB = airItem("water_slab");
    private static final DeferredItem<Item> ICE_STAIRS = airItem("ice_stairs");
    private static final DeferredItem<Item> AIR_GLASS = airItem("air_glass");
    private static final DeferredItem<Item> VOID_BLOCK = airItem("void_block");
    private static final List<Supplier<? extends Item>> AIR_PRO_POOL =
        List.of(VERTICAL_SLAB, HORIZONTAL_LADDER, WATER_SLAB, ICE_STAIRS, AIR_GLASS, VOID_BLOCK);

    private static final String AIR_PRO_NEXT_TICK = "air_pro_next_tick";
    private static final String AIR_PRO_REFUNDED = "air_pro_refunded";

    private static final String SUB_KEY = "cancel_subscription_active";
    private static final String TOTAL_KEY = "cancel_subscription_paid";
    private static final int COST = 10;
    private static final String DEBT_KEY = "subscription_debt";
    private static final String DEADLINE_KEY = "subscription_deadline";
    private static final String BANK_KEY = "subscription_bank";
    private static final String CLAIM_KEY = "subscription_claim_count";
    private static final String ORGAN_KEY = "subscription_organ_loss";
    private static final Random RNG = new Random();
    private static final KeyMapping OPEN = new KeyMapping(
        "打开订阅中心", GLFW.GLFW_KEY_O, "订阅");

    private static int queuedSounds = 0;
    private static long lastNoticeTick = -1000;
    private static long lastBlockChargeTick = -1;

    public CancelSubscription(net.neoforged.bus.api.IEventBus bus) {
        AIR_ITEMS.register(bus);
        bus.addListener(this::registerKeys);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(this);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    private static ServerPlayer singlePlayer() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return null;
        return server.getPlayerList().getPlayer(mc.player.getUUID());
    }

    private static boolean active(ServerPlayer player) {
        return player != null && player.getPersistentData().getBoolean(SUB_KEY);
    }

    private static int emeraldCount(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.EMERALD)) total += stack.getCount();
        }
        return total;
    }

    private static void takeEmeralds(ServerPlayer player, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(Items.EMERALD)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        player.getInventory().setChanged();
    }

    private static boolean charge(ServerPlayer player, String reason) {
        if (!active(player)) return false;
        if (player.getPersistentData().getBoolean(AIR_PRO_REFUNDED) && !reason.startsWith("退款后")) return false;
        int debt = player.getPersistentData().getInt(DEBT_KEY);
        if (debt > 0) return false; // No cascading charges while a debt is pending.
        if (emeraldCount(player) < COST) {
            player.getPersistentData().putInt(DEBT_KEY, COST);
            player.getPersistentData().putLong(DEADLINE_KEY, player.level().getGameTime() + 200);
            notifyPlayer(player, "欠费10绿宝石！10秒内还款，否则触发随机器官惩罚");
            return false;
        }
        takeEmeralds(player, COST);
        player.getPersistentData().putInt(TOTAL_KEY,
            player.getPersistentData().getInt(TOTAL_KEY) + COST);
        notifyPlayer(player, reason + "：自动扣除10绿宝石！");
        return true;
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (active(player)) {
            long now = player.level().getGameTime();
            long next = player.getPersistentData().getLong(AIR_PRO_NEXT_TICK);
            if (next == 0L) {
                player.getPersistentData().putLong(AIR_PRO_NEXT_TICK,
                    now + 600 + RNG.nextInt(1201));
            } else if (now >= next) {
                player.getPersistentData().putLong(AIR_PRO_NEXT_TICK,
                    now + 600 + RNG.nextInt(1201));
                int remaining = 1 + RNG.nextInt(3);
                for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                    if (player.getInventory().getItem(slot).isEmpty()) {
                        player.getInventory().setItem(slot,
                            new ItemStack(AIR_PRO_POOL.get(RNG.nextInt(AIR_PRO_POOL.size())).get()));
                        remaining--;
                    }
                }
                player.getInventory().setChanged();
                notifyPlayer(player, "空气 PRO 已自动补货：不存在的方块已发放");
            }
        }
        int debt = player.getPersistentData().getInt(DEBT_KEY);
        if (player.getPersistentData().getBoolean(AIR_PRO_REFUNDED)
            && player.tickCount % 100 == 0) {
            charge(player, "退款后空气维持服务（每5秒）");
        }
        if (debt <= 0) return;
        // Countdown uses world ticks, so it only advances while the world runs.
        if (emeraldCount(player) >= debt) {
            takeEmeralds(player, debt);
            player.getPersistentData().putInt(DEBT_KEY, 0);
            player.getPersistentData().remove(DEADLINE_KEY);
            notifyPlayer(player, "还款成功，债务清零");
            return;
        }
        if (player.level().getGameTime() >= player.getPersistentData().getLong(DEADLINE_KEY)) {
            player.getPersistentData().putInt(DEBT_KEY, 0);
            player.getPersistentData().remove(DEADLINE_KEY);
            int organ = RNG.nextInt(4);
            player.getPersistentData().putInt(ORGAN_KEY, organ + 1);
            switch (organ) {
                case 0 -> player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 60, 0));
                case 1 -> player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 60, 1));
                case 2 -> player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 20 * 60, 1));
                default -> player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 60, 1));
            }
            notifyPlayer(player, "逾期！随机器官抵押生效（负面效果持续60秒）");
        }
    }

    private static void claim() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer player = singlePlayer();
        if (server == null || player == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            // One claim per player, persisted to player data.
            if (p.getPersistentData().getInt(CLAIM_KEY) > 0) {
                notifyPlayer(p, "索赔申请已处理，每位玩家限一次");
                return;
            }
            p.getPersistentData().putInt(CLAIM_KEY, 1);
            int roll = RNG.nextInt(10000);
            long prize = roll == 0 ? 100000000L :
                roll < 500 ? 10000L : roll < 2000 ? 100L :
                roll < 5000 ? 10L : 0L;
            p.getPersistentData().putLong(BANK_KEY,
                p.getPersistentData().getLong(BANK_KEY) + prize);
            notifyPlayer(p, prize == 0 ? "索赔被驳回！" : "索赔成功！虚拟银行入账 " + prize + " 绿宝石");
        });
    }

    private static void withdraw() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer player = singlePlayer();
        if (server == null || player == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            long balance = p.getPersistentData().getLong(BANK_KEY);
            if (balance <= 0) { notifyPlayer(p, "虚拟银行没有余额"); return; }
            int room = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack stack = p.getInventory().getItem(i);
                if (stack.isEmpty()) room += 64;
                else if (stack.is(Items.EMERALD)) room += Math.max(0, 64 - stack.getCount());
            }
            int amount = (int)Math.min(Math.min(balance, 64L), room);
            if (amount <= 0) { notifyPlayer(p, "背包空间不足"); return; }
            ItemStack stack = new ItemStack(Items.EMERALD, amount);
            p.getInventory().add(stack);
            int delivered = amount - stack.getCount();
            p.getPersistentData().putLong(BANK_KEY, balance - delivered);
            notifyPlayer(p, "已提取 " + delivered + " 颗绿宝石");
        });
    }

    private static void notifyPlayer(ServerPlayer player, String message) {
        // Action bar avoids opening another screen or creating a chargeable sound.
        long now = player.level().getGameTime();
        if (now - lastNoticeTick >= 10) {
            player.displayClientMessage(Component.literal("[尊贵会员] " + message), true);
            lastNoticeTick = now;
        }
    }

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (mc.player != null) mc.setScreen(new SubscriptionScreen(0));
        }
        int count = Math.min(queuedSounds, 32);
        queuedSounds = 0;
        if (count == 0) return;
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer player = singlePlayer();
        if (server == null || player == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer current = server.getPlayerList().getPlayer(id);
            if (!active(current)) return;
            for (int i = 0; i < count; i++) {
                if (!charge(current, "听觉体验服务")) break;
            }
        });
    }

    @SubscribeEvent
    public void onSound(PlaySoundEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) return;
        if (event.getSound() == null) return;
        // A played sound event, not microphone input. Cap queue to avoid overload.
        queuedSounds = Math.min(queuedSounds + 1, 32);
    }

    @SubscribeEvent
    public void onBlockUpdate(BlockEvent.NeighborNotifyEvent event) {
        // Neighbor notifications are one kind of block update. This deliberately
        // does not claim to catch every possible block state change.
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || level.getServer() != server) return;
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(event.getPos()) <= 256) {
                if (level.getGameTime() != lastBlockChargeTick) {
                    lastBlockChargeTick = level.getGameTime();
                    charge(player, "方块更新服务");
                }
            }
        }
    }

    private static void subscribe() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer player = singlePlayer();
        if (server == null || player == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer current = server.getPlayerList().getPlayer(id);
            if (current == null || active(current)) return;
            if (emeraldCount(current) < COST) {
                notifyPlayer(current, "开通失败：至少需要 10 绿宝石");
                return;
            }
            current.getPersistentData().putBoolean(SUB_KEY, true);
            charge(current, "首期会员开通");
        });
        mc.setScreen(null);
    }

    private static void unsubscribe() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer player = singlePlayer();
        if (server == null || player == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer current = server.getPlayerList().getPlayer(id);
            if (current != null) {
                current.getPersistentData().putBoolean(AIR_PRO_REFUNDED, true);
                notifyPlayer(current, "退款申请已记录；仅保留每5秒10绿宝石的空气维持服务");
            }
        });
    }

    public static class SubscriptionScreen extends Screen {
        private final int stage;
        private static final String[] PROMPTS = {
            "999纯金会员 · 每次事件收费10绿宝石",
            "您真的要离开至尊黄金会员吗？",
            "您的专属黄金权益即将失效！",
            "今日限定：再考虑一下吧？",
            "您离开的原因我们非常重视",
            "再确认一次，真的不是误触？",
            "尊贵客服为您送来挽留祝福",
            "您尚有大量空气权益未使用",
            "请审阅尊贵会员服务条款",
            "您的黄金身份即将被封存",
            "最后一次确认退订申请",
            "退款已处理：每5秒继续收取10绿宝石"
        };
        public SubscriptionScreen(int stage) {
            super(Component.literal("订阅"));
            this.stage = stage;
        }
        @Override protected void init() {
            int cx = width / 2;
            int top = height / 2 - 3;
            ServerPlayer player = singlePlayer();
            if (player == null) {
                addRenderableWidget(Button.builder(Component.literal("仅支持单人世界 · 返回"),
                    b -> onClose()).bounds(cx - 110, top, 220, 20).build());
            } else if (stage == 13) {
                addRenderableWidget(Button.builder(Component.literal("售后服务"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(14)))
                    .bounds(cx - 110, top, 220, 20).build());
                addRenderableWidget(Button.builder(Component.literal("返回"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(0)))
                    .bounds(cx - 110, top + 28, 220, 20).build());
            } else if (stage == 14) {
                addRenderableWidget(Button.builder(Component.literal("索赔中心"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(12)))
                    .bounds(cx - 110, top, 220, 20).build());
                addRenderableWidget(Button.builder(Component.literal("返回"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(13)))
                    .bounds(cx - 110, top + 28, 220, 20).build());
            } else if (stage == 12) {
                addRenderableWidget(Button.builder(Component.literal("提交随机索赔申请"),
                    b -> { claim(); Minecraft.getInstance().setScreen(new SubscriptionScreen(0)); })
                    .bounds(cx - 110, top, 220, 20).build());
                addRenderableWidget(Button.builder(Component.literal("返回会员中心"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(0)))
                    .bounds(cx - 110, top + 30, 220, 20).build());
            } else if (stage == 0) {
                if (!active(player)) {
                    addRenderableWidget(Button.builder(Component.literal("开通黄金会员 · 10绿宝石"),
                        b -> subscribe()).bounds(cx - 110, top, 220, 20).build());
                } else {
                    addRenderableWidget(Button.builder(Component.literal("会员管理 · 申请退订"),
                        b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(1)))
                        .bounds(cx - 110, top, 220, 20).build());
                }
                addRenderableWidget(Button.builder(Component.literal("条款与服务"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(13)))
                    .bounds(cx - 110, top + 28, 220, 20).build());
            } else if (stage <= 10) {
                addRenderableWidget(Button.builder(Component.literal("坚持退订 · 第 " + stage + "/10 步"),
                    b -> {
                        if (stage == 10) {
                            unsubscribe();
                            Minecraft.getInstance().setScreen(new SubscriptionScreen(11));
                        } else Minecraft.getInstance().setScreen(new SubscriptionScreen(stage + 1));
                    }).bounds(cx - 110, top, 220, 20).build());
                addRenderableWidget(Button.builder(Component.literal("保留黄金会员身份"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(0)))
                    .bounds(cx - 110, top + 28, 220, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("返回"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(0)))
                    .bounds(cx - 110, top + 25, 220, 20).build());
            }
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, width, height, 0xF20D0B08);
            int left = Math.max(10, width / 2 - 155), right = Math.min(width - 10, width / 2 + 155);
            int top = Math.max(8, height / 2 - 94), bottom = Math.min(height - 8, height / 2 + 105);
            g.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFFB58520);
            g.fill(left, top, right, bottom, 0xFF201709);
            g.fill(left + 3, top + 3, right - 3, top + 6, 0xFFFFE39A);
            g.fill(left + 3, bottom - 6, right - 3, bottom - 3, 0xFFD1A139);
            g.drawCenteredString(font, "✦ 999 GOLD · 订阅 ✦", width / 2, top + 13, 0xFFFFD66E);
            String message = stage == 12 ? "黄金索赔 · 最高一亿绿宝石（万分之一）" : stage == 13 ? "会员条款与服务" : stage == 14 ? "售后服务中心" : PROMPTS[stage];
            g.drawCenteredString(font, message, width / 2, top + 35, 0xFFFFE8B0);
            ServerPlayer p = singlePlayer();
            if (p != null) {
                long bank = p.getPersistentData().getLong(BANK_KEY);
                int debt = p.getPersistentData().getInt(DEBT_KEY);
                g.drawCenteredString(font, "欠费: " + debt + "  |  空气 PRO 自动补货中",
                    width / 2, top + 54, 0xFFFFC96A);
                if (debt > 0) {
                    long ticks = Math.max(0, p.getPersistentData().getLong(DEADLINE_KEY) - p.level().getGameTime());
                    g.drawCenteredString(font, "追债倒计时: " + ((ticks + 19) / 20) + "秒",
                        width / 2, top + 67, 0xFFFF7777);
                }
            }
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(font, "O键开启 · 仅游戏内绿宝石 · 娱乐讽刺玩法",
                width / 2, height - 14, 0xFFCEB77B);
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}
