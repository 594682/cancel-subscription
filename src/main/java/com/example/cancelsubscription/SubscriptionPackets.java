package com.example.cancelsubscription;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Network packets shared by logical server and client. All mutations on the server. */
public final class SubscriptionPackets {
    public static final int STATUS = 0;
    public static final int SUBSCRIBE = 1;
    public static final int REFUND = 2;
    public static final int CLAIM = 3;
    public static final int COLLECT_COMPENSATION = 4;
    public static final int SOUND = 5;

    private SubscriptionPackets() {}

    public record ActionPayload(int action) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CancelSubscription.MOD_ID, "action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> buf.writeVarInt(payload.action),
                buf -> new ActionPayload(buf.readVarInt()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StatePayload(boolean active, boolean refunded, int debt, int totalPaid,
            boolean claimed, long claimPrize, long compensationOwed, int debtCountdownSeconds)
            implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CancelSubscription.MOD_ID, "state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeBoolean(p.active);
                buf.writeBoolean(p.refunded);
                buf.writeVarInt(p.debt);
                buf.writeVarInt(p.totalPaid);
                buf.writeBoolean(p.claimed);
                buf.writeVarLong(p.claimPrize);
                buf.writeVarLong(p.compensationOwed);
                buf.writeVarInt(p.debtCountdownSeconds);
            }, buf -> new StatePayload(buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                buf.readVarLong(), buf.readVarLong(), buf.readVarInt()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, (packet, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                CancelSubscription.handleAction(player, packet.action());
            }
        });
        // The physical server never touches the client-only handler class.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientHandler(registrar);
        } else {
            registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC,
                (packet, ctx) -> { /* dedicated server does not receive S2C packets */ });
        }
    }

    private static void registerClientHandler(PayloadRegistrar registrar) {
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC,
            (packet, ctx) -> com.example.cancelsubscription.client.SubscriptionClient.applyState(packet));
    }
}
