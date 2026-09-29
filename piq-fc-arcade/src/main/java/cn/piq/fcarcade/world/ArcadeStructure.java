package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.SquareFormation;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.layout.RectangularFormation;
import cn.piq.fcarcade.session.ArcadeMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public record ArcadeStructure(
        BlockPos anchor,
        Direction facing,
        int width,
        int height,
        ArcadeMode mode
) {
    private static final int MAX_PANEL_DIMENSION = 8;

    public static ArcadeStructure resolve(Level level, BlockPos clickedMember) {
        BlockPos dualAnchor = DualCabinetStructure.resolveAnchor(level, clickedMember);
        BlockPos portraitAnchor=PortraitCabinetBlock.resolveAnchor(level,clickedMember);
        final BlockPos member = dualAnchor == null ? (portraitAnchor==null?clickedMember:portraitAnchor) : dualAnchor;
        BlockState memberState = level.getBlockState(member);
        if (!(memberState.getBlock() instanceof FcArcadeBlock arcadeBlock)) {
            return new ArcadeStructure(
                    member.immutable(),
                    Direction.NORTH,
                    1,
                    1,
                    ArcadeMode.LOCKSTEP);
        }

        Direction facing = memberState.getValue(FcArcadeBlock.FACING);
        ArcadeMode mode = arcadeBlock.mode();
        if (arcadeBlock.displayStyle() == ArcadeDisplayStyle.DUAL_CABINET) {
            return new ArcadeStructure(member.immutable(), facing, 2, 3, mode);
        }
        if (arcadeBlock.displayStyle()
                == ArcadeDisplayStyle.WATERFRAMES_PANEL) {
            return resolvePanel(
                    level,
                    member,
                    facing,
                    mode,
                    memberState.getBlock());
        }
        if (!arcadeBlock.supportsFormation()) {
            return new ArcadeStructure(
                    member.immutable(),
                    facing,
                    1,
                    1,
                    mode);
        }
        var memberBlock = memberState.getBlock();
        Direction screenRight = facing.getCounterClockWise();
        for (int size = 3; size >= 2; size--) {
            var anchorOffset = SquareFormation.findAnchor(size, offset -> isCell(
                    level,
                    member.relative(screenRight, offset.horizontal()).above(offset.vertical()),
                    facing,
                    memberBlock));
            if (anchorOffset.isPresent()) {
                var offset = anchorOffset.get();
                BlockPos anchor = member
                        .relative(screenRight, offset.horizontal())
                        .above(offset.vertical());
                return new ArcadeStructure(
                        anchor.immutable(),
                        facing,
                        size,
                        size,
                        mode);
            }
        }
        return new ArcadeStructure(
                member.immutable(),
                facing,
                1,
                1,
                mode);
    }

    private static ArcadeStructure resolvePanel(
            Level level,
            BlockPos member,
            Direction facing,
            ArcadeMode mode,
            net.minecraft.world.level.block.Block memberBlock
    ) {
        Direction screenRight = facing.getCounterClockWise();
        Set<PanelOffset> cells = new HashSet<>();
        ArrayDeque<PanelOffset> pending = new ArrayDeque<>();
        PanelOffset origin = new PanelOffset(0, 0);
        cells.add(origin);
        pending.add(origin);

        while (!pending.isEmpty()) {
            PanelOffset current = pending.removeFirst();
            for (PanelOffset neighbor : current.neighbors()) {
                if (cells.contains(neighbor)) continue;
                BlockPos neighborPos = member
                        .relative(screenRight, neighbor.horizontal())
                        .above(neighbor.vertical());
                if (!isCell(level, neighborPos, facing, memberBlock)) continue;
                cells.add(neighbor);
                if (cells.size()
                        > MAX_PANEL_DIMENSION * MAX_PANEL_DIMENSION) {
                    return single(member, facing, mode);
                }
                pending.addLast(neighbor);
            }
        }

        Set<RectangularFormation.Cell> formationCells = new HashSet<>();
        for (PanelOffset cell : cells) {
            formationCells.add(new RectangularFormation.Cell(
                    cell.horizontal(),
                    cell.vertical()));
        }
        var bounds = RectangularFormation.resolve(
                formationCells,
                MAX_PANEL_DIMENSION);
        if (bounds.isEmpty()) {
            return single(member, facing, mode);
        }
        var rectangle = bounds.get();

        BlockPos anchor = member
                .relative(screenRight, rectangle.minimumHorizontal())
                .above(rectangle.minimumVertical());
        return new ArcadeStructure(
                anchor.immutable(),
                facing,
                rectangle.width(),
                rectangle.height(),
                mode);
    }

    private static ArcadeStructure single(
            BlockPos member,
            Direction facing,
            ArcadeMode mode
    ) {
        return new ArcadeStructure(
                member.immutable(),
                facing,
                1,
                1,
                mode);
    }

    private static boolean isCell(
            Level level,
            BlockPos pos,
            Direction facing,
            net.minecraft.world.level.block.Block memberBlock
    ) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == memberBlock
                && state.getValue(FcArcadeBlock.FACING) == facing;
    }

    private record PanelOffset(int horizontal, int vertical) {
        private Iterable<PanelOffset> neighbors() {
            return java.util.List.of(
                    new PanelOffset(horizontal - 1, vertical),
                    new PanelOffset(horizontal + 1, vertical),
                    new PanelOffset(horizontal, vertical - 1),
                    new PanelOffset(horizontal, vertical + 1));
        }
    }
}
