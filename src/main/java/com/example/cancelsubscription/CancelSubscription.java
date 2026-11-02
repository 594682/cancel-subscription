package com.example.cancelsubscription;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

@Mod(value = "cancelsubscription", dist = Dist.CLIENT)
public class CancelSubscription {
    private static final KeyMapping OPEN = new KeyMapping("打开取消订阅中心", GLFW.GLFW_KEY_O, "取消订阅");
    private static boolean subscribed = false;

    public CancelSubscription(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener(this::registerKeys);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(this);
    }
    private void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        while (OPEN.consumeClick()) Minecraft.getInstance().setScreen(new SubscriptionScreen(0));
    }
    public static class SubscriptionScreen extends Screen {
        private final int stage;
        private static final String[] PROMPTS = {
            "尊贵会员中心：开通仅需一秒",
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
            "取消成功！恭喜获得自由！"
        };
        public SubscriptionScreen(int stage) {
            super(Component.literal("取消订阅比加入组织还难"));
            this.stage = stage;
        }
        @Override protected void init() {
            int cx = width / 2;
            if (!subscribed && stage == 0) {
                addRenderableWidget(Button.builder(Component.literal("一键开通会员（免费）"), b -> {
                    subscribed = true;
                    Minecraft.getInstance().setScreen(new SubscriptionScreen(0));
                }).bounds(cx-105,height/2,210,20).build());
            } else if (stage == 0) {
                addRenderableWidget(Button.builder(Component.literal("取消订阅"), b ->
                    Minecraft.getInstance().setScreen(new SubscriptionScreen(1))
                ).bounds(cx-105,height/2,210,20).build());
            } else if (stage < 11) {
                int offset = (stage % 3 - 1) * 35;
                addRenderableWidget(Button.builder(Component.literal(
                    stage == 6 ? "人工客服已为您加急（继续）" : "我仍然坚持取消（"+stage+"/10）"
                ), b -> Minecraft.getInstance().setScreen(new SubscriptionScreen(stage+1)))
                    .bounds(cx-105+offset,height/2,210,20).build());
                addRenderableWidget(Button.builder(Component.literal("算了，继续享受会员"), b ->
                    Minecraft.getInstance().setScreen(new SubscriptionScreen(0))
                ).bounds(cx-105,height/2+32,210,20).build());
            } else {
                subscribed = false;
                addRenderableWidget(Button.builder(Component.literal("终于自由了！"), b -> onClose())
                    .bounds(cx-105,height/2+20,210,20).build());
            }
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics,mouseX,mouseY,partialTick);
            graphics.drawCenteredString(font, "取消订阅比加入组织还难", width/2,height/2-66,0xFFD966);
            graphics.drawCenteredString(font, PROMPTS[stage],width/2,height/2-38,0xFFFFFF);
            graphics.drawCenteredString(font, "按 O 打开中心 · 本模组不涉及真实付款",width/2,height-35,0xAAAAAA);
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}
