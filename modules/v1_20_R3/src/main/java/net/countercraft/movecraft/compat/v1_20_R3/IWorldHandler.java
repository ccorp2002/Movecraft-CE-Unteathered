package net.countercraft.movecraft.compat.v1_20_R3;

import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.MovecraftChunk;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.WorldHandler;
import net.countercraft.movecraft.craft.ChunkManager;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.util.CollectionUtils;

import net.countercraft.movecraft.util.MathUtils;
import net.countercraft.movecraft.util.UnsafeUtils;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.v1_20_R3.util.RandomSourceWrapper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.ticks.ScheduledTick;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_20_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_20_R3.CraftChunk;
import org.bukkit.craftbukkit.v1_20_R3.block.CraftBlock;
import org.bukkit.craftbukkit.v1_20_R3.block.data.CraftBlockData;
import org.bukkit.craftbukkit.v1_20_R3.inventory.CraftInventoryView;
import org.bukkit.craftbukkit.v1_20_R3.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings("unused")
public class IWorldHandler extends WorldHandler {
    private static final Rotation ROTATION[];
    static {
        ROTATION = new Rotation[3];
        ROTATION[MovecraftRotation.NONE.ordinal()] = Rotation.NONE;
        ROTATION[MovecraftRotation.CLOCKWISE.ordinal()] = Rotation.CLOCKWISE_90;
        ROTATION[MovecraftRotation.ANTICLOCKWISE.ordinal()] = Rotation.COUNTERCLOCKWISE_90;
    }
    private final BlockData air = Bukkit.getServer().createBlockData("minecraft:air");
    private final NextTickProvider tickProvider = new NextTickProvider();
    private static final Plugin PLUGIN = Bukkit.getPluginManager().getPlugin("Movecraft");
    private static final RandomSource RANDOM = new RandomSourceWrapper(new java.util.Random());
    

    public IWorldHandler() {}
//    @Override
//    public void addPlayerLocation(Player player, double x, double y, double z, float yaw, float pitch){
//        ServerPlayer ePlayer = ((CraftPlayer) player).getHandle();
//        ePlayer.connection.teleport(x, y, z, yaw, pitch, EnumSet.allOf(ClientboundPlayerPositionPacket.RelativeArgument.class));
//    }

    public boolean doesObjectContainField(Object object, String fieldName) {
        return Arrays.stream(object.getClass().getFields())
                .anyMatch(f -> f.getName().equals(fieldName));
    }
    @Override
    public boolean runTaskInCraftWorld(@NotNull Runnable runMe, Craft craft) {
        return runTaskInWorld(runMe,craft.getWorld());
    }
    @Override
    public boolean runTaskInWorld(@NotNull Runnable runMe, org.bukkit.World world) {
            final ServerLevel nativeWorld = ((CraftWorld) world).getHandle();
            boolean ran = false;
            if (doesObjectContainField(nativeWorld,"tickExecutor") && Settings.IS_MULTITHREADED) {
                try {
                    nativeWorld.tickExecutor.submit(() -> {

                        io.papermc.paper.util.TickThread.ServerLevelTickThread currentThread = (io.papermc.paper.util.TickThread.ServerLevelTickThread) Thread.currentThread();
                        currentThread.currentlyTickingServerLevel = nativeWorld;
                        runMe.run();
                        currentThread.currentlyTickingServerLevel = null;
                    }, nativeWorld).get();
                    ran = true;
                } catch (Exception exc) {
                    ran = false;
                }
            } else {
                ran = false;
            }
            return ran;
        //return true;
    }
    public void rotateCraftVert(@NotNull final Craft craft, @NotNull final MovecraftLocation originPoint, @NotNull final MovecraftRotation rotation, @NotNull final HitBox newHitBox, @NotNull final HitBox oldHitBox) {
        //*******************************************
        //*      Step one: Convert to Positions     *
        //*******************************************

        final ServerLevel nativeWorld = ((CraftWorld) craft.getWorld()).getHandle();

        final Map<BlockPos,BlockState> blockData = new LinkedHashMap<>(craft.getHitBox().size(),1.5f);
        final BlockState air = Blocks.AIR.defaultBlockState();
        final List<TileHolder> tiles = new ArrayList<>();
        oldHitBox.forEach((movecraftLocation) -> {
            MovecraftLocation rotatedPosition = MathUtils.rotateVecAxis(rotation,movecraftLocation,craft.getCruiseDirection());
            BlockPos position = locationToPosition(movecraftLocation);
            BlockState state = getBlockFastest(nativeWorld,position);
            blockData.put(locationToPosition(rotatedPosition),state);
            final BlockEntity tile = removeBlockEntity(nativeWorld, position);
            if (tile != null) tiles.add(new TileHolder(tile, tickProvider.getNextTick(nativeWorld, position), position));
            //get the nextTick to move with the tile
            setBlockFastest(nativeWorld, position, air, craft.getHitBox());
            setBlockFastest(nativeWorld, locationToPosition(rotatedPosition), state, craft.getHitBox());
        });

          //*******************************************
          //*   Step three: Translate all the blocks  *
          //*******************************************
          // blockedByWater=false means an ocean-going vessel
          //TODO: Simplify
          //TODO: go by chunks
          //TODO: Don't move unnecessary blocks
          //get the blocks and rotate them
          //MovecraftLocation.sendBlockUpdated(craft,sendAir);
          //create the new block

        for (Map.Entry<BlockPos,BlockState> entry : blockData.entrySet()) {
            setBlockFastest(nativeWorld, entry.getKey(), entry.getValue(), craft.getHitBox());
        }


          //*******************************************
          //*    Step four: replace all the tiles     *
          //*******************************************
          //TODO: go by chunks
        for (TileHolder tileHolder : tiles) {
            MovecraftLocation rotatedPosition = MathUtils.rotateVecAxis(rotation,positionToLocation(tileHolder.getTilePosition()),craft.getCruiseDirection());
            moveBlockEntity(nativeWorld, locationToPosition(rotatedPosition), tileHolder.getTile());
        }
      //*******************************************
      //*   Step five: Destroy the leftovers      *
      //*******************************************
      //TODO: add support for pass-through
        
        if (craft.getNotificationPlayer() == null) return;
        processLight(craft.getHitBox(),craft.getWorld());
    }

    @Override
    public void rotateCraft(@NotNull final Craft craft, @NotNull final MovecraftLocation originPoint, @NotNull final MovecraftRotation rotation) {
        //*******************************************
        //*      Step one: Convert to Positions     *
        //*******************************************

        final ServerLevel nativeWorld = ((CraftWorld) craft.getWorld()).getHandle();

        final HashMap<BlockPos, BlockPos> rotatedPositions = new HashMap<>(craft.getHitBox().size(),1.5f);
        final MovecraftRotation counterRotation = rotation == MovecraftRotation.CLOCKWISE ? MovecraftRotation.ANTICLOCKWISE : MovecraftRotation.CLOCKWISE;
        final HashMap<BlockPos, BlockState> blockData = new HashMap<>(craft.getHitBox().size(),1.5f);
        final HashMap<BlockPos, BlockState> redstoneComps = new HashMap<>(128000,1.5f);
        final List<TileHolder> tiles = new ArrayList<>();
        final BlockState air = Blocks.AIR.defaultBlockState();
        //  HashMap<Location, BlockData> sendAir = new HashMap<>(128000,1.5f);
        for (MovecraftLocation newLocation : craft.getHitBox()) {
            rotatedPositions.put(locationToPosition(MathUtils.rotateVec(counterRotation, newLocation.subtract(originPoint)).add(originPoint)), locationToPosition(newLocation));
        }
        for (BlockPos position : rotatedPositions.keySet()) {
            blockData.put(position, nativeWorld.getBlockState(position).rotate(ROTATION[rotation.ordinal()]));
        }
          //*******************************************
          //*         Step two: Get the tiles         *
          //*******************************************
          //get the tiles
        for (BlockPos position : rotatedPositions.keySet()) {
            BlockEntity tile = removeBlockEntity(nativeWorld, position);
            if (tile == null)
                continue;
            //get the nextTick to move with the tile
            tiles.add(new TileHolder(tile, tickProvider.getNextTick(nativeWorld, position), position));
        }

          //*******************************************
          //*   Step three: Translate all the blocks  *
          //*******************************************
          // blockedByWater=false means an ocean-going vessel
          //TODO: Simplify
          //TODO: go by chunks
          //TODO: Don't move unnecessary blocks
          //get the blocks and rotate them
          //MovecraftLocation.sendBlockUpdated(craft,sendAir);
          //create the new block

        for (Map.Entry<BlockPos, BlockState> entry : blockData.entrySet()) {
            setBlockFastest(nativeWorld, rotatedPositions.get(entry.getKey()), entry.getValue(), craft.getHitBox());
            if (isRedstoneComponent(entry.getValue().getBlock())) redstoneComps.put(rotatedPositions.get(entry.getKey()), entry.getValue()); //Determine Redstone Blocks
        }

          //*******************************************
          //*    Step four: replace all the tiles     *
          //*******************************************
          //TODO: go by chunks
        for (TileHolder tileHolder : tiles) {
            rotateBlockEntity(nativeWorld, rotatedPositions.get(tileHolder.getTilePosition()), tileHolder.getTile(), rotation);
            if (tileHolder.getNextTick() == null)
                continue;
            final long currentTime = nativeWorld.getLevelData().getGameTime();
            nativeWorld.getBlockTicks().schedule(new ScheduledTick<>((Block) tileHolder.getNextTick().type(), rotatedPositions.get(tileHolder.getNextTick().pos()), tileHolder.getNextTick().triggerTick() - currentTime, tileHolder.getNextTick().priority(), tileHolder.getNextTick().subTickOrder()));
        }
      //*******************************************
      //*   Step five: Destroy the leftovers      *
      //*******************************************
      //TODO: add support for pass-through
        Collection<BlockPos> deletePositions = CollectionUtils.oldFilter(rotatedPositions.keySet(), rotatedPositions.values());
        for (BlockPos position : deletePositions) {
            setBlockFastest(nativeWorld, position, air, craft.getHitBox());
        }
        if (craft.getNotificationPlayer() == null) return;
        if (craft.getOrigBlockCount()>=256000) return;
        processLight(craft.getHitBox(),craft.getWorld());
        if (craft.getOrigBlockCount()>=25600) return;
        processRedstone(redstoneComps.keySet(), nativeWorld);
    }

    @Override
    public void translateCraft(@NotNull final Craft craft, @NotNull final MovecraftLocation displacement, @NotNull final org.bukkit.World world) {
        //TODO: Add support for rotations
        //A craftTranslateCommand should only occur if the craft is moving to a valid position
        //*******************************************
        //*      Step one: Convert to Positions     *
        //*******************************************
        final ServerLevel oldNativeWorld = ((CraftWorld) craft.getWorld()).getHandle();
        final ServerLevel nativeWorld = ((CraftWorld) world).getHandle();
        final BlockPos translateVector = locationToPosition(displacement);
        final List<BlockPos> positions = new ArrayList<>(craft.getHitBox().size());
        final List<BlockState> blockData = new ArrayList<>();
        final List<BlockPos> redstoneComps = new ArrayList<>();
        final List<BlockPos> newPositions = new ArrayList<>();
        final BlockState air = Blocks.AIR.defaultBlockState();
        craft.getHitBox().forEach((movecraftLocation) -> positions.add(locationToPosition((movecraftLocation)).subtract(translateVector)));
        //*******************************************
        //*         Step two: Get the tiles         *
        //*******************************************
        final List<TileHolder> tiles = new ArrayList<>();
        //get the tiles
        //*******************************************
        //*   Step three: Translate all the blocks  *
        //*******************************************
        // blockedByWater=false means an ocean-going vessel
        //TODO: Simplify
        //TODO: go by chunks
        //TODO: Don't move unnecessary blocks
        //get the blocks and translate the positions
        //HashMap<Location, BlockData> sendAir = new HashMap<>(positions.size())
        for (BlockPos position : positions) {
            BlockState data = oldNativeWorld.getBlockState(position);
            blockData.add(data);
            newPositions.add(position.offset(translateVector));
            BlockEntity tile = removeBlockEntity(oldNativeWorld, position);
            if (tile == null) continue;
            //get the nextTick to move with the tile
            //nativeWorld.capturedTileEntities.remove(position);
            //nativeWorld.getChunkAtWorldCoords(position).getTileEntities().remove(position);
            tiles.add(new TileHolder(tile, tickProvider.getNextTick(oldNativeWorld, position), position));
        }
        //create the new block
        //HashMap<Location, BlockData> sendBlocks = new HashMap<>(newPositions.size());
        //final HashMap<BlockPos, BlockState> fireBlocks = new HashMap<>(128000,1.5f);
        for(int i = 0, positionSize = newPositions.size(); i<positionSize; i++) {
            BlockPos pos = newPositions.get(i);
            BlockState state = blockData.get(i);
            // Add to fire processing list
            setBlockFastest(nativeWorld, pos, state, craft.getHitBox());
            if (isRedstoneComponent(nativeWorld.getBlockState(pos).getBlock())) redstoneComps.add(pos);
            //sendBlockFast(nativeWorld, pos, state);
            //else
            //  setBlockFastest(nativeWorld, pos, state);
            //Location bloc = new Location(craft.getWorld(),pos.getX(),pos.getY(),pos.getZ());
            //sendBlocks.put(new Location(craft.getWorld(),pos.getX(),pos.getY(),pos.getZ()),bloc.getBlock().getBlockData());
        }
        //*******************************************
        //*    Step four: replace all the tiles     *
        //*******************************************
        //TODO: go by chunks
        for (TileHolder tileHolder : tiles) {
            moveBlockEntity(nativeWorld, tileHolder.getTilePosition().offset(translateVector), tileHolder.getTile());
            if (tileHolder.getNextTick() == null)
                continue;
            final long currentTime = nativeWorld.getLevelData().getGameTime();
            nativeWorld.getBlockTicks().schedule(new ScheduledTick<>((Block) tileHolder.getNextTick().type(), tileHolder.getTilePosition().offset(translateVector), tileHolder.getNextTick().triggerTick() - currentTime, tileHolder.getNextTick().priority(), tileHolder.getNextTick().subTickOrder()));
        }
        //MovecraftLocation.sendBlockUpdated(craft,sendBlocks);
        //*******************************************
        //*   Step five: Destroy the leftovers      *
        //*******************************************
        List<BlockPos> deletePositions = positions;
        if (oldNativeWorld == nativeWorld) deletePositions = CollectionUtils.oldFilter(positions,newPositions);
        for (BlockPos position : deletePositions) {
            BlockPos pos = position;

            //Location bloc = new Location(world,pos.getX(),pos.getY(),pos.getZ());
            //if (craft.getOrigBlockCount()>=900000)
            setBlockFastest(oldNativeWorld, position, air, craft.getHitBox());
            //else
            //  setBlockFast(oldNativeWorld, position, Blocks.AIR.defaultBlockState());
            //sendAir.put(bloc,air);
        }
        //MovecraftLocation.sendBlockUpdated(craft,sendAir);

        //*******************************************
        //*      Step six: Process redstone         *
        //*******************************************
        if (craft.getNotificationPlayer() == null) return;
        if (craft.getOrigBlockCount()>=256000) return;
        processLight(craft.getHitBox(),craft.getWorld());
        if (craft.getOrigBlockCount()>=25600) return;
        processRedstone(redstoneComps, nativeWorld);
    }

    @Nullable
    private BlockEntity removeBlockEntity(@NotNull Level world, @NotNull BlockPos position){
        return world.getChunkAt(position).blockEntities.remove(position);
    }

    @NotNull
    public BlockPos locationToPosition(@NotNull MovecraftLocation loc) {
        return new BlockPos(loc.getX(), loc.getY(), loc.getZ());
    }

    @NotNull
    public BlockPos locationToPosition(@NotNull Location loc) {
        return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @NotNull
    public MovecraftLocation positionToLocation(@NotNull BlockPos pos) {
        return new MovecraftLocation(pos.getX(), pos.getY(), pos.getZ());
    }

    public org.bukkit.Chunk getChunkFastest(Location loc) {
        BlockPos pos = locationToPosition(loc);
        ServerLevel world = ((CraftWorld) loc.getWorld()).getHandle();
        LevelChunk chunk = (LevelChunk) world.getChunkAt(pos);
        if (chunk == null) chunk = world.getChunkAt(pos);
        org.bukkit.Chunk bukkitChunk = new CraftChunk(chunk);
        return bukkitChunk;
    }

    private void setBlockFast(@NotNull Level world, @NotNull BlockPos position, @NotNull BlockState data) {
        LevelChunk chunk = (LevelChunk) world.getChunkAt(position);
        if (chunk == null) chunk = world.getChunkAt(position);
        int chunkSection = (position.getY() >> 4) - chunk.getMinSection();
        LevelChunkSection section = chunk.getSections()[chunkSection];
        if (section == null) {
            // Put a GLASS block to initialize the section. It will be replaced next with the real block.
            chunk.setBlockState(position, Blocks.GLASS.defaultBlockState(), false);
            section = chunk.getSections()[chunkSection];
        }
        if (section.getBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15).equals(data)) {
            //Block is already of correct type and data, don't overwrite
            return;
        }
        section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, data);
        world.sendBlockUpdated(position, data, data, 3);
        //world.getLightEngine().checkBlock(position); // boolean corresponds to if chunk section empty
        chunk.setUnsaved(true);
    }

    private void setBlockFastest(@NotNull Level world, @NotNull BlockPos position, @NotNull BlockState data) {
        LevelChunk chunk = (LevelChunk) world.getChunkAt(position);
        if (chunk == null) chunk = world.getChunkAt(position);
        int chunkSection = (position.getY() >> 4) - chunk.getMinSection();
        LevelChunkSection section = chunk.getSections()[chunkSection];
        if (section == null) {
            // Put a GLASS block to initialize the section. It will be replaced next with the real block.
            chunk.setBlockState(position, Blocks.GLASS.defaultBlockState(), false);
            section = chunk.getSections()[chunkSection];
        }
        if (getBlockFastest(world,position).equals(data)) {
            //Block is already of correct type and data, don't overwrite
            return;
        }
        if (chunk.blockEntities.get(position) != null || data.equals(Blocks.AIR.defaultBlockState())) {
            section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, data);
          //  ((ServerLevel)world).getChunkSource().blockChanged(position);
            //((ServerLevel)world).sendBlockUpdated(position, data, data, 3);
            ((ServerLevel)world).getChunkSource().blockChanged(position);
        } else {
            section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, data);
          //  ((ServerLevel)world).getChunkSource().blockChanged(position);
            //((ServerLevel)world).sendBlockUpdated(position, data, data, 1);
            ((ServerLevel)world).getChunkSource().blockChanged(position);
        }
        chunk.setUnsaved(true);
    }

    private void setBlockFastest(@NotNull final Level world, @NotNull final BlockPos position, @NotNull final BlockState data, @NotNull HitBox box) {
        LevelChunk chunk = (LevelChunk) world.getChunkAt(position);
        if (chunk == null) chunk = world.getChunkAt(position);
        int chunkSection = (position.getY() >> 4) - chunk.getMinSection();
        LevelChunkSection section = chunk.getSections()[chunkSection];
        if (section == null) {
            // Put a GLASS block to initialize the section. It will be replaced next with the real block.
            chunk.setBlockState(position, Blocks.GLASS.defaultBlockState(), false);
            section = chunk.getSections()[chunkSection];
        }
        if (getBlockFastest(world,position).equals(data)) {
            //Block is already of correct type and data, don't overwrite
            return;
        }
        if (chunk.blockEntities.get(position) != null || data.equals(Blocks.AIR.defaultBlockState())) {
            section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, data);
          //  ((ServerLevel)world).getChunkSource().blockChanged(position);
            //((ServerLevel)world).sendBlockUpdated(position, data, data, 3);
            ((ServerLevel)world).getChunkSource().blockChanged(position);
        } else {
            section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, data);
          //  ((ServerLevel)world).getChunkSource().blockChanged(position);
            //((ServerLevel)world).sendBlockUpdated(position, data, data, 1);
            ((ServerLevel)world).getChunkSource().blockChanged(position);
        }
        chunk.setUnsaved(true);
    }
    @Nullable
    public BlockState getNMSBlockFast(@NotNull MovecraftLocation location, World world) {
        if (world == null)
            return null;
        ServerLevel level = ((CraftWorld) world).getHandle();
        BlockPos position = new BlockPos(location.getX(), location.getY(), location.getZ());
        return getBlockFastest(level,position);
    }

    @Nullable
    public BlockState getBlockFastest(@NotNull Level world, @NotNull BlockPos position) {
        LevelChunk chunk = (LevelChunk) world.getChunkAt(position);
        if (chunk == null) chunk = world.getChunkAt(position);
        int chunkSection = (position.getY() >> 4) - chunk.getMinSection();
        LevelChunkSection section = chunk.getSections()[chunkSection];
        BlockState data = section.getBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
        return data;
    }
    @Nullable
    public org.bukkit.Material toBukkitBlockFast(@NotNull Location location) {
        if (location.getWorld() == null)
            return null;
        ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();
        BlockPos position = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockState data = getBlockFastest(world,position);
        if (data == null) return Material.AIR;
        return CraftMagicNumbers.getMaterial(data.getBlock());
    }
    @Nullable
    public org.bukkit.Material toBukkitBlockFast(@NotNull MovecraftLocation location, World world) {
        if (world == null)
            return null;
        ServerLevel level = ((CraftWorld) world).getHandle();
        BlockPos position = new BlockPos(location.getX(), location.getY(), location.getZ());
        BlockState data = getBlockFastest(level,position);
        if (data == null) return Material.AIR;
        return CraftMagicNumbers.getMaterial(data.getBlock());
    }
    @Nullable
    public org.bukkit.block.Block getBukkitBlockFast(@NotNull Location location) {
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        BlockPos position = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        LevelChunk chunk = (LevelChunk) level.getChunkIfLoaded(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) chunk = level.getChunkAt(position);
        org.bukkit.block.Block block = (org.bukkit.block.Block)CraftBlock.at(level, position);
        if (block == null) block = location.getBlock();
        return block;
    }
    @Nullable
    public org.bukkit.block.Block getBukkitBlockFast(@NotNull MovecraftLocation location, World world) {
        if (world == null)
            return null;
        ServerLevel level = ((CraftWorld) world).getHandle();
        BlockPos position = new BlockPos(location.getX(), location.getY(), location.getZ());
        LevelChunk chunk = (LevelChunk) level.getChunkIfLoaded(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) chunk = level.getChunkAt(position);
        org.bukkit.block.Block block = (org.bukkit.block.Block)CraftBlock.at(level, position);
        if (block == null) block = location.toBukkit(world).getBlock();
        return block;
    }


    
    @Override
    public void setBlockFast(@NotNull Location location, @NotNull Material mat){
        Level world = ((CraftWorld) (location.getWorld())).getHandle();
        setBlockFast(location, mat.createBlockData());
    }

    public void processRedstone(Collection<BlockPos> redstone, Level world) {
        if (redstone.size() >= 25600) return;
        for (final BlockPos pos : redstone) {
            BlockState data = getBlockFastest(world,pos);
            world.neighborChanged(data,pos,data.getBlock(),pos,false);
            world.sendBlockUpdated(pos, data, data, 3);
            if (isToggleableRedstoneComponent(data.getBlock())) {
                data.getBlock().tick(data,(ServerLevel)world,pos,((ServerLevel)world).random);
            }
        }
    }
    @Override
    public @Nullable Location getAccessLocation(@NotNull InventoryView inventoryView) {
        AbstractContainerMenu menu = null;
        try {
            menu = ((CraftInventoryView) inventoryView).getHandle();
        } catch (Exception exc) {}
        Field field = UnsafeUtils.getFieldOfType(ContainerLevelAccess.class, menu.getClass());
        if (field != null) {
            try {
                field.setAccessible(true);
                return ((ContainerLevelAccess) field.get(menu)).getLocation();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public void setAccessLocation(@NotNull InventoryView inventoryView, @NotNull Location location) {
        if (location.getWorld() == null)
            return;
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        BlockPos position = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        ContainerLevelAccess access = ContainerLevelAccess.create(level, position);

        AbstractContainerMenu menu = null;
        try {
            menu = ((CraftInventoryView) inventoryView).getHandle();
        } catch (Exception exc) {}
        if (menu == null) return;
        UnsafeUtils.trySetFieldOfType(ContainerLevelAccess.class, menu, access);
    }

    private void processFireSpread(final HashMap<BlockPos, BlockState> fireStates, ServerLevel world) {
        for (var entry: fireStates.entrySet()) {
            BlockState state = entry.getValue();
            if (state.getBlock() instanceof FireBlock fireBlock) {
                fireBlock.tick(state, world, entry.getKey(), world.random);
            }
        }
    }

    @Override
    public void setBlockFast(@NotNull Location location, @NotNull BlockData data){
        setBlockFast(location, MovecraftRotation.NONE, data);
    }

    public void setBlockFast(@NotNull World world, @NotNull MovecraftLocation location, @NotNull BlockData data){
        setBlockFast(world, location, MovecraftRotation.NONE, data);
    }

    public void setBlockFast(@NotNull World world, @NotNull MovecraftLocation location, @NotNull MovecraftRotation rotation, @NotNull BlockData data) {
        BlockState blockData;
        if(data instanceof CraftBlockData){
            blockData = ((CraftBlockData) data).getState();
        } else {
            blockData = (BlockState) data;
        }
        blockData = blockData.rotate(ROTATION[rotation.ordinal()]);
        Level nmsWorld = ((CraftWorld)(world)).getHandle();
        BlockPos BlockPos = locationToPosition(location);
        setBlockFastest(nmsWorld, BlockPos, blockData);
    }

    @Override
    public void setBlockFast(@NotNull Location location, @NotNull MovecraftRotation rotation, @NotNull BlockData data) {
        BlockState blockData;
        if (data instanceof CraftBlockData) {
            blockData = ((CraftBlockData) data).getState();
        }
        else {
            blockData = (BlockState) data;
        }
        blockData = blockData.rotate(ROTATION[rotation.ordinal()]);
        Level world = ((CraftWorld) (location.getWorld())).getHandle();
        BlockPos BlockPos = locationToPosition(MathUtils.bukkit2MovecraftLoc(location));
        setBlockFastest(world, BlockPos, blockData);
    }

    @Override
    public void disableShadow(@NotNull Material type) {
        // Disabled
    }
    @Override
    public void processLight(HitBox hitBox, @NotNull World world) {
        long delay = 30L;
        if (hitBox.size() >= 12000)
            delay += 60;
        if (hitBox.size() >= 20000)
            delay += 120;
        if (hitBox.size() >= 40000)
            delay += 240;
        if (hitBox.size() >= 128000)
            return;
        new BukkitRunnable() {
            @Override
            public void run() {
                ServerLevel nativeWorld = ((CraftWorld) world).getHandle();
                for (MovecraftLocation loc: hitBox) {
                    nativeWorld.getLightEngine().checkBlock(locationToPosition(loc));
                }
            }
        }.runTaskLater(PLUGIN, (delay*20));
    }


    private Field getField(String name) {
        try {
            var field = ServerGamePacketListenerImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (NoSuchFieldException ex) {
            System.out.println("Failed to find field " + name);
            return null;
        }
    }

    private final Field justTeleportedField = getField("justTeleported");
    private final Field awaitingPositionFromClientField = getField("B");
    private final Field lastPosXField = getField("lastPosX");
    private final Field lastPosYField = getField("lastPosY");
    private final Field lastPosZField = getField("lastPosZ");
    private final Field awaitingTeleportField = getField("C");
    private final Field awaitingTeleportTimeField = getField("D");
    private final Field aboveGroundVehicleTickCountField = getField("H");

    private boolean isRedstoneComponent(Block block) {
        return block instanceof RedStoneWireBlock ||
                block instanceof FurnaceBlock ||
                block instanceof BlastFurnaceBlock ||
                block instanceof SmokerBlock ||
                block instanceof DiodeBlock ||
                block instanceof TargetBlock ||
                block instanceof PressurePlateBlock ||
                block instanceof ButtonBlock ||
                block instanceof BasePressurePlateBlock ||
                block instanceof LeverBlock ||
                block instanceof HopperBlock ||
                block instanceof ObserverBlock ||
                block instanceof DaylightDetectorBlock ||
                block instanceof DispenserBlock ||
                block instanceof DropperBlock ||
                block instanceof RedstoneLampBlock ||
                block instanceof RedstoneTorchBlock ||
                block instanceof ComparatorBlock ||
                block instanceof SculkSensorBlock ||
                block instanceof PistonBaseBlock ||
                block instanceof MovingPistonBlock ||
                block instanceof CopperBulbBlock ||
                block instanceof CrafterBlock;
    }
    private boolean isToggleableRedstoneComponent(Block block) {
        return block instanceof PressurePlateBlock ||
                block instanceof ButtonBlock ||
                block instanceof BasePressurePlateBlock ||
                block instanceof LeverBlock ||
                block instanceof HopperBlock;
    }

    private void moveBlockEntity(@NotNull Level nativeWorld, @NotNull BlockPos newPosition, @NotNull BlockEntity tile) {
        LevelChunk chunk = nativeWorld.getChunkAt(newPosition);
        try {
            var positionField = BlockEntity.class.getDeclaredField("p"); // o is obfuscated worldPosition
            UnsafeUtils.setField(positionField, tile, newPosition);
        }
        catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        tile.setLevel(nativeWorld);
        tile.clearRemoved();
        if (nativeWorld.captureBlockStates) {
            nativeWorld.capturedTileEntities.put(newPosition, tile);
            return;
        }
        final BlockState data = tile.getBlockState();
        setBlockFastest(nativeWorld, newPosition, data);
        chunk.setBlockEntity(tile);
        chunk.blockEntities.put(newPosition, tile);
    }


    private void rotateBlockEntity(@NotNull Level nativeWorld, @NotNull BlockPos newPosition, @NotNull BlockEntity tile, @NotNull MovecraftRotation rotation) {
        LevelChunk chunk = nativeWorld.getChunkAt(newPosition);
        try {
            var positionField = BlockEntity.class.getDeclaredField("p"); // o is obfuscated worldPosition
            UnsafeUtils.setField(positionField, tile, newPosition);
        }
        catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        tile.setLevel(nativeWorld);
        tile.clearRemoved();
        if (nativeWorld.captureBlockStates) {
            nativeWorld.capturedTileEntities.put(newPosition, tile);
            return;
        }
        tile.setBlockState(tile.getBlockState().rotate(ROTATION[rotation.ordinal()]));
        final BlockState data = tile.getBlockState();
        setBlockFastest(nativeWorld, newPosition, data);
        chunk.setBlockEntity(tile);
        chunk.blockEntities.put(newPosition, tile);
    }
    private static class TileHolder {
        @NotNull
        private final BlockEntity tile;
        @Nullable
        private final ScheduledTick<?> nextTick;
        @NotNull
        private final BlockPos tilePosition;

        public TileHolder(@NotNull BlockEntity tile, @Nullable ScheduledTick<?> nextTick, @NotNull BlockPos tilePosition) {
            this.tile = tile;
            this.nextTick = nextTick;
            this.tilePosition = tilePosition;
        }


        @NotNull
        public BlockEntity getTile() {
            return tile;
        }

        @Nullable
        public ScheduledTick<?> getNextTick() {
            return nextTick;
        }

        @NotNull
        public BlockPos getTilePosition() {
            return tilePosition;
        }
    }
}