package cn.piq.fcarcade.home;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomSaveMode;
import cn.piq.fcarcade.server.ServerCartridgeService;
import cn.piq.fcarcade.server.ServerCartridgeAssemblyService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dedicated, versioned cartridge protocol; no arcade block-position authorization bypass. */
public final class CartridgeNetwork {
    public static final int REFRESH = 0, WRITE = 1, START_ROM = 2, START_COVER = 3,
            CHUNK = 4, FINISH = 5, CANCEL = 6, DOWNLOAD_COVER = 7, SET_PLAYERS = 8, SET_SAVE_MODE = 9;
    public static final int OPEN = 0, STATUS = 1, UPLOAD_READY = 2, COVER_START = 3,
            COVER_CHUNK = 4, CLOSED = 5;
    public static final CartridgeEditBinding NO_TARGET = new CartridgeEditBinding(new UUID(0, 0), new UUID(0, 0), 0, 0);
    private CartridgeNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        CartridgeSaveNetwork.register(event);
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"34")
                .playToServer(Request.TYPE, Request.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerCartridgeService.handle(player, payload);
                }))
                .playToServer(DismantleRequest.TYPE, DismantleRequest.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerCartridgeAssemblyService.dismantle(player, payload.binding());
                }))
                .playToClient(Reply.TYPE, Reply.CODEC, (payload, context) -> {
                    if (FMLEnvironment.dist.isClient()) context.enqueueWork(() -> ClientDispatch.handle(payload));
                });
    }
    private static final class ClientDispatch {
        static void handle(Reply reply) {
            if (reply.operation == COVER_START || reply.operation == COVER_CHUNK)
                cn.piq.fcarcade.client.ClientCartridgeCovers.receive(reply);
            else cn.piq.fcarcade.client.ClientCartridgeEditor.receive(reply);
        }
    }
    public static void send(Request request) { PacketDistributor.sendToServer(request); }
    /** Client input hook captures only a synchronized main-hand identity; never sends ROM/cover output data. */
    public static void dismantle(ItemStack stack, int selectedSlot) {
        if (!FcCartridgeData.isCartridge(stack) || stack.getCount() != 1) return;
        UUID id = FcCartridgeData.id(stack);
        if (id == null) id = CartridgeAssemblyBinding.ZERO; // Server can explain unsupported metadata, but never authorizes a missing identity.
        try {
            PacketDistributor.sendToServer(new DismantleRequest(new CartridgeAssemblyBinding(UUID.randomUUID(),
                    selectedSlot, id, FcCartridgeData.assemblyRevision(stack))));
        } catch (IllegalArgumentException ignored) { /* Malformed held data is never a client crash or authority bypass. */ }
    }
    public record DismantleRequest(CartridgeAssemblyBinding binding) implements CustomPacketPayload {
        public DismantleRequest { if (binding == null) throw new IllegalArgumentException("拆卡请求无效"); }
        public static final Type<DismantleRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"cartridge_dismantle"));
        public static final StreamCodec<RegistryFriendlyByteBuf,DismantleRequest> CODEC = StreamCodec.of((buffer,value) -> {
            CartridgeAssemblyBinding target = value.binding;
            buffer.writeUUID(target.requestId()); buffer.writeByte(target.slot());
            buffer.writeUUID(target.cartridgeId()); buffer.writeVarLong(target.revision());
        }, buffer -> new DismantleRequest(new CartridgeAssemblyBinding(buffer.readUUID(),buffer.readUnsignedByte(),
                buffer.readUUID(),buffer.readVarLong())));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void reply(ServerPlayer player, Reply reply) { PacketDistributor.sendToPlayer(player, reply); }
    public static Request request(int op, CartridgeEditBinding target) {
        return new Request(op, target, "", "", "", "", 0, 0, new byte[0]);
    }
    private static void writeTarget(RegistryFriendlyByteBuf buffer, CartridgeEditBinding target) {
        buffer.writeUUID(target.token()); buffer.writeUUID(target.cartridgeId());
        buffer.writeByte(target.hand()); buffer.writeVarInt(target.slot());
    }
    private static CartridgeEditBinding readTarget(RegistryFriendlyByteBuf buffer) {
        return new CartridgeEditBinding(buffer.readUUID(), buffer.readUUID(), buffer.readUnsignedByte(), buffer.readVarInt());
    }
    private static String text(String value, int max) {
        if (value == null || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("卡带消息文字无效");
        return value;
    }
    public record Request(int operation, CartridgeEditBinding target, String hash, String cover,
                          String title, String fileName, int total, int offset, byte[] data) implements CustomPacketPayload {
        public Request {
            if (operation < 0 || operation > SET_SAVE_MODE || target == null || total < 0
                    || total > RomRepository.MAX_ROM_BYTES || offset < 0 || offset > RomRepository.MAX_ROM_BYTES
                    || data == null || data.length > CartridgeLimits.CHUNK_BYTES) throw new IllegalArgumentException("卡带请求无效");
            hash = CartridgeLimits.hashOrEmpty(hash); cover = CartridgeLimits.hashOrEmpty(cover);
            title = CartridgeLimits.cleanTitle(title); fileName = text(fileName, 128); data = data.clone();
            if(operation==SET_SAVE_MODE&&(hash.isEmpty()||total>2||offset!=0||data.length!=0
                    ||!cover.isEmpty()||!title.isEmpty()||!fileName.isEmpty()))throw new IllegalArgumentException("卡带存档设置无效");
        }
        @Override public byte[] data() { return data.clone(); }
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "cartridge_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeByte(value.operation); writeTarget(buffer, value.target);
            buffer.writeUtf(value.hash, 64); buffer.writeUtf(value.cover, 64); buffer.writeUtf(value.title, CartridgeLimits.MAX_TITLE);
            buffer.writeUtf(value.fileName, 128); buffer.writeVarInt(value.total); buffer.writeVarInt(value.offset); buffer.writeByteArray(value.data);
        }, buffer -> new Request(buffer.readUnsignedByte(), readTarget(buffer), buffer.readUtf(64), buffer.readUtf(64),
                buffer.readUtf(CartridgeLimits.MAX_TITLE), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray(CartridgeLimits.CHUNK_BYTES)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Reply(int operation, CartridgeEditBinding target, String hash, String romSha, String coverSha,
                        String title, String message, int total, int offset, byte[] data,
                        List<RomCatalogEntry> catalog, int capabilities, List<String> covers, RomSaveMode cardSaveMode) implements CustomPacketPayload {
        public Reply(int operation,CartridgeEditBinding target,String hash,String romSha,String coverSha,String title,String message,int total,int offset,byte[] data,List<RomCatalogEntry> catalog,int capabilities,List<String> covers){
            this(operation,target,hash,romSha,coverSha,title,message,total,offset,data,catalog,capabilities,covers,RomSaveMode.NONE);
        }
        public Reply(int operation, CartridgeEditBinding target, String hash, String romSha, String coverSha,
                     String title, String message, int total, int offset, byte[] data, List<RomCatalogEntry> catalog, int capabilities) {
            this(operation, target, hash, romSha, coverSha, title, message, total, offset, data, catalog, capabilities, List.of());
        }
        /** Legacy callers are fail-closed: only the authoritative editor supplies permissions. */
        public Reply(int operation, CartridgeEditBinding target, String hash, String romSha, String coverSha,
                     String title, String message, int total, int offset, byte[] data, List<RomCatalogEntry> catalog) {
            this(operation, target, hash, romSha, coverSha, title, message, total, offset, data, catalog, 0);
        }
        public Reply {
            java.util.Objects.requireNonNull(cardSaveMode);
            if (operation < 0 || operation > CLOSED || target == null || total < 0
                    || total > CartridgeLimits.MAX_COVER_BYTES || offset < 0 || offset > CartridgeLimits.MAX_COVER_BYTES
                    || data == null || data.length > CartridgeLimits.CHUNK_BYTES || catalog == null
                    || catalog.size() > CartridgeLimits.MAX_CATALOG || capabilities < 0 || capabilities > cn.piq.fcarcade.access.PlayerContentPolicy.ALL
                    || covers == null || covers.size() > 256)
                throw new IllegalArgumentException("卡带响应无效");
            hash = CartridgeLimits.hashOrEmpty(hash); romSha = CartridgeLimits.hashOrEmpty(romSha); coverSha = CartridgeLimits.hashOrEmpty(coverSha);
            title = CartridgeLimits.cleanTitle(title); message = text(message, 256); data = data.clone(); catalog = List.copyOf(catalog);
            covers = List.copyOf(covers);
            if (covers.stream().anyMatch(v -> !CartridgeLimits.validHash(v)) || covers.stream().distinct().count() != covers.size())
                throw new IllegalArgumentException("封面目录无效");
        }
        @Override public byte[] data() { return data.clone(); }
        public static final Type<Reply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "cartridge_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeByte(value.operation); writeTarget(buffer, value.target);
            buffer.writeUtf(value.hash, 64); buffer.writeUtf(value.romSha, 64); buffer.writeUtf(value.coverSha, 64);
            buffer.writeUtf(value.title, CartridgeLimits.MAX_TITLE); buffer.writeUtf(value.message, 256);
            buffer.writeVarInt(value.total); buffer.writeVarInt(value.offset); buffer.writeByteArray(value.data);
            buffer.writeVarInt(value.capabilities);
            buffer.writeVarInt(value.catalog.size());
            for (RomCatalogEntry entry : value.catalog) {
                buffer.writeUtf(entry.fileName(), 128); buffer.writeUtf(entry.sha256(), 64);
                buffer.writeVarInt(entry.size()); buffer.writeVarInt(entry.mapper()); buffer.writeVarInt(entry.maxPlayers());
                buffer.writeVarInt(entry.saveMode().id());
            }
            buffer.writeVarInt(value.covers.size());
            for (String cover : value.covers) buffer.writeUtf(cover, 64);
            buffer.writeByte(value.cardSaveMode.id());
        }, buffer -> {
            int op = buffer.readUnsignedByte(); CartridgeEditBinding target = readTarget(buffer);
            String hash = buffer.readUtf(64), rom = buffer.readUtf(64), cover = buffer.readUtf(64);
            String title = buffer.readUtf(CartridgeLimits.MAX_TITLE), message = buffer.readUtf(256);
            int total = buffer.readVarInt(), offset = buffer.readVarInt(); byte[] bytes = buffer.readByteArray(CartridgeLimits.CHUNK_BYTES);
            int capabilities = buffer.readVarInt();
            int count = buffer.readVarInt();
            if (count < 0 || count > CartridgeLimits.MAX_CATALOG) throw new IllegalArgumentException("卡带目录过大");
            List<RomCatalogEntry> catalog = new ArrayList<>(count);
            for (int i = 0; i < count; i++) catalog.add(new RomCatalogEntry(buffer.readUtf(128), buffer.readUtf(64),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), RomSaveMode.fromId(buffer.readVarInt())));
            int coverCount = buffer.readVarInt();
            if (coverCount < 0 || coverCount > 256) throw new IllegalArgumentException("封面目录过大");
            List<String> covers = new ArrayList<>(coverCount);
            for (int i = 0; i < coverCount; i++) covers.add(buffer.readUtf(64));
            return new Reply(op, target, hash, rom, cover, title, message, total, offset, bytes, catalog, capabilities, covers,RomSaveMode.fromId(buffer.readUnsignedByte()));
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
