// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import cn.piq.sfcarcade.net.SfcNetwork;
import cn.piq.sfcarcade.rom.SfcRomEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SfcRomLibraryScreen extends Screen {
    private static final int PAGE_SIZE = 6;
    private static BlockPos rememberedBlockPos;
    private static int rememberedPage;

    private final BlockPos blockPos;
    private final String selectedHash;
    private final List<Entry> entries;
    private int page;

    public SfcRomLibraryScreen(
            SfcNetwork.LibraryPayload payload,
            List<SfcRomEntry> localRoms
    ) {
        super(Component.translatable("screen.piq_sfc_arcade.library_title"));
        blockPos = payload.pos().immutable();
        selectedHash = payload.selectedHash();

        Map<String, EntryBuilder> merged = new LinkedHashMap<>();
        for (SfcNetwork.CatalogEntry server : payload.entries()) {
            merged.computeIfAbsent(server.sha256(), ignored -> new EntryBuilder())
                    .server = server;
        }
        for (SfcRomEntry local : localRoms) {
            merged.computeIfAbsent(local.sha256(), ignored -> new EntryBuilder())
                    .local = local;
        }
        entries = merged.values().stream()
                .map(EntryBuilder::build)
                .sorted(Comparator.comparing(
                        Entry::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        int lastPage = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        page = rememberedBlockPos != null && rememberedBlockPos.equals(blockPos)
                ? Math.min(rememberedPage, lastPage)
                : 0;
        rememberPage();
    }

    @Override
    protected void init() {
        clearWidgets();
        int contentWidth = Math.min(520, width - 32);
        int left = (width - contentWidth) / 2;
        int start = page * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            Entry entry = entries.get(index);
            Button button = Button.builder(entryLabel(entry), ignored -> choose(entry))
                    .bounds(left, 54 + (index - start) * 24, contentWidth, 20)
                    .build();
            addRenderableWidget(button);
        }

        int bottom = height - 52;
        Button previous = Button.builder(
                        Component.translatable("screen.piq_sfc_arcade.previous"),
                        ignored -> {
                            page--;
                            rememberPage();
                            rebuildWidgets();
                        })
                .bounds(left, bottom, 92, 20).build();
        previous.active = page > 0;
        addRenderableWidget(previous);

        Button next = Button.builder(
                        Component.translatable("screen.piq_sfc_arcade.next"),
                        ignored -> {
                            page++;
                            rememberPage();
                            rebuildWidgets();
                        })
                .bounds(left + 98, bottom, 92, 20).build();
        next.active = (page + 1) * PAGE_SIZE < entries.size();
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.piq_sfc_arcade.open_rom_folder"),
                        ignored -> openRomFolder())
                .bounds(left + contentWidth - 190, bottom, 190, 20).build());

        int lowerWidth = (contentWidth - 4) / 2;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.piq_sfc_arcade.refresh"),
                        ignored -> {
                            SfcNetwork.requestLibrary(blockPos);
                            onClose();
                        })
                .bounds(left, bottom + 24, lowerWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left + lowerWidth + 4, bottom + 24, lowerWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.drawCenteredString(font, Component.translatable(
                        "screen.piq_sfc_arcade.library_page", page + 1, pages),
                width / 2, 32, 0xA0A0A0);
        graphics.drawCenteredString(font, Component.translatable(
                        "screen.piq_sfc_arcade.library_upload_notice"),
                width / 2, height - 76, 0xE0B060);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(
                    "screen.piq_sfc_arcade.library_empty"), width / 2, 76, 0xE0A040);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void choose(Entry entry) {
        ClientSfcRomTransfers.select(blockPos, entry.sha256(), entry.server() != null);
    }

    private Component entryLabel(Entry entry) {
        String marker = entry.sha256().equals(selectedHash) ? "▶ " : "";
        MutableComponent label = Component.literal(marker + entry.displayName() + " · ");
        if (entry.local() != null && entry.server() != null) {
            label.append(Component.translatable("screen.piq_sfc_arcade.synced")
                    .withStyle(ChatFormatting.GREEN));
        } else if (entry.local() != null) {
            label.append(Component.translatable("screen.piq_sfc_arcade.location_local"))
                    .append(Component.literal(" · "))
                    .append(Component.translatable("screen.piq_sfc_arcade.upload_game")
                            .withStyle(ChatFormatting.GOLD));
        } else {
            label.append(Component.translatable("screen.piq_sfc_arcade.location_server"));
        }
        return label;
    }

    private void openRomFolder() {
        try {
            Files.createDirectories(ClientSfcRomLibrary.root());
            Util.getPlatform().openFile(ClientSfcRomLibrary.root().toFile());
        } catch (IOException error) {
            if (minecraft != null) {
                minecraft.gui.setOverlayMessage(Component.translatable(
                        "message.piq_sfc_arcade.open_folder_failed", readable(error)), false);
            }
        }
    }

    private void rememberPage() {
        rememberedBlockPos = blockPos;
        rememberedPage = page;
    }

    private static String readable(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private record Entry(
            String sha256,
            String displayName,
            SfcRomEntry local,
            SfcNetwork.CatalogEntry server
    ) {
    }

    private static final class EntryBuilder {
        private SfcRomEntry local;
        private SfcNetwork.CatalogEntry server;

        private Entry build() {
            return new Entry(
                    local != null ? local.sha256() : server.sha256(),
                    server != null ? server.fileName() : local.fileName(),
                    local,
                    server);
        }
    }
}
