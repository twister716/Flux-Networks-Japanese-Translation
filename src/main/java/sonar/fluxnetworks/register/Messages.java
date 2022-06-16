package sonar.fluxnetworks.register;

import icyllis.modernui.forge.NetworkHandler;
import icyllis.modernui.forge.PacketDispatcher;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.player.Player;
import sonar.fluxnetworks.FluxNetworks;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.api.device.IFluxDevice;
import sonar.fluxnetworks.api.network.SecurityLevel;
import sonar.fluxnetworks.common.capability.FluxPlayer;
import sonar.fluxnetworks.common.connection.*;
import sonar.fluxnetworks.common.device.TileFluxDevice;
import sonar.fluxnetworks.common.item.ItemAdminConfigurator;
import sonar.fluxnetworks.common.util.FluxUtils;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.function.Supplier;

import static sonar.fluxnetworks.register.Registration.sNetwork;

/**
 * Network messages, TCP protocol. This class contains common messages, S2C message specs
 * and C2S message handling.
 * <p>
 * Security check is necessary on the server side. However, due to network latency,
 * players cannot be kicked out unless the packet is <em>seriously illegal</em>.
 * <p>
 * Some terms:
 * <ul>
 *   <li><b>Protocol</b>: controls message codec and handling, if some of them changed, then protocol
 *   needs to update.</li>
 *   <li><b>Index</b>: identifies message body, unsigned short, 0-based indexing, must be sequential
 *   for table lookup (switch statement), server-to-client and client-to-server are independent.</li>
 *   <li><b>Token</b>: the container id, unsigned byte, generated by Minecraft, ranged from 1 to 100.
 *   Container menu is required for C/S communication, if the initiator (the server side) is destroyed,
 *   then the token is expired.</li>
 *   <li><b>Result</b>: the return code sent by the server to the client to respond to a client request.
 *   There's a key used to identify the request, generally, it is the same as the message index.
 *   That is, blocking, client is waiting for the server.</li>
 * </ul>
 *
 * @author BloCamLimb
 * @since 7.0
 */
@ParametersAreNonnullByDefault
public class Messages {

    /**
     * Note: Increment this if any packet is changed.
     */
    static final String PROTOCOL = "701";

    /**
     * C->S message indices, must be sequential, 0-based indexing
     */
    static final int C2S_DEVICE_BUFFER = 0;
    static final int C2S_SUPER_ADMIN = 1;
    static final int C2S_CREATE_NETWORK = 2;
    static final int C2S_DELETE_NETWORK = 3;
    static final int C2S_EDIT_DEVICE = 4;
    static final int C2S_CONNECT_DEVICE = 5;
    static final int C2S_EDIT_CONFIGURATOR = 6;
    static final int C2S_CONNECT_CONFIGURATOR = 7;
    static final int C2S_EDIT_MEMBER = 8;
    static final int C2S_EDIT_NETWORK = 9;
    static final int C2S_EDIT_CONNECTIONS = 10;
    static final int C2S_UPDATE_NETWORK = 11;
    static final int C2S_TRACK_MEMBERS = 12;
    static final int C2S_TRACK_CONNECTIONS = 13;
    static final int C2S_TRACK_STATISTICS = 14;

    /**
     * S->C message indices, must be sequential, 0-based indexing
     */
    static final int S2C_DEVICE_BUFFER = 0;
    static final int S2C_RESPONSE = 1;
    static final int S2C_SUPER_ADMIN = 2;
    static final int S2C_UPDATE_NETWORK = 3;
    static final int S2C_DELETE_NETWORK = 4;
    static final int S2C_UPDATE_MEMBERS = 5;
    static final int S2C_UPDATE_CONNECTIONS = 6;

    /**
     * Byte stream.
     *
     * @param device the block entity created by server
     * @param type   for example, {@link FluxConstants#DEVICE_S2C_GUI_SYNC}
     * @return dispatcher
     */
    @Nonnull
    public static PacketDispatcher deviceBuffer(TileFluxDevice device, byte type) {
        assert type < 0; // S2C negative
        var buf = NetworkHandler.buffer(S2C_DEVICE_BUFFER);
        buf.writeBlockPos(device.getBlockPos());
        buf.writeByte(type);
        device.writePacket(buf, type);
        return sNetwork.dispatch(buf);
    }

    /**
     * Response to client.
     *
     * @param token the container id
     * @param key   the request key
     * @param code  the response code
     */
    private static void response(int token, int key, int code, Player player) {
        var buf = NetworkHandler.buffer(S2C_RESPONSE);
        buf.writeByte(token);
        buf.writeShort(key);
        buf.writeByte(code);
        sNetwork.sendToPlayer(buf, player);
    }

    /**
     * Update player's super admin.
     */
    public static void superAdmin(boolean enable, Player player) {
        var buf = NetworkHandler.buffer(S2C_SUPER_ADMIN);
        buf.writeBoolean(enable);
        sNetwork.sendToPlayer(buf, player);
    }

    /**
     * Variation of {@link #updateNetwork(Collection, byte)} that updates only one network.
     */
    @Nonnull
    public static PacketDispatcher updateNetwork(FluxNetwork network, byte type) {
        var buf = NetworkHandler.buffer(S2C_UPDATE_NETWORK);
        buf.writeByte(type);
        buf.writeVarInt(1); // size
        buf.writeVarInt(network.getNetworkID());
        final var tag = new CompoundTag();
        network.writeCustomTag(tag, type);
        buf.writeNbt(tag);
        return sNetwork.dispatch(buf);
    }

    @Nonnull
    public static PacketDispatcher updateNetwork(Collection<FluxNetwork> networks, byte type) {
        var buf = NetworkHandler.buffer(S2C_UPDATE_NETWORK);
        buf.writeByte(type);
        buf.writeVarInt(networks.size());
        for (var network : networks) {
            buf.writeVarInt(network.getNetworkID());
            final var tag = new CompoundTag();
            network.writeCustomTag(tag, type);
            buf.writeNbt(tag);
        }
        return sNetwork.dispatch(buf);
    }

    @Nonnull
    private static PacketDispatcher updateNetwork(int[] networkIDs, byte type) {
        var buf = NetworkHandler.buffer(S2C_UPDATE_NETWORK);
        buf.writeByte(type);
        buf.writeVarInt(networkIDs.length);
        for (var networkID : networkIDs) {
            buf.writeVarInt(networkID);
            final var tag = new CompoundTag();
            FluxNetworkData.getNetwork(networkID).writeCustomTag(tag, type);
            buf.writeNbt(tag);
        }
        return sNetwork.dispatch(buf);
    }

    /**
     * Notify all clients that a network was deleted.
     */
    public static void deleteNetwork(int id) {
        var buf = NetworkHandler.buffer(S2C_DELETE_NETWORK);
        buf.writeVarInt(id);
        sNetwork.sendToAll(buf);
    }

    @Nonnull
    static NetworkHandler.ClientListener msg() {
        return ClientMessages::msg;
    }

    static void msg(short index, FriendlyByteBuf payload, Supplier<ServerPlayer> player) {
        MinecraftServer server = player.get().getLevel().getServer();
        switch (index) {
            case C2S_DEVICE_BUFFER -> onDeviceBuffer(payload, player, server);
            case C2S_SUPER_ADMIN -> onSuperAdmin(payload, player, server);
            case C2S_EDIT_DEVICE -> onEditDevice(payload, player, server);
            case C2S_CREATE_NETWORK -> onCreateNetwork(payload, player, server);
            case C2S_DELETE_NETWORK -> onDeleteNetwork(payload, player, server);
            case C2S_EDIT_NETWORK -> onEditNetwork(payload, player, server);
            case C2S_CONNECT_DEVICE -> onSetTileNetwork(payload, player, server);
            case C2S_UPDATE_NETWORK -> onUpdateNetwork(payload, player, server);
            case C2S_EDIT_MEMBER -> onEditMember(payload, player, server);
            default -> kick(player.get(), new RuntimeException("Unidentified message index " + index));
        }
    }

    private static void kick(ServerPlayer p, RuntimeException e) {
        if (p.server.isDedicatedServer()) {
            p.connection.disconnect(new TranslatableComponent("multiplayer.disconnect.invalid_packet"));
            FluxNetworks.LOGGER.info("Received invalid packet from player {}", p.getGameProfile().getName(), e);
        } else {
            FluxNetworks.LOGGER.info("Received invalid packet", e);
        }
    }

    private static void consume(FriendlyByteBuf payload) {
        if (payload.isReadable()) {
            throw new DecoderException("Payload is not fully consumed");
        }
    }

    private static void onDeviceBuffer(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                       BlockableEventLoop<?> looper) {
        looper.execute(() -> {
            ServerPlayer p = player.get();
            try {
                if (p != null && p.level.getBlockEntity(payload.readBlockPos()) instanceof TileFluxDevice e) {
                    if (e.canPlayerAccess(p)) {
                        byte id = payload.readByte();
                        if (id > 0) {
                            e.readPacket(payload, id);
                        } else {
                            throw new IllegalArgumentException();
                        }
                        consume(payload);
                    }
                }
            } catch (RuntimeException e) {
                kick(p, e);
            }
            payload.release();
        });
        throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
    }

    private static void onSuperAdmin(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                     BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final boolean enable = payload.readBoolean();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxPlayer fp = FluxUtils.get(p, FluxPlayer.FLUX_PLAYER);
            if (fp != null && (fp.isSuperAdmin() || FluxPlayer.canActivateSuperAdmin(p))) {
                fp.setSuperAdmin(enable);
                superAdmin(fp.isSuperAdmin(), p);
            } else {
                response(token, 0, FluxConstants.RESPONSE_REJECT, p);
            }
        });
    }

    private static void onEditDevice(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                     BlockableEventLoop<?> looper) {
        int networkId = payload.readVarInt();
        if (networkId == FluxConstants.INVALID_NETWORK_ID) {
            BlockPos pos = payload.readBlockPos();
            CompoundTag tag = payload.readNbt();
            consume(payload);
            Objects.requireNonNull(tag);
            looper.execute(() -> {
                ServerPlayer p = player.get();
                if (p == null) return;
                try {
                    if (p.level.getBlockEntity(pos) instanceof TileFluxDevice e) {
                        if (e.canPlayerAccess(p)) {
                            e.readCustomTag(tag, FluxConstants.NBT_TILE_SETTING);
                        }
                    }
                } catch (RuntimeException e) {
                    kick(p, e);
                }
            });
        } else {
            final int size = payload.readVarInt();
            if (size <= 0) {
                throw new IllegalArgumentException();
            }
            List<GlobalPos> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(FluxUtils.readGlobalPos(payload));
            }
            CompoundTag tag = payload.readNbt();
            consume(payload);
            Objects.requireNonNull(tag);
            looper.execute(() -> {
                ServerPlayer p = player.get();
                if (p == null) return;
                try {
                    FluxNetwork network = FluxNetworkData.getNetwork(networkId);
                    if (network.getPlayerAccess(p).canEdit()) {
                        for (GlobalPos pos : list) {
                            IFluxDevice f = network.getConnectionByPos(pos);
                            if (f instanceof TileFluxDevice e) {
                                e.readCustomTag(tag, FluxConstants.NBT_TILE_SETTING);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    kick(p, e);
                }
            });
        }
    }

    private static void onCreateNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                        BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final String name = payload.readUtf(256);
        final int color = payload.readInt();
        final SecurityLevel security = SecurityLevel.fromKey(payload.readByte());
        final String password = security.isEncrypted() ? payload.readUtf(256) : "";

        // validate
        consume(payload);
        if (FluxUtils.isBadNetworkName(name)) {
            throw new IllegalArgumentException("Invalid network name: " + name);
        }
        if (security.isEncrypted() && FluxUtils.isBadPassword(password)) {
            throw new IllegalArgumentException("Invalid network password: " + password);
        }

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            if (FluxNetworkData.getInstance().createNetwork(p, name, color, security, password) != null) {
                response(token, FluxConstants.REQUEST_CREATE_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
            } else {
                response(token, FluxConstants.REQUEST_CREATE_NETWORK, FluxConstants.RESPONSE_NO_SPACE, p);
            }
        });
    }

    private static void onDeleteNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                        BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            if (network.isValid()) {
                if (network.getPlayerAccess(p).canDelete()) {
                    FluxNetworkData.getInstance().deleteNetwork(network);
                    response(token, FluxConstants.REQUEST_DELETE_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
                } else {
                    response(token, FluxConstants.REQUEST_DELETE_NETWORK, FluxConstants.RESPONSE_NO_OWNER, p);
                }
            } else {
                response(token, FluxConstants.REQUEST_DELETE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
            }
        });
    }

    private static void onSetTileNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                         BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final BlockPos pos = payload.readBlockPos();
        final int networkID = payload.readVarInt();
        final String password = payload.readUtf(256);

        // validate
        consume(payload);
        if (!password.isEmpty() && FluxUtils.isBadPassword(password)) {
            throw new IllegalArgumentException("Invalid network password: " + password);
        }

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            if (p.level.getBlockEntity(pos) instanceof TileFluxDevice e) {
                if (e.getNetworkID() == networkID) {
                    return;
                }
                if (!e.canPlayerAccess(p)) {
                    response(token, FluxConstants.REQUEST_SET_NETWORK, FluxConstants.RESPONSE_REJECT, p);
                    return;
                }
                final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
                if (e.getDeviceType().isController() && network.getLogicalDevices(FluxNetwork.CONTROLLER).size() > 0) {
                    response(token, FluxConstants.REQUEST_SET_NETWORK, FluxConstants.RESPONSE_HAS_CONTROLLER, p);
                    return;
                }
                // we can connect to an invalid network (i.e. disconnect)
                if (!network.isValid() || network.canPlayerAccess(p, password)) {
                    if (network.isValid()) {
                        e.setConnectionOwner(p.getUUID());
                    }
                    e.connect(network);
                    response(token, FluxConstants.REQUEST_SET_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
                    return;
                }
                if (password.isEmpty()) {
                    response(token, FluxConstants.REQUEST_SET_NETWORK, FluxConstants.RESPONSE_REQUIRE_PASSWORD, p);
                } else {
                    response(token, FluxConstants.REQUEST_SET_NETWORK, FluxConstants.RESPONSE_INVALID_PASSWORD, p);
                }
            }
        });
    }

    private static void onEditNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                      BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final String name = payload.readUtf(256);
        final int color = payload.readInt();
        final SecurityLevel security = SecurityLevel.fromKey(payload.readByte());
        final String password = security.isEncrypted() ? payload.readUtf(256) : "";
        final int wireless = payload.readInt();

        // validate
        consume(payload);
        if (FluxUtils.isBadNetworkName(name)) {
            throw new IllegalArgumentException("Invalid network name: " + name);
        }
        if (!password.isEmpty() && FluxUtils.isBadPassword(password)) {
            throw new IllegalArgumentException("Invalid network password: " + password);
        }

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            if (network.isValid()) {
                if (network.getPlayerAccess(p).canEdit()) {
                    boolean changed = false;
                    if (!network.getNetworkName().equals(name)) {
                        network.setNetworkName(name);
                        changed = true;
                    }
                    if (network.getNetworkColor() != color) {
                        network.setNetworkColor(color);
                        // update renderer
                        network.getLogicalDevices(FluxNetwork.ANY).forEach(TileFluxDevice::sendBlockUpdate);
                        changed = true;
                    }
                    if (network.getSecurityLevel() != security) {
                        network.setSecurityLevel(security);
                        changed = true;
                    }
                    if (!password.isEmpty()) {
                        ((ServerFluxNetwork) network).setPassword(password);
                    }
                    if (wireless != -1 && network.getWirelessMode() != wireless) {
                        network.setWirelessMode(wireless);
                        changed = true;
                    }
                    if (changed) {
                        updateNetwork(network, FluxConstants.NBT_NET_BASIC).sendToAll();
                    }
                    response(token, FluxConstants.REQUEST_EDIT_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
                } else {
                    response(token, FluxConstants.REQUEST_EDIT_NETWORK, FluxConstants.RESPONSE_NO_ADMIN, p);
                }
            } else {
                response(token, FluxConstants.REQUEST_EDIT_NETWORK, FluxConstants.RESPONSE_REJECT, p);
            }
        });
    }

    private static void onUpdateNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                        BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int size = payload.readVarInt();
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        final int[] networkIDs = new int[size];
        for (int i = 0; i < size; i++) {
            networkIDs[i] = payload.readVarInt();
        }
        final byte type = payload.readByte();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            boolean reject = true;
            if (p.containerMenu.containerId == token && p.containerMenu instanceof FluxMenu menu) {
                if (FluxPlayer.isPlayerSuperAdmin(p)) {
                    reject = false;
                } else if (networkIDs.length == 1) {
                    // admin configurator is decoration, check access permission
                    if (!(menu.mProvider instanceof ItemAdminConfigurator.Provider)) {
                        final FluxNetwork network = FluxNetworkData.getNetwork(networkIDs[0]);
                        if (network.isValid() && menu.mProvider.getNetworkID() == networkIDs[0]) {
                            reject = false;
                        }
                    }
                }
            }
            if (reject) {
                response(token, FluxConstants.REQUEST_UPDATE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
            } else {
                // this packet always triggers an event, so no response
                updateNetwork(networkIDs, type).sendToPlayer(p);
            }
        });
    }

    private static void onEditMember(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                     BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final UUID targetUUID = payload.readUUID();
        final byte type = payload.readByte();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            if (!network.isValid()) {
                response(token, FluxConstants.REQUEST_EDIT_MEMBER, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            int code = network.changeMembership(p, targetUUID, type);
            if (code == FluxConstants.RESPONSE_SUCCESS) {
                updateNetwork(network, FluxConstants.NBT_NET_MEMBERS).sendToPlayer(p);
            }
            response(token, FluxConstants.REQUEST_EDIT_MEMBER, code, p);
        });
    }
}
