package com.example.cancelsubscription;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Shared logic. Do not import net.minecraft.client here: this class loads on
 * physical dedicated servers as well as integrated servers.
 * Everything called "money" here means Minecraft EMERALDS, never real funds.
 */
@Mod(CancelSubscription.MOD_ID)
public final class CancelSubscription {
    public static final String MOD_ID = "cancelsubscription";
    private static final int FEE = 10;

    private static final String ACTIVE = "cancel_subscription_active";
    private static final String REFUNDED = "air_pro_refunded";
    private static final String TOTAL = "cancel_subscription_paid";
    private static final String DEBT = "subscription_debt";
    private static final String DEADLINE = "subscription_deadline";
    private static final String RESTOCK_TICK = "air_pro_next_tick";
    private static final String CLAIM_COUNT = "subscription_claim_count";
    private static final String CLAIM_PRIZE = "subscription_claim_prize";
    private static final String CLAIM_OWED = "subscription_claim_owed";
    private static final String OLD_BANK = "subscription_bank";
    private static final String ORGAN = "subscription_organ_loss";
    private static final String LAST_BLOCK = "subscription_last_block_charge";
    private static final String LAST_SOUND = "subscription_last_sound_charge";
    private static final String LAST_NOTICE = "subscription_last_notice";

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    /** Inventory item only; intentionally never registers a placeable block. */
    private static final class VanishingItem extends Item {
        private VanishingItem(Properties properties) { super(properties); }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            if (!context.getLevel().isClientSide()) {
                if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
                    context.getItemInHand().shrink(1);
                }
                if (context.getPlayer() != null) {
                    context.getPlayer().displayClientMessage(
                        Component.literal("[空气 PRO] 放置成功：世界里什么都没有。"), true);
                }
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
    }

    private static DeferredItem<Item> airItem(String name) {
        return ITEMS.registerItem(name, VanishingItem::new);
    }

    private static final DeferredItem<Item> VERTICAL_SLAB = airItem("vertical_slab");
    private static final DeferredItem<Item> HORIZONTAL_LADDER = airItem("horizontal_ladder");
    private static final DeferredItem<Item> WATER_SLAB = airItem("water_slab");
    private static final DeferredItem<Item> ICE_STAIRS = airItem("ice_stairs");
    private static final DeferredItem<Item> AIR_GLASS = airItem("air_glass");
    private static final DeferredItem<Item> VOID_BLOCK = airItem("void_block");
    private static final List<Supplier<? extends Item>> AIR_POOL = List.of(
        VERTICAL_SLAB, HORIZONTAL_LADDER, WATER_SLAB, ICE_STAIRS, AIR_GLASS, VOID_BLOCK
    );

    public CancelSubscription(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(SubscriptionPackets::register);
        NeoForge.EVENT_BUS.register(this);
    }

    private static boolean active(ServerPlayer player) {
        return player.getPersistentData().getBoolean(ACTIVE);
    }

    private static boolean refunded(ServerPlayer player) {
        return player.getPersistentData().getBoolean(REFUNDED);
    }

    private static int emeraldCount(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item.is(Items.EMERALD)) count += item.getCount();
        }
        return count;
    }

    private static void takeEmeralds(ServerPlayer player, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!item.is(Items.EMERALD)) continue;
            int taken = Math.min(item.getCount(), remaining);
            item.shrink(taken);
            remaining -= taken;
        }
        player.getInventory().setChanged();
    }

    private static void notice(ServerPlayer player, String text) {
        CompoundTag tag = player.getPersistentData();
        long tick = player.level().getGameTime();
        if (!tag.contains(LAST_NOTICE) || tick - tag.getLong(LAST_NOTICE) >= 10) {
            player.displayClientMessage(Component.literal("[尊贵会员] " + text), true);
            tag.putLong(LAST_NOTICE, tick);
        }
    }

    /** The authoritative server makes the charge, not the GUI or local client. */
    private static void charge(ServerPlayer player, String reason) {
        if (!active(player)) return;
        CompoundTag tag = player.getPersistentData();
        if (emeraldCount(player) >= FEE) {
            takeEmeralds(player, FEE);
            tag.putInt(TOTAL, tag.getInt(TOTAL) + FEE);
            notice(player, reason + "：已扣除10绿宝石");
        } else {
            tag.putInt(DEBT, Math.min(1_000_000, tag.getInt(DEBT) + FEE));
            if (!tag.contains(DEADLINE)) {
                tag.putLong(DEADLINE, player.level().getGameTime() + 200L);
            }
            notice(player, "绿宝石不足！欠费累积，每次收费10绿宝石");
        }
    }

    private static void collectDebt(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        int debt = tag.getInt(DEBT);
        if (debt <= 0) return;
        if (emeraldCount(player) >= debt) {
            takeEmeralds(player, debt);
            tag.putInt(TOTAL, tag.getInt(TOTAL) + debt);
            tag.putInt(DEBT, 0);
            tag.remove(DEADLINE);
            notice(player, "欠款已结清：" + debt + "绿宝石");
        } else if (player.level().getGameTime() >= tag.getLong(DEADLINE)) {
            // Comedy only: temporary vanilla effects, no real body/health damage.
            int roll = ThreadLocalRandom.current().nextInt(4);
            tag.putInt(ORGAN, roll + 1);
            switch (roll) {
                case 0 -> player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 60));
                case 1 -> player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 60, 1));
                case 2 -> player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 20 * 60, 1));
                default -> player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 60, 1));
            }
            tag.putLong(DEADLINE, player.level().getGameTime() + 200L);
            notice(player, "逾期：触发60秒随机负面效果，欠费仍保留");
        }
    }

    private static void airProRestock(ServerPlayer player) {
        if (!active(player) || refunded(player)) return;
        CompoundTag tag = player.getPersistentData();
        long now = player.level().getGameTime();
        long due = tag.getLong(RESTOCK_TICK);
        if (due == 0L) {
            tag.putLong(RESTOCK_TICK, now + 600L + ThreadLocalRandom.current().nextInt(1201));
        } else if (now >= due) {
            tag.putLong(RESTOCK_TICK, now + 600L + ThreadLocalRandom.current().nextInt(1201));
            int remaining = 1 + ThreadLocalRandom.current().nextInt(3);
            int given = 0;
            for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                if (player.getInventory().getItem(slot).isEmpty()) {
                    Item item = AIR_POOL.get(ThreadLocalRandom.current().nextInt(AIR_POOL.size())).get();
                    player.getInventory().setItem(slot, new ItemStack(item));
                    remaining--;
                    given++;
                }
            }
            if (given > 0) {
                player.getInventory().setChanged();
                notice(player, "空气 PRO 已自动补货：" + given + " 个不存在的物品");
            }
        }
    }

    @SubscribeEvent
    public void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        airProRestock(player);
        if (active(player) && refunded(player) && player.tickCount % 100 == 0) {
            charge(player, "退款后空气维持费（每5秒）");
        }
        if (player.tickCount % 10 == 0) collectDebt(player);
        if (player.tickCount % 20 == 0) sendState(player);
    }

    /** All actions are validated and executed on the server's main thread. */
    static void handleAction(ServerPlayer player, int action) {
        CompoundTag tag = player.getPersistentData();
        switch (action) {
            case SubscriptionPackets.STATUS -> { /* request a fresh state */ }
            case SubscriptionPackets.SUBSCRIBE -> {
                if (!active(player)) {
                    if (emeraldCount(player) < FEE) {
                        notice(player, "开通失败：至少需要10个绿宝石");
                    } else {
                        tag.putBoolean(ACTIVE, true);
                        tag.putBoolean(REFUNDED, false);
                        tag.remove(RESTOCK_TICK);
                        charge(player, "首期订阅");
                    }
                }
            }
            case SubscriptionPackets.REFUND -> {
                if (active(player) && !refunded(player)) {
                    tag.putBoolean(REFUNDED, true);
                    tag.remove(RESTOCK_TICK);
                    notice(player, "退款流程结束：其他收费和补货停止，仅剩每5秒10绿宝石");
                }
            }
            case SubscriptionPackets.CLAIM -> {
                if (!active(player)) {
                    notice(player, "请先开通会员，再进入维权中心");
                } else if (tag.getInt(CLAIM_COUNT) > 0) {
                    notice(player, "维权申请已处理，每位玩家仅有一次机会");
                } else {
                    tag.putInt(CLAIM_COUNT, 1);
                    int roll = ThreadLocalRandom.current().nextInt(10_000);
                    long prize = roll == 0 ? 100_000_000L :
                        roll < 500 ? 10_000L : roll < 2_000 ? 100L :
                        roll < 5_000 ? 10L : 0L;
                    tag.putLong(CLAIM_PRIZE, prize);
                    tag.putLong(CLAIM_OWED, tag.getLong(CLAIM_OWED) + prize);
                    notice(player, prize == 0 ? "维权结果：申请驳回" : "维权成功！赔偿" + prize + "绿宝石");
                    // Important: No GUI navigation in the claim action.
                }
            }
            case SubscriptionPackets.COLLECT_COMPENSATION -> payout(player);
            case SubscriptionPackets.SOUND -> {
                long now = player.level().getGameTime();
                if (active(player) && !refunded(player) &&
                    (!tag.contains(LAST_SOUND) || now - tag.getLong(LAST_SOUND) >= 20)) {
                    tag.putLong(LAST_SOUND, now);
                    charge(player, "听觉体验服务");
                }
            }
            default -> { /* ignore unknown/untrusted actions */ }
        }
        sendState(player);
    }

    private static void payout(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        long owed = tag.getLong(CLAIM_OWED);
        if (owed <= 0 || tag.getInt(CLAIM_COUNT) <= 0) return;
        int amount = (int) Math.min(64L, owed);
        ItemStack stack = new ItemStack(Items.EMERALD, amount);
        player.getInventory().add(stack);
        int delivered = amount - stack.getCount();
        if (delivered > 0) {
            tag.putLong(CLAIM_OWED, owed - delivered);
            notice(player, "维权赔偿已领取：" + delivered + "颗绿宝石");
        } else {
            notice(player, "背包没有空间领取赔偿绿宝石");
        }
    }

    private static void migrateLegacyBank(ServerPlayer player) {
        // Preserve compensation earned by earlier builds without restoring the bank UI.
        CompoundTag tag = player.getPersistentData();
        if (tag.getLong(OLD_BANK) > 0L) {
            tag.putLong(CLAIM_OWED, tag.getLong(CLAIM_OWED) + tag.getLong(OLD_BANK));
            tag.remove(OLD_BANK);
            if (tag.getInt(CLAIM_COUNT) == 0) tag.putInt(CLAIM_COUNT, 1);
        }
    }

    private static void sendState(ServerPlayer player) {
        migrateLegacyBank(player);
        CompoundTag tag = player.getPersistentData();
        long remaining = tag.contains(DEADLINE) ?
            Math.max(0L, tag.getLong(DEADLINE) - player.level().getGameTime()) : 0L;
        PacketDistributor.sendToPlayer(player, new SubscriptionPackets.StatePayload(
            active(player), refunded(player), tag.getInt(DEBT),
            tag.getInt(TOTAL), tag.getInt(CLAIM_COUNT) != 0,
            tag.getLong(CLAIM_PRIZE), tag.getLong(CLAIM_OWED),
            (int) ((remaining + 19L) / 20L)));
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendState(player);
    }

    @SubscribeEvent
    public void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendState(player);
    }

    @SubscribeEvent
    public void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendState(player);
    }

    @SubscribeEvent
    public void clonePlayer(PlayerEvent.Clone event) {
        // Vanilla clones players on death; copy our own persistent NBT keys explicitly.
        CompoundTag src = event.getOriginal().getPersistentData();
        CompoundTag dst = event.getEntity().getPersistentData();
        for (String key : List.of(ACTIVE, REFUNDED, TOTAL, DEBT, DEADLINE, RESTOCK_TICK,
                CLAIM_COUNT, CLAIM_PRIZE, CLAIM_OWED, OLD_BANK, ORGAN)) {
            Tag value = src.get(key);
            if (value != null) dst.put(key, value.copy());
        }
    }

    @SubscribeEvent
    public void blockUpdate(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long tick = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            if (!active(player) || refunded(player)) continue;
            if (player.blockPosition().distSqr(event.getPos()) > 256) continue;
            CompoundTag tag = player.getPersistentData();
            if (tag.contains(LAST_BLOCK) && tag.getLong(LAST_BLOCK) == tick) continue;
            tag.putLong(LAST_BLOCK, tick);
            charge(player, "方块更新服务");
        }
    }
}
