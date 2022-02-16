package pl.kosma.geodesy;

import com.google.common.collect.Sets;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.block.enums.StructureBlockMode;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.minecraft.block.Block.NOTIFY_LISTENERS;

public class GeodesyCore {

    // Build-time adjustments.
    static final int BUILD_MARGIN = 16;
    static final int WALL_OFFSET = 2;
    static final Block MARKER_BLOCKER = Blocks.WITHER_SKELETON_WALL_SKULL;
    static final Block MARKER_MACHINE = Blocks.ZOMBIE_WALL_HEAD;
    static final Block WORK_AREA_WALL = Blocks.TINTED_GLASS;
    static final Set<Block> PRESERVE_BLOCKS = Sets.newHashSet(Blocks.BUDDING_AMETHYST, Blocks.COMMAND_BLOCK);

    static final Logger LOGGER = LoggerFactory.getLogger("GeodesyCore");

    private World world;
    private IterableBlockBox geode;

    void geodesyArea(World world, BlockPos startPos, BlockPos endPos) {
        this.world = world;

        // Detect the geode area.
        detectGeode(startPos, endPos);
        prepareWorkArea(false);
        highlightGeode();
    }

    void geodesyProject(Direction[] directions) {
        // Expand the area and clear it out for work purposes.
        this.prepareWorkArea(true);
        this.growClusters();

        // Render a frame.
        IterableBlockBox frameBoundingBox = new IterableBlockBox(geode.expand(WALL_OFFSET));
        frameBoundingBox.forEachEdgePosition(blockPos -> world.setBlockState(blockPos, Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS));

        // Do nothing more if we have no direction - this signifies we just want
        // to draw the frame and do nothing else.
        if (directions.length == 0)
            return;

        // Count the amethyst clusters (for efficiency calculation).
        AtomicInteger clustersTotal = new AtomicInteger();
        geode.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.AMETHYST_CLUSTER) {
                clustersTotal.getAndIncrement();
            }
        });

        // Run the projection.
        for (Direction direction: directions) {
            this.projectGeode(direction);
        }

        // Replace all remaining amethyst clusters with buttons so items can't
        // fall on them and get stuck.
        AtomicInteger clustersLeft = new AtomicInteger();
        geode.forEachPosition(blockPos -> {
            BlockState blockState = world.getBlockState(blockPos);
            if (blockState.getBlock() == Blocks.AMETHYST_CLUSTER) {
                clustersLeft.getAndIncrement();
                BlockState button = Blocks.POLISHED_BLACKSTONE_BUTTON.getDefaultState();
                Direction facing = blockState.get(Properties.FACING);
                button = switch (facing) {
                    case DOWN -> button.with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.CEILING);
                    case UP -> button.with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.FLOOR);
                    default -> button.with(Properties.HORIZONTAL_FACING, facing);
                };
                world.setBlockState(blockPos, button, NOTIFY_LISTENERS);
            }
        });
        int clustersCollected = clustersTotal.get() - clustersLeft.get();

        // Re-grow the buds so they are visible.
        this.growClusters();

        // Calculate and show layout efficiency.
        float efficiency = 100f * (clustersTotal.get()-clustersLeft.get()) / clustersTotal.get();
        String layoutName = String.join(" ", Arrays.stream(directions).map(Direction::toString).collect(Collectors.joining(", ")));
        LOGGER.info("Layout efficiency for \"{}\": {}% ({}/{})", layoutName, (int) efficiency, clustersCollected, clustersTotal.get());
    }

    public void geodesyAnalyze() {
        // Run all possible projections and show the efficiencies.
        LOGGER.info("Running all possible projections to determine efficiencies...");
        geodesyProject(new Direction[]{Direction.EAST});
        geodesyProject(new Direction[]{Direction.SOUTH});
        geodesyProject(new Direction[]{Direction.UP});
        geodesyProject(new Direction[]{Direction.EAST, Direction.SOUTH});
        geodesyProject(new Direction[]{Direction.SOUTH, Direction.UP});
        geodesyProject(new Direction[]{Direction.UP, Direction.EAST});
        geodesyProject(new Direction[]{Direction.EAST, Direction.SOUTH, Direction.UP});
        // Clean up the results of the last projection.
        geodesyProject(new Direction[]{});
        // Advise the user.
        LOGGER.info("...projection complete. Use your judgement to choose the best set of projections.");
        LOGGER.info("Tips:");
        LOGGER.info("1. You can change the order of projections to make the flying machine layouts simpler.");
        LOGGER.info("2. You can change EAST to WEST, SOUTH to NORTH, UP to DOWN depending on your liking.");
        LOGGER.info("Those changes will not affect the farm's efficiency.");
    }

    void geodesyAssemble() {
        // Run along all the axes and move all slime/honey blocks inside the frame.
        for (Direction direction: Direction.values()) {
            geode.slice(direction.getAxis(), slice -> {
                // Calculate positions of the source and target blocks for moving.
                BlockPos targetPos = slice.getEndpoint(direction).offset(direction, WALL_OFFSET);
                BlockPos sourcePos = targetPos.offset(direction, 1);
                Block sourceBlock = world.getBlockState(sourcePos).getBlock();
                Block targetBlock = world.getBlockState(targetPos).getBlock();
                // Check that the operation can succeed.
                if (sourceBlock != Blocks.AIR && targetBlock != Blocks.CRYING_OBSIDIAN) {
                    world.setBlockState(targetPos, world.getBlockState(sourcePos), NOTIFY_LISTENERS);
                    world.setBlockState(sourcePos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                }
            });
        }

        // Check each slice for a marker block.
        for (Direction slicingDirection: Direction.values()) {
            geode.slice(slicingDirection.getAxis(), slice -> {
                // Check for blocker marker block.
                BlockPos blockerPos = slice.getEndpoint(slicingDirection).offset(slicingDirection, WALL_OFFSET+2);
                if (world.getBlockState(blockerPos).getBlock() != MARKER_BLOCKER)
                    return;
                // Find the position of the first machine block.
                BlockPos firstMachinePos = null;
                for (Direction direction: Direction.values()) {
                    if (world.getBlockState(blockerPos.offset(direction)).getBlock() == MARKER_MACHINE) {
                        firstMachinePos = blockerPos.offset(direction);
                        break;
                    }
                }
                if (firstMachinePos == null)
                    return;
                // First the direction of the second (and third) machine block.
                Direction machineDirection = null;
                for (Direction direction: Direction.values()) {
                    if (world.getBlockState(firstMachinePos.offset(direction, 1)).getBlock() == MARKER_MACHINE &&
                            world.getBlockState(firstMachinePos.offset(direction, 2)).getBlock() == MARKER_MACHINE) {
                        machineDirection = direction;
                        break;
                    }
                }
                if (machineDirection == null)
                    return;
                // Read the sticky block at machine position (we need its opposite).
                BlockPos stickyPos = slice.getEndpoint(slicingDirection).offset(slicingDirection, WALL_OFFSET);
                Block stickyBlock = world.getBlockState(stickyPos).getBlock();
                if (stickyBlock == Blocks.SLIME_BLOCK)
                    stickyBlock = Blocks.HONEY_BLOCK;
                else if (stickyBlock == Blocks.HONEY_BLOCK)
                    stickyBlock = Blocks.SLIME_BLOCK;
                else
                    return;
                // Important: the actual machine is built one block closer to the geode
                // than the player-placed markers are. Also wipe out the blocker glass because
                // it doesn't get removed otherwise.
                world.setBlockState(blockerPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                blockerPos = blockerPos.offset(slicingDirection.getOpposite());
                firstMachinePos = firstMachinePos.offset(slicingDirection.getOpposite());
                // All good - build the machine.
                buildMachine(blockerPos, firstMachinePos, slicingDirection, machineDirection, stickyBlock);
            });
        }
    }

    private void prepareWorkArea(boolean force) {
        IterableBlockBox workBoundingBox = new IterableBlockBox(geode.expand(BUILD_MARGIN));
        BlockPos commandBlockPos = new BlockPos(workBoundingBox.getMaxX(), workBoundingBox.getMaxY(), workBoundingBox.getMaxZ());

        // Check for existing command block, bail out if found.
        if (!force) {
            if (world.getBlockState(commandBlockPos).getBlock() == Blocks.COMMAND_BLOCK)
                return;
        }

        // Wipe out the area (except stuff we preserve)
        workBoundingBox.forEachPosition(blockPos -> {
            if (!PRESERVE_BLOCKS.contains(world.getBlockState(blockPos).getBlock()))
                world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        });

        // Place walls inside to prevent water and falling blocks from going bonkers.
        IterableBlockBox wallsBoundingBox = new IterableBlockBox(workBoundingBox.expand(1));
        wallsBoundingBox.forEachWallPosition(blockPos -> {
            world.setBlockState(blockPos, WORK_AREA_WALL.getDefaultState(), NOTIFY_LISTENERS);
        });

        // Add a command block to allow the player to reeexecute the command easily.
        world.setBlockState(commandBlockPos, Blocks.COMMAND_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        CommandBlockBlockEntity commandBlock = (CommandBlockBlockEntity) world.getBlockEntity(commandBlockPos);
        commandBlock.getCommandExecutor().setCommand(String.format("/geodesy area %d %d %d %d %d %d",
                geode.getMinX(), geode.getMinY(), geode.getMinZ(), geode.getMaxX(), geode.getMaxY(), geode.getMaxZ()));
        commandBlock.markDirty();
    }

    private void detectGeode(BlockPos pos1, BlockPos pos2) {
        // Calculate the correct min/max coordinates and construct a box.
        IterableBlockBox scanBox = new IterableBlockBox(new BlockBox(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        ));

        // Scan the box, marking any positions with budding amethyst.
        List<BlockPos> amethystPositions = new ArrayList<>();
        scanBox.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST)
                amethystPositions.add(blockPos);
        });

        // Calculate the minimum bounding box that contains these positions.
        // The expand is to make sure we grab all the amethyst clusters as well.
        geode = new IterableBlockBox(BlockBox.encompassPositions(amethystPositions).orElse(null).expand(1));
    }

    private void highlightGeode() {
        // Highlight the geode area.
        int commandBlockOffset = WALL_OFFSET+1;
        BlockPos structureBlockPos = new BlockPos(geode.getMinX()-commandBlockOffset, geode.getMinY()-commandBlockOffset, geode.getMinZ()-commandBlockOffset);
        world.setBlockState(structureBlockPos, Blocks.STRUCTURE_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        StructureBlockBlockEntity structure = (StructureBlockBlockEntity) world.getBlockEntity(structureBlockPos);
        structure.setMode(StructureBlockMode.SAVE);
        structure.setStructureName("geode");
        structure.setOffset(new BlockPos(commandBlockOffset, commandBlockOffset, commandBlockOffset));
        structure.setSize(geode.getDimensions().add(1, 1, 1));
        structure.setShowBoundingBox(true);
        structure.markDirty();
    }

    private void growClusters() {
        geode.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST) {
                for (Direction direction: Direction.values()) {
                    BlockPos budPos = blockPos.offset(direction);
                    if (world.getBlockState(budPos).getBlock() == Blocks.AIR)
                        world.setBlockState(budPos, Blocks.AMETHYST_CLUSTER.getDefaultState().with(AmethystClusterBlock.FACING, direction));
                }
            }
        });
    }

    private void projectGeode(Direction direction) {
        geode.slice(direction.getAxis(), slice -> {
            // For each slice, determine the block composition
            AtomicBoolean hasBlock = new AtomicBoolean(false);
            AtomicBoolean hasCluster = new AtomicBoolean(false);
            slice.forEachPosition(blockPos -> {
                BlockState blockState = world.getBlockState(blockPos);
                Block block = blockState.getBlock();
                if (block == Blocks.BUDDING_AMETHYST)
                    hasBlock.set(true);
                if (block == Blocks.AMETHYST_CLUSTER)
                    hasCluster.set(true);
            });
            // Choose sidewall block type depending on the block composition on the slice
            Block wallBlock;
            if (hasBlock.get())
                wallBlock = Blocks.CRYING_OBSIDIAN;
            else if (hasCluster.get())
                wallBlock = Blocks.MOSS_BLOCK;
            else
                wallBlock = Blocks.AIR;
            // If this location has a flying machine, wipe out everything in that slice
            // to simulate the flying machine doing its work.
            if (wallBlock == Blocks.MOSS_BLOCK) {
                slice.forEachPosition(blockPos -> {
                    world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                });
            }
            // Set sidewall block
            BlockPos wallPos = slice.getEndpoint(direction).offset(direction, WALL_OFFSET);
            world.setBlockState(wallPos, wallBlock.getDefaultState());
            // Set the opposite block to crying obsidian.
            BlockPos blockerPos = slice.getEndpoint(direction.getOpposite()).offset(direction.getOpposite(), WALL_OFFSET);
            world.setBlockState(blockerPos, Blocks.CRYING_OBSIDIAN.getDefaultState());
        });
    }

    private void buildMachine(BlockPos blockerPos, BlockPos pos, Direction directionAlong, Direction directionUp, Block stickyBlock) {
        /*
         * It looks like this:
         * S HHH
         * S HVHH[<N
         * SB[L>]SSSB
         */
        // Blocker block.
        world.setBlockState(blockerPos, Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);
        // Clear out the machine marker blocks.
        world.setBlockState(pos.offset(directionUp, 0), Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 2), Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // First layer: piston, 2 slime
        world.setBlockState(pos.offset(directionUp, 0), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionAlong.getOpposite()), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 2), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Second layer: redstone lamp, observer, slime (order is important)
        world.setBlockState(pos.offset(directionUp, 2), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionUp), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 0), Blocks.REDSTONE_LAMP.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Third layer: observer, slime, slime
        world.setBlockState(pos.offset(directionUp, 0), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionAlong.getOpposite()), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 2), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Fourth layer: piston, slime
        world.setBlockState(pos.offset(directionUp, 0), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionAlong), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Fifth layer: slime, piston
        world.setBlockState(pos.offset(directionUp, 0), Blocks.SLIME_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionAlong.getOpposite()), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 2);
        // [SKIP!] Seventh layer: slime, note block
        world.setBlockState(pos.offset(directionUp, 0), Blocks.SLIME_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.NOTE_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, -1);
        // [GO BACK!] Sixth layer: slime, observer
        // This one is tricky, we initially set the observer in a wrong direction
        // so the note block tune change is not triggered.
        world.setBlockState(pos.offset(directionUp, 1), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionUp), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 0), Blocks.SLIME_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionAlong), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 2);
        // [SKIP AGAIN!] Eighth layer: blocker
        world.setBlockState(pos.offset(directionUp, 0), Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);
    }
}
