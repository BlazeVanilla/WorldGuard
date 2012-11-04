// $Id$
/*
 * WorldGuard
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit;

import static com.sk89q.worldguard.bukkit.BukkitUtil.toVector;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;

import com.sk89q.rulelists.KnownAttachment;
import com.sk89q.rulelists.RuleSet;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.blacklist.events.BlockBreakBlacklistEvent;
import com.sk89q.worldguard.blacklist.events.BlockPlaceBlacklistEvent;
import com.sk89q.worldguard.blacklist.events.DestroyWithBlacklistEvent;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;

/**
 * The listener for block events.
 */
public class WorldGuardBlockListener implements Listener {

    private WorldGuardPlugin plugin;

    public WorldGuardBlockListener(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerEvents() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        Block blockDamaged = event.getBlock();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Cake are damaged and not broken when they are eaten, so we must
        // handle them a bit separately
        if (blockDamaged.getTypeId() == BlockID.CAKE_BLOCK) {
            // Regions
            if (!plugin.getGlobalRegionManager().canBuild(player, blockDamaged)) {
                player.sendMessage(ChatColor.DARK_RED + "You're not invited to this tea party!");
                event.setCancelled(true);
                return;
            }

            // RuleLists
            RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_INTERACT);
            BukkitContext context = new BukkitContext(event);
            context.setSourceEntity(player);
            context.setTargetBlock(event.getBlock().getState());
            if (rules.process(context)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (!plugin.getGlobalRegionManager().canBuild(player, event.getBlock())
         || !plugin.getGlobalRegionManager().canConstruct(player, event.getBlock())) {
            player.sendMessage(ChatColor.DARK_RED + "You don't have permission for this area.");
            event.setCancelled(true);
            return;
        }

        // Blacklist
        if (wcfg.getBlacklist() != null) {
            if (!wcfg.getBlacklist().check(
                    new BlockBreakBlacklistEvent(plugin.wrapPlayer(player),
                    toVector(event.getBlock()),
                    event.getBlock().getTypeId()), false, false)) {
                event.setCancelled(true);
                return;
            }

            if (!wcfg.getBlacklist().check(
                    new DestroyWithBlacklistEvent(plugin.wrapPlayer(player),
                    toVector(event.getBlock()),
                    player.getItemInHand().getTypeId()), false, false)) {
                event.setCancelled(true);
                return;
            }
        }

        // Chest protection
        if (wcfg.isChestProtected(event.getBlock(), player)) {
            player.sendMessage(ChatColor.DARK_RED + "The chest is protected.");
            event.setCancelled(true);
            return;
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_BREAK);
        BukkitContext context = new BukkitContext(event);
        context.setSourceEntity(player);
        context.setTargetBlock(event.getBlock().getState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }

        /* --- No short-circuit returns below this line --- */

        // Sponges
        SpongeApplicator spongeAppl = wcfg.getSpongeApplicator();
        if (spongeAppl != null && block.getType() == Material.SPONGE) {
            if (spongeAppl.isActiveSponge(block)) {
                spongeAppl.placeWater(block);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block blockFrom = event.getBlock();
        Block blockTo = event.getToBlock();

        boolean isWater = blockFrom.getTypeId() == 8 || blockFrom.getTypeId() == 9;
        boolean isLava = blockFrom.getTypeId() == 10 || blockFrom.getTypeId() == 11;

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (wcfg.highFreqFlags && isWater
                && !plugin.getGlobalRegionManager().allows(DefaultFlag.WATER_FLOW,
                blockFrom.getLocation())) {
            event.setCancelled(true);
            return;
        }

        if (wcfg.highFreqFlags && isLava
                && !plugin.getGlobalRegionManager().allows(DefaultFlag.LAVA_FLOW,
                blockFrom.getLocation())) {
            event.setCancelled(true);
            return;
        }

        // Sponges
        SpongeApplicator spongeAppl = wcfg.getSpongeApplicator();
        if (spongeAppl != null && isWater) {
            if (spongeAppl.isNearSponge(blockTo)) {
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_SPREAD);
        BukkitContext context = new BukkitContext(event);
        context.setSourceBlock(event.getBlock().getState());
        context.setTargetBlock(event.getToBlock().getState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        IgniteCause cause = event.getCause();
        Block block = event.getBlock();
        World world = block.getWorld();
        boolean isFireSpread = cause == IgniteCause.SPREAD;

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(world);

        // Fire stop toggle
        if (wcfg.fireSpreadDisableToggle && isFireSpread) {
            event.setCancelled(true);
            return;
        }

        // Regions
        if (wcfg.useRegions) {
            Vector pt = toVector(block);
            Player player = event.getPlayer();
            RegionManager mgr = plugin.getGlobalRegionManager().get(world);
            ApplicableRegionSet set = mgr.getApplicableRegions(pt);

            if (player != null && !plugin.getGlobalRegionManager().hasBypass(player, world)) {
                LocalPlayer localPlayer = plugin.wrapPlayer(player);

                if (cause == IgniteCause.FLINT_AND_STEEL || cause == IgniteCause.FIREBALL) {
                    if (!set.canBuild(localPlayer)) {
                        event.setCancelled(true);
                        return;
                    }
                    if (!set.allows(DefaultFlag.LIGHTER, localPlayer)
                            && !plugin.hasPermission(player, "worldguard.override.lighter")) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }

            if (wcfg.highFreqFlags && isFireSpread
                    && !set.allows(DefaultFlag.FIRE_SPREAD)) {
                event.setCancelled(true);
                return;
            }

            if (wcfg.highFreqFlags && cause == IgniteCause.LAVA
                    && !set.allows(DefaultFlag.LAVA_FIRE)) {
                event.setCancelled(true);
                return;
            }

            if (cause == IgniteCause.LIGHTNING && !set.allows(DefaultFlag.LIGHTNING)) {
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules;
        BukkitContext context;
        BlockState placedState;

        switch (event.getCause()) {
        case FLINT_AND_STEEL:
            // Consider flint and steel as an item use
            rules = wcfg.getRuleList().get(KnownAttachment.ITEM_USE);
            context = new BukkitContext(event);
            context.setSourceEntity(event.getPlayer());
            context.setTargetBlock(event.getBlock().getState());
            context.setItem(event.getPlayer().getItemInHand()); // Should be flint and steel

            // Make a virtual new state
            placedState = event.getBlock().getState();
            placedState.setType(Material.FIRE);
            context.setPlacedBlock(placedState);

            if (rules.process(context)) {
                event.setCancelled(true);
                return;
            }
            break;
        case LAVA:
        case SPREAD:
            // Consider everything else as a block spread
            rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_SPREAD);
            context = new BukkitContext(event);
            context.setTargetBlock(event.getBlock().getState());

            // Make a virtual source state
            BlockState sourceState = event.getBlock().getState();
            sourceState.setType(event.getCause() == IgniteCause.LAVA ? Material.LAVA : Material.FIRE);
            context.setSourceBlock(sourceState);

            // Make a virtual new state
            placedState = event.getBlock().getState();
            placedState.setType(Material.FIRE);
            context.setPlacedBlock(placedState);

            if (rules.process(context)) {
                event.setCancelled(true);
                return;
            }
            break;
        case FIREBALL:
        case LIGHTNING:
            // Consider everything else as a block spread
            rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_PLACE);
            context = new BukkitContext(event);
            context.setTargetBlock(event.getBlock().getState());

            // Make a virtual new state
            placedState = event.getBlock().getState();
            placedState.setType(Material.FIRE);
            context.setPlacedBlock(placedState);

            if (rules.process(context)) {
                event.setCancelled(true);
                return;
            }
            break;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Fire stop toggle
        if (wcfg.fireSpreadDisableToggle) {
            Block block = event.getBlock();
            event.setCancelled(true);
            checkAndDestroyAround(block.getWorld(), block.getX(), block.getY(), block.getZ(), BlockID.FIRE);
            return;
        }

        // Chest protection
        if (wcfg.isChestProtected(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        // Regions
        if (wcfg.useRegions) {
            Block block = event.getBlock();
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            Vector pt = toVector(block);
            RegionManager mgr = plugin.getGlobalRegionManager().get(block.getWorld());
            ApplicableRegionSet set = mgr.getApplicableRegions(pt);

            if (!set.allows(DefaultFlag.FIRE_SPREAD)) {
                checkAndDestroyAround(block.getWorld(), x, y, z, BlockID.FIRE);
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_BREAK);
        BukkitContext context = new BukkitContext(event);
        BlockState virtualFireState = event.getBlock().getState();
        virtualFireState.setType(Material.FIRE);
        context.setSourceBlock(virtualFireState);
        context.setTargetBlock(event.getBlock().getState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    private void checkAndDestroyAround(World world, int x, int y, int z, int required) {
        checkAndDestroy(world, x, y, z + 1, required);
        checkAndDestroy(world, x, y, z - 1, required);
        checkAndDestroy(world, x, y + 1, z, required);
        checkAndDestroy(world, x, y - 1, z, required);
        checkAndDestroy(world, x + 1, y, z, required);
        checkAndDestroy(world, x - 1, y, z, required);
    }

    private void checkAndDestroy(World world, int x, int y, int z, int required) {
        if (world.getBlockTypeIdAt(x, y, z) == required) {
            world.getBlockAt(x, y, z).setTypeId(BlockID.AIR);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_PHYSICS);
        BukkitContext context = new BukkitContext(event);
        context.setTargetBlock(event.getBlock().getState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }

        /* --- No short-circuit returns below this line --- */

        // Sponges
        SpongeApplicator spongeAppl = wcfg.getSpongeApplicator();
        if (spongeAppl != null && block.getType() == Material.SPONGE) {
            if (spongeAppl.isActiveSponge(block)) {
                spongeAppl.placeWater(block);
            } else {
                spongeAppl.clearWater(block);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block blockPlaced = event.getBlock();
        Player player = event.getPlayer();
        World world = blockPlaced.getWorld();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(world);

        // Regions
        if (wcfg.useRegions) {
            final Location location = blockPlaced.getLocation();
            if (!plugin.getGlobalRegionManager().canBuild(player, location)
             || !plugin.getGlobalRegionManager().canConstruct(player, location)) {
                player.sendMessage(ChatColor.DARK_RED + "You don't have permission for this area.");
                event.setCancelled(true);
                return;
            }
        }

        // Blacklist
        if (wcfg.getBlacklist() != null) {
            if (!wcfg.getBlacklist().check(
                    new BlockPlaceBlacklistEvent(plugin.wrapPlayer(player), toVector(blockPlaced),
                    blockPlaced.getTypeId()), false, false)) {
                event.setCancelled(true);
                return;
            }
        }

        // Chest protection
        if (wcfg.signChestProtection && wcfg.getChestProtection().isChest(blockPlaced.getTypeId())) {
            if (wcfg.isAdjacentChestProtected(event.getBlock(), player)) {
                player.sendMessage(ChatColor.DARK_RED + "This spot is for a chest that you don't have permission for.");
                event.setCancelled(true);
                return;
            }
        }

        // Sponges
        SpongeApplicator spongeAppl = wcfg.getSpongeApplicator();
        if (spongeAppl != null && spongeAppl.isActiveSponge(blockPlaced)) {
            spongeAppl.clearWater(blockPlaced);
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_PLACE);
        BukkitContext context = new BukkitContext(event);
        context.setTargetBlock(event.getBlock().getState());
        context.setPlacedBlock(event.getBlockReplacedState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(player.getWorld());

        // Chest protection
        if (wcfg.signChestProtection) {
            if (event.getLine(0).equalsIgnoreCase("[Lock]")) {
                if (wcfg.isChestProtectedPlacement(event.getBlock(), player)) {
                    player.sendMessage(ChatColor.DARK_RED + "You do not own the adjacent chest.");
                    event.getBlock().breakNaturally();
                    event.setCancelled(true);
                    return;
                }

                if (event.getBlock().getTypeId() != BlockID.SIGN_POST) {
                    player.sendMessage(ChatColor.RED
                            + "The [Lock] sign must be a sign post, not a wall sign.");

                    event.getBlock().breakNaturally();
                    event.setCancelled(true);
                    return;
                }

                if (!event.getLine(1).equalsIgnoreCase(player.getName())) {
                    player.sendMessage(ChatColor.RED
                            + "The first owner line must be your name.");

                    event.getBlock().breakNaturally();
                    event.setCancelled(true);
                    return;
                }

                int below = event.getBlock().getRelative(0, -1, 0).getTypeId();

                if (below == BlockID.TNT || below == BlockID.SAND
                        || below == BlockID.GRAVEL || below == BlockID.SIGN_POST) {
                    player.sendMessage(ChatColor.RED
                            + "That is not a safe block that you're putting this sign on.");

                    event.getBlock().breakNaturally();
                    event.setCancelled(true);
                    return;
                }

                event.setLine(0, "[Lock]");
                player.sendMessage(ChatColor.YELLOW
                        + "A chest or double chest above is now protected.");
            }
        } else if (!wcfg.disableSignChestProtectionCheck) {
            if (event.getLine(0).equalsIgnoreCase("[Lock]")) {
                player.sendMessage(ChatColor.RED
                        + "WorldGuard's sign chest protection is disabled.");

                event.getBlock().breakNaturally();
                event.setCancelled(true);
                return;
            }
        }

        if (!plugin.getGlobalRegionManager().canBuild(player, event.getBlock())) {
            player.sendMessage(ChatColor.DARK_RED + "You don't have permission for this area.");
            event.setCancelled(true);
            return;
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_INTERACT);
        BukkitContext context = new BukkitContext(event);
        context.setTargetBlock(event.getBlock().getState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (wcfg.useRegions) {
            if (!plugin.getGlobalRegionManager().allows(DefaultFlag.LEAF_DECAY,
                    event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_FADE);
        BukkitContext context = new BukkitContext(event);
        context.setTargetBlock(event.getBlock().getState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        int type = event.getNewState().getTypeId();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (type == BlockID.ICE) {
            if (wcfg.useRegions && !plugin.getGlobalRegionManager().allows(
                    DefaultFlag.ICE_FORM, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        if (type == BlockID.SNOW) {
            if (wcfg.useRegions && !plugin.getGlobalRegionManager().allows(
                    DefaultFlag.SNOW_FALL, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_FORM);
        BukkitContext context = new BukkitContext(event);
        context.setTargetBlock(event.getBlock().getState());
        context.setPlacedBlock(event.getNewState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        int fromType = event.getSource().getTypeId();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (fromType == BlockID.RED_MUSHROOM || fromType == BlockID.BROWN_MUSHROOM) {
            if (wcfg.useRegions && !plugin.getGlobalRegionManager().allows(
                    DefaultFlag.MUSHROOMS, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        if (fromType == BlockID.GRASS) {
            if (wcfg.useRegions && !plugin.getGlobalRegionManager().allows(
                    DefaultFlag.GRASS_SPREAD, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_SPREAD);
        BukkitContext context = new BukkitContext(event);
        context.setSourceBlock(event.getSource().getState());
        context.setTargetBlock(event.getBlock().getState());
        context.setPlacedBlock(event.getNewState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        int type = event.getBlock().getTypeId();

        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // regions
        if (type == BlockID.ICE) {
            if (wcfg.useRegions && !plugin.getGlobalRegionManager().allows(
                    DefaultFlag.ICE_MELT, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        if (type == BlockID.SNOW) {
            if (wcfg.useRegions && !plugin.getGlobalRegionManager().allows(
                    DefaultFlag.SNOW_MELT, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.BLOCK_FADE);
        BukkitContext context = new BukkitContext(event);
        context.setTargetBlock(event.getBlock().getState());
        context.setPlacedBlock(event.getNewState());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (wcfg.useRegions) {
            if (!plugin.getGlobalRegionManager().allows(DefaultFlag.PISTONS, event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }

            for (Block block : event.getBlocks()) {
                if (!plugin.getGlobalRegionManager().allows(DefaultFlag.PISTONS, block.getLocation())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // Regions
        if (wcfg.useRegions && event.isSticky()) {
            if (!(plugin.getGlobalRegionManager().allows(DefaultFlag.PISTONS, event.getRetractLocation()))
                    || !(plugin.getGlobalRegionManager().allows(DefaultFlag.PISTONS, event.getBlock().getLocation()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        ConfigurationManager cfg = plugin.getGlobalStateManager();
        WorldConfiguration wcfg = cfg.get(event.getBlock().getWorld());

        // RuleLists
        RuleSet rules = wcfg.getRuleList().get(KnownAttachment.ITEM_DROP);
        BukkitContext context = new BukkitContext(event);
        context.setSourceBlock(event.getBlock().getState());
        context.setItem(event.getItem());
        if (rules.process(context)) {
            event.setCancelled(true);
            return;
        }
    }
}
