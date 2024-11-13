package net.countercraft.movecraft.util;


import com.google.common.collect.*;
import java.util.*;


import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.craft.PlayerCraftImpl;
import net.countercraft.movecraft.craft.BaseCraft;
import net.countercraft.movecraft.craft.SubCraft;
import net.countercraft.movecraft.craft.SinkingCraft;
import net.countercraft.movecraft.craft.NPCCraftImpl;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.MovecraftChunk;
import net.countercraft.movecraft.WorldHandler;
import net.countercraft.movecraft.processing.CachedMovecraftWorld;
import net.countercraft.movecraft.processing.MovecraftWorld;
import net.countercraft.movecraft.processing.WorldManager;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.countercraft.movecraft.util.MathUtils;
import net.countercraft.movecraft.util.DirectionalUtils;
import net.countercraft.movecraft.config.Settings;

import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.ExplosionResult;

import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.meta.*;
import org.bukkit.inventory.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ItemUtils {
  protected static final ItemStack airItem = new ItemStack(Material.AIR,0);

  public static ItemStack reduceItemStack(ItemStack stack, int amntChange) {
    final ItemMeta itemMeta = stack.getItemMeta();
    if (stack.getAmount() > 1) {
        int amount = stack.getAmount();
        amntChange *= -1;
        if (amount + amntChange > 0) {
            stack.setAmount(amount + amntChange);
            if (isPowerItem(stack)) {
                if ((itemMeta instanceof org.bukkit.inventory.meta.Damageable damageable) && damageable.hasDamageValue()) {
                    damageable.resetDamage();
                }
                stack.setItemMeta(itemMeta);
            }
        } else {
            stack = airItem;
        }
    } else {
            stack = airItem;
    }
    return stack;
  }

  public static boolean updateItemDurability(ItemStack stack, int amntChange) {
    final ItemMeta itemMeta = stack.getItemMeta();
    if (!(itemMeta instanceof org.bukkit.inventory.meta.Damageable damageable) || stack.getAmount() > 1 || (isPowerItem(stack))) {
        stack = reduceItemStack(stack,amntChange);
        if (stack.getAmount() < amntChange) {
            stack = airItem;
        }
        if (stack.getType() == Material.AIR || stack.getAmount() == 0) return false;
        return true;
    } else {
        if (damageable.hasDamageValue() || damageable.hasMaxDamage()) {
            int durability = damageable.getDamage();
            int maxDurability = 0;
            if (damageable.hasMaxDamage()) maxDurability = damageable.getMaxDamage();
            else maxDurability = stack.getType().getMaxDurability();
            if (maxDurability > durability + amntChange) {
                damageable.setDamage(durability + amntChange);
                stack.setItemMeta(damageable);
                return true;
            } else {
                damageable.setDamage(maxDurability - 5);
                stack.setItemMeta(damageable);
            }
        }
    }
    return false;
  }

  public static ItemStack updateShieldDurabilityAndReturn(ItemStack stack, int amntChange) {
    final ItemMeta itemMeta = stack.getItemMeta();
    if (stack.getType() == Material.PRISMARINE_CRYSTALS && stack.getAmount() > 0) {
        stack = reduceItemStack(stack,amntChange);
        if (stack.getAmount() < amntChange) {
            stack = airItem;
        }
        if (stack.getType() == Material.AIR || stack.getAmount() == 0) return null;
        return stack;
    } else {
        if (itemMeta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            if (damageable.hasDamageValue() || damageable.hasMaxDamage()) {
                int durability = damageable.getDamage();
                int maxDurability = 0;
                if (damageable.hasMaxDamage()) maxDurability = damageable.getMaxDamage();
                else maxDurability = stack.getType().getMaxDurability();
                if (maxDurability > durability + amntChange) {
                    damageable.setDamage(durability + amntChange);
                    stack.setItemMeta(damageable);
                } else {
                    damageable.setDamage(maxDurability - 5);
                    stack.setItemMeta(damageable);
                }
            }
            return stack;

        }
    }
    return null;
  }

  public static boolean isShieldFuelItem(ItemStack i1) {
        if (i1.getType() == Material.PRISMARINE_CRYSTALS) return true;
        if (i1.getItemMeta().hasCustomModelData()) {
            if (i1.getType() == Material.IRON_HOE || i1.getType() == Material.NETHERITE_HOE) {
                if (i1.getItemMeta().getCustomModelData() == 1) {
                    return true;
                }
            }
        }
        return false;
  }

  public static boolean isPowerItem(ItemStack i1) {
        if (i1.getItemMeta().hasCustomModelData()) {
            if (i1.getType() == Material.GLOWSTONE_DUST) {
                if (i1.getItemMeta().getCustomModelData() == 1) {
                    return true;
                }
            }
        }
        return false;
  }
  public static boolean isSimilar(ItemStack i1, ItemStack i2) {
        if (i1 == null) return false;
        if (i2 == null) return false;
        if (i1.getType() == i2.getType()) {
            if (i1.hasItemMeta() && i2.hasItemMeta()) {
                if (i1.getItemMeta().hasCustomModelData() && i2.getItemMeta().hasCustomModelData()) {
                    if (i1.getItemMeta().getCustomModelData() == i2.getItemMeta().getCustomModelData()) return true;
                }
            } else {
                if (!i1.hasItemMeta() && !i2.hasItemMeta()) {
                    return true;
                }
            }
        }
        if (isPowerItem(i1) && isPowerItem(i2)) return true;
        return false;
    }
}