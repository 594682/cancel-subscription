package com.example.cancelsubscription;

import java.util.UUID;
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
    private static final String SUB_KEY = "cancel_subscription_active";
    private static final String TOTAL_KEY = "cancel_subscription_paid";
    private static final int COST = 10;
    private static final KeyMapping OPEN = new KeyMapping(
        "打开取消订阅中心", GLFW.GLFW_KEY_O, "取消订阅");

    private static int queuedSounds = 0;
    private static long lastNoticeTick = -1000;

    public CancelSubscription(net.neoforged.bus.api.IEventBus bus) {
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

    private static boolean charge(ServerPlayer player, String reason) {
        if (!active(player) || emeraldCount(player) < COST) {
            if (active(player)) notifyPlayer(player, "余额不足！本次" + reason + "扣费失败");
            return false;
        }
        int remaining = COST;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(Items.EMERALD)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        player.getInventory().setChanged();
        player.getPersistentData().putInt(TOTAL_KEY,
            player.getPersistentData().getInt(TOTAL_KEY) + COST);
        notifyPlayer(player, reason + "：自动扣除 " + COST + " 绿宝石！");
        return true;
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
                charge(player, "方块更新服务");
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
                current.getPersistentData().putBoolean(SUB_KEY, false);
                notifyPlayer(current, "已取消订阅，不再扣费");
            }
        });
    }

    public static class SubscriptionScreen extends Screen {
        private final int stage;
        private static final String[] PROMPTS = {
            "超级尊贵会员：10绿宝石开通，之后按事件续费",
            "您确定要放弃尊贵身份吗？",
            "限时挽留：您的专属权益即将消失！",
            "温馨提示：退订可能影响您的幸福指数",
            "满意度调查：您为什么要离开我们？",
            "确认一下：您真的不是误触吗？",
            "客服正在排队处理您的申请……",
            "系统检测到您有未使用的空气权益",
            "取消前，请认真阅读三遍服务协议",
            "最后一次机会：留下来有惊喜！",
            "您已到达取消订阅的最后一步",
            "取消成功！您已获得自由！"
        };

        public SubscriptionScreen(int stage) {
            super(Component.literal("取消订阅比加入组织还难"));
            this.stage = stage;
        }

        @Override protected void init() {
            int cx = width / 2;
            ServerPlayer player = singlePlayer();
            if (player == null) {
                addRenderableWidget(Button.builder(Component.literal("仅支持单人世界（返回）"),
                    b -> onClose()).bounds(cx - 105, height / 2, 210, 20).build());
            } else if (!active(player) && stage == 0) {
                addRenderableWidget(Button.builder(Component.literal("开通会员（立即扣10绿宝石）"),
                    b -> subscribe()).bounds(cx - 105, height / 2, 210, 20).build());
            } else if (stage == 0) {
                addRenderableWidget(Button.builder(Component.literal("申请取消自动续费"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(1)))
                    .bounds(cx - 105, height / 2, 210, 20).build());
            } else if (stage <= 10) {
                addRenderableWidget(Button.builder(Component.literal("坚持取消（" + stage + "/10）"),
                    b -> {
                        if (stage == 10) {
                            unsubscribe();
                            Minecraft.getInstance().setScreen(new SubscriptionScreen(11));
                        } else {
                            Minecraft.getInstance().setScreen(new SubscriptionScreen(stage + 1));
                        }
                    }).bounds(cx - 105, height / 2, 210, 20).build());
                addRenderableWidget(Button.builder(Component.literal("算了，继续享受会员"),
                    b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(0)))
                    .bounds(cx - 105, height / 2 + 32, 210, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("终于自由了！"),
                    b -> onClose()).bounds(cx - 105, height / 2 + 20, 210, 20).build());
            }
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font, "取消订阅比加入组织还难",
                width / 2, height / 2 - 66, 0xFFD966);
            graphics.drawCenteredString(font, PROMPTS[stage],
                width / 2, height / 2 - 38, 0xFFFFFF);
            graphics.drawCenteredString(font, "O键打开 · 方块邻居更新/声音事件扣费 · 仅游戏绿宝石",
                width / 2, height - 35, 0xAAAAAA);
        }

        @Override public boolean isPauseScreen() { return false; }
    }
}
