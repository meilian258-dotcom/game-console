package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.J2meSelectGamePayload;
import cn.piq.j2mearcade.core.J2meGameDescriptor;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.List;

final class J2meLibraryScreen extends Screen {
    private static final int PAGE_SIZE = 7;

    private final Path directory;
    private final List<J2meGameDescriptor> games;
    private final BlockPos machinePos;
    private int page;

    J2meLibraryScreen(Path directory, List<J2meGameDescriptor> games) {
        this(directory, games, null);
    }

    J2meLibraryScreen(Path directory, List<J2meGameDescriptor> games, BlockPos machinePos) {
        super(Component.translatable("screen.piq_j2me_arcade.library_title"));
        this.directory = directory;
        this.games = games;
        this.machinePos = machinePos == null ? null : machinePos.immutable();
    }

    @Override
    protected void init() {
        clearWidgets();
        int contentWidth = Math.min(420, width - 32);
        int left = (width - contentWidth) / 2;
        int top = machinePos == null ? 48 : 62;
        int start = page * PAGE_SIZE;
        int end = Math.min(games.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            J2meGameDescriptor game = games.get(index);
            addRenderableWidget(Button.builder(
                            Component.literal(game.name()),
                            ignored -> selectGame(game))
                    .bounds(left, top + (index - start) * 24, contentWidth, 20)
                    .build());
        }

        int bottom = height - 32;
        Button previous = Button.builder(Component.literal("<"), ignored -> changePage(-1))
                .bounds(left, bottom, 32, 20).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.piq_j2me_arcade.open_folder"),
                        ignored -> Util.getPlatform().openFile(directory.toFile()))
                .bounds(left + 40, bottom, contentWidth - 80, 20).build());
        Button next = Button.builder(Component.literal(">"), ignored -> changePage(1))
                .bounds(left + contentWidth - 32, bottom, 32, 20).build();
        next.active = (page + 1) * PAGE_SIZE < games.size();
        addRenderableWidget(next);
    }

    private void selectGame(J2meGameDescriptor game) {
        if (machinePos == null) {
            minecraft.setScreen(new J2meGameScreen(game));
            return;
        }
        PacketDistributor.sendToServer(new J2meSelectGamePayload(
                machinePos, game.jar().getFileName().toString()));
        ClientJ2meArcade.startMachine(machinePos, game);
    }

    private void changePage(int offset) {
        page = Math.max(0, page + offset);
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Supply a flat backdrop; Screen.render dispatches to the no-op
        // renderBackground override below before drawing the buttons.
        graphics.fill(0, 0, width, height, 0xB0101218);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFFFF);
        if (machinePos != null) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.piq_j2me_arcade.select_for_machine"),
                    width / 2, 34, 0xFF78E6A8);
        }
        if (games.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.piq_j2me_arcade.no_games"),
                    width / 2, height / 2 - 10, 0xFFFFCC55);
            graphics.drawCenteredString(font, directory.toString(),
                    width / 2, height / 2 + 8, 0xFFAAAAAA);
        } else {
            int pages = (games.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            graphics.drawCenteredString(font,
                    Component.translatable("screen.piq_j2me_arcade.page", page + 1, pages),
                    width / 2, machinePos == null ? 34 : 45, 0xFFAAAAAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        // Keep the game list, buttons and Minecraft text pixel-sharp.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
