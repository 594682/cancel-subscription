package com.example.cancelsubscription.client;

import com.example.cancelsubscription.CancelSubscription;
import com.example.cancelsubscription.SubscriptionPackets;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Runs only on the physical client; safe to exclude from dedicated servers. */
@Mod(value = CancelSubscription.MOD_ID, dist = Dist.CLIENT)
public final class SubscriptionClient {
    private static final KeyMapping OPEN = new KeyMapping(
        "key.cancelsubscription.open", GLFW.GLFW_KEY_O,
        "key.categories.cancelsubscription");
    private static SubscriptionPackets.StatePayload lastState;
    private static int heardSounds = 0;
    private static int lastSoundReportTick = -10000;

    public SubscriptionClient(IEventBus bus) {
        bus.addListener(this::onKeyMappings);
        NeoForge.EVENT_BUS.register(this);
    }

    private void onKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    public static void applyState(SubscriptionPackets.StatePayload state) {
        SubscriptionPackets.StatePayload previous = lastState;
        lastState = state;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SubscriptionScreen screen) {
            boolean claimButtonsChanged = screen.page == Page.CLAIM &&
                (previous == null || previous.claimed() != state.claimed() ||
                (previous.compensationOwed() > 0L) != (state.compensationOwed() > 0L));
            boolean homeButtonsChanged = screen.page == Page.HOME &&
                (previous == null || previous.active() != state.active() ||
                 previous.refunded() != state.refunded());
            if (claimButtonsChanged || homeButtonsChanged) screen.refreshButtons();
        }
    }

    private static void action(int code) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new SubscriptionPackets.ActionPayload(code));
        }
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            lastState = null;
            heardSounds = 0;
            return;
        }
        while (OPEN.consumeClick()) {
            mc.setScreen(new SubscriptionScreen(Page.HOME, 0));
            action(SubscriptionPackets.STATUS);
        }
        if (heardSounds > 0 && lastState != null && lastState.active() &&
            !lastState.refunded() && mc.player.tickCount - lastSoundReportTick >= 20) {
            // Sound is only client-observable. The server validates and rate-limits this report.
            heardSounds = 0;
            lastSoundReportTick = mc.player.tickCount;
            action(SubscriptionPackets.SOUND);
        }
    }

    @SubscribeEvent
    public void onSound(PlaySoundEvent event) {
        if (Minecraft.getInstance().player != null && event.getSound() != null) {
            heardSounds = Math.min(8, heardSounds + 1);
        }
    }

    public enum Page { HOME, TERMS, AFTERSALES, CLAIM, REFUND, REFUND_DONE }

    /** Native-resolution drawing: no scaled textures, transformed fonts or blurry bitmaps. */
    public static final class SubscriptionScreen extends Screen {
        private final Page page;
        private final int refundStep;
        private int panelLeft, panelTop, panelWidth, panelHeight, buttonTop;

        public SubscriptionScreen(Page page, int refundStep) {
            super(Component.literal("订阅 · 999 GOLD"));
            this.page = page;
            this.refundStep = refundStep;
        }

        private void go(Page dest, int step) {
            Minecraft.getInstance().setScreen(new SubscriptionScreen(dest, step));
        }

        @Override
        protected void init() {
            panelWidth = Math.min(372, Math.max(160, width - 24));
            panelHeight = Math.min(228, Math.max(130, height - 18));
            panelLeft = (width - panelWidth) / 2;
            panelTop = (height - panelHeight) / 2;
            buttonTop = panelTop + panelHeight - 92;
            int buttonWidth = Math.min(256, panelWidth - 40);
            int bx = (width - buttonWidth) / 2;
            int by = buttonTop;

            switch (page) {
                case HOME -> {
                    if (lastState != null && lastState.active() && lastState.refunded()) {
                        addButton(bx, by, buttonWidth, "退款规则 · 会员条款", () -> go(Page.TERMS, 0));
                        addButton(bx, by + 26, buttonWidth, "售后服务", () -> go(Page.AFTERSALES, 0));
                    } else {
                        if (lastState != null && lastState.active()) {
                            addButton(bx, by, buttonWidth, "会员管理 · 申请退款", () -> go(Page.REFUND, 1));
                        } else {
                            addButton(bx, by, buttonWidth, "开通空气 PRO · 10绿宝石", () -> action(SubscriptionPackets.SUBSCRIBE));
                        }
                        addButton(bx, by + 26, buttonWidth, "条款与服务", () -> go(Page.TERMS, 0));
                    }
                }
                case TERMS -> {
                    addButton(bx, by, buttonWidth, "售后服务", () -> go(Page.AFTERSALES, 0));
                    addButton(bx, by + 26, buttonWidth, "返回会员中心", () -> go(Page.HOME, 0));
                }
                case AFTERSALES -> {
                    addButton(bx, by, buttonWidth, "维权 / 索赔中心", () -> go(Page.CLAIM, 0));
                    addButton(bx, by + 26, buttonWidth, "返回服务条款", () -> go(Page.TERMS, 0));
                }
                case CLAIM -> {
                    if (lastState != null && lastState.claimed()) {
                        if (lastState.compensationOwed() > 0L) {
                            addButton(bx, by, buttonWidth, "领取未发放赔偿（最多64颗）",
                                () -> action(SubscriptionPackets.COLLECT_COMPENSATION));
                        }
                    } else {
                        addButton(bx, by, buttonWidth, "提交维权申请（每人限一次）",
                            () -> action(SubscriptionPackets.CLAIM));
                    }
                    addButton(bx, by + 26, buttonWidth, "返回售后服务", () -> go(Page.AFTERSALES, 0));
                }
                case REFUND -> {
                    addButton(bx, by, buttonWidth, "坚持退款 · 第 " + refundStep + "/10 步", () -> {
                        if (refundStep == 10) {
                            action(SubscriptionPackets.REFUND);
                            go(Page.REFUND_DONE, 0);
                        } else go(Page.REFUND, refundStep + 1);
                    });
                    addButton(bx, by + 26, buttonWidth, "我还是保留会员", () -> go(Page.HOME, 0));
                }
                case REFUND_DONE -> addButton(bx, by + 26, buttonWidth,
                    "返回会员中心", () -> go(Page.HOME, 0));
            }
        }

        private void refreshButtons() {
            this.rebuildWidgets();
        }

        private void addButton(int x, int y, int w, String text, Runnable onClick) {
            addRenderableWidget(Button.builder(Component.literal(text), b -> onClick.run())
                .bounds(x, y, w, 20).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // Do not call renderBackground(): no post-processing blur from a vanilla screen.
            g.fill(0, 0, width, height, 0xCF0A0C12);
            int x = panelLeft, y = panelTop, r = x + panelWidth, b = y + panelHeight;
            // Clean sharp, one-pixel borders; no upscaled or stretched text sprites.
            g.fill(x - 2, y - 2, r + 2, b + 2, 0xFFF0C665);
            g.fill(x, y, r, b, 0xFF191D2C);
            g.fill(x + 4, y + 4, r - 4, y + 6, 0xFFF9E2A4);
            g.fill(x + 4, b - 6, r - 4, b - 4, 0xFF89632F);
            g.fill(x + 12, y + 30, r - 12, y + 31, 0xFF8D713B);
            g.drawCenteredString(font, "✦ 999 GOLD · 空气 PRO ✦", width / 2, y + 13, 0xFFFFD678);
            g.drawCenteredString(font, titleLine(), width / 2, y + 42, 0xFFF7DEAA);
            g.drawCenteredString(font, detailLine(), width / 2, y + 60, 0xFFD8DEEF);

            if (page == Page.HOME) {
                if (lastState == null) {
                    g.drawCenteredString(font, "正在同步服务端会员状态…", width / 2, y + 84, 0xFFD5B986);
                } else {
                    String state = lastState.active() ?
                        (lastState.refunded() ? "已退款 · 每5秒10绿宝石" : "尊贵会员 · 正常订阅中") :
                        "尚未开通会员";
                    g.drawCenteredString(font, state, width / 2, y + 84, 0xFFFFC970);
                    g.drawCenteredString(font, "累计支付 " + lastState.totalPaid() +
                        "  |  欠费 " + lastState.debt(), width / 2, y + 100, 0xFFD9D9E1);
                    if (lastState.debt() > 0) {
                        g.drawCenteredString(font, "追债倒计时 " + lastState.debtCountdownSeconds() + " 秒",
                            width / 2, y + 115, 0xFFFF8282);
                    }
                }
            } else if (page == Page.CLAIM && lastState != null && lastState.claimed()) {
                g.drawCenteredString(font, "裁定赔偿：" + lastState.claimPrize() + " 绿宝石",
                    width / 2, y + 83, 0xFFFFC870);
                g.drawCenteredString(font, "尚未领取：" + lastState.compensationOwed() + " 绿宝石",
                    width / 2, y + 100, 0xFFD9D9E1);
            } else if (page == Page.REFUND_DONE) {
                g.drawCenteredString(font, "只有空气维持费仍会继续扣除", width / 2, y + 84, 0xFFFFC970);
            }
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(font, "娱乐模组 · 游戏内绿宝石 · 无真实收费",
                width / 2, b - 18, 0xFFAB9D87);
        }

        private String titleLine() {
            return switch (page) {
                case HOME -> "至尊会员中心";
                case TERMS -> "会员条款与服务";
                case AFTERSALES -> "售后服务 · 维权入口";
                case CLAIM -> "至尊维权中心";
                case REFUND -> "挽留流程 · 第" + refundStep + "关";
                case REFUND_DONE -> "退款申请已提交";
            };
        }

        private String detailLine() {
            return switch (page) {
                case HOME -> "不存在的东西 · 尊贵地自动补货";
                case TERMS -> "请仔细阅读本不存在的会员服务条款";
                case AFTERSALES -> "维权申请与退订流程完全独立";
                case CLAIM -> lastState == null ? "正在查询服务端维权记录…" :
                    lastState.claimed() ? "维权结果已写入玩家存档" : "有1/10000概率获赔一亿绿宝石";
                case REFUND -> "申请退款不代表停止空气维持费";
                case REFUND_DONE -> "退款后仅每5秒收取10颗绿宝石";
            };
        }

        @Override public boolean isPauseScreen() { return false; }
    }
}
