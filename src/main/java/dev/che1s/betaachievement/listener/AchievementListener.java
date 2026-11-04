package dev.che1s.betaachievement.listener;

import dev.che1s.betaachievement.BetaAchievement;
import dev.che1s.betaachievement.achievement.AchievementTriggers;
import net.minecraft.server.Container;
import net.minecraft.server.ContainerFurnace;
import net.minecraft.server.ContainerWorkbench;
import net.minecraft.server.EntityGhast;
import net.minecraft.server.EntityMonster;
import net.minecraft.server.EntitySlime;
import net.minecraft.server.Packet102WindowClick;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.packet.PacketReceivedEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Legacy-Poseidon event bridge for server-side achievement triggers. */
public final class AchievementListener implements Listener {
    private final BetaAchievement plugin;
    private final Map<UUID, Location> railStarts = new HashMap<UUID, Location>();
    private final Map<Integer, AttackRecord> lastAttackers = new HashMap<Integer, AttackRecord>();

    private static final class AttackRecord {
        final Player player;
        final long time;

        AttackRecord(Player player) {
            this.player = player;
            this.time = System.currentTimeMillis();
        }
    }

    public AchievementListener(BetaAchievement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void pickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        plugin.getAchievements().unlock(player, "openInventory", false);
        int itemId = event.getItem().getItemStack().getTypeId();
        if (itemId == 17) {
            plugin.getAchievements().unlock(player, "mineWood", false);
        }
        if (itemId == 334) {
            plugin.getAchievements().unlock(player, "killCow", false);
        }
    }

    @EventHandler
    public void packet(PacketReceivedEvent event) {
        if (!(event.getPacket() instanceof Packet102WindowClick) || event.getPlayer() == null) {
            return;
        }
        Packet102WindowClick click = (Packet102WindowClick) event.getPacket();
        CraftPlayer player = (CraftPlayer) event.getPlayer();
        Container container = player.getHandle().activeContainer;
        if (container instanceof ContainerWorkbench && click.b == 0 && click.e != null) {
            crafted(event.getPlayer(), click.e.id);
        } else if (container instanceof ContainerFurnace && click.b == 2 && click.e != null) {
            furnace(event.getPlayer(), click.e.id);
        }
    }

    private void crafted(Player player, int itemId) {
        if (itemId == 58) {
            grant(player, "buildWorkBench");
        } else if (AchievementTriggers.isPickaxe(itemId)) {
            grant(player, "buildPickaxe");
            if (AchievementTriggers.isBetterPickaxe(itemId)) {
                grant(player, "buildBetterPickaxe");
            }
        } else if (itemId == 61) {
            grant(player, "buildFurnace");
        } else if (AchievementTriggers.isHoe(itemId)) {
            grant(player, "buildHoe");
        } else if (itemId == 297) {
            grant(player, "makeBread");
        } else if (itemId == 354) {
            grant(player, "bakeCake");
        } else if (AchievementTriggers.isSword(itemId)) {
            grant(player, "buildSword");
        }
    }

    private void furnace(Player player, int itemId) {
        if (itemId == 265) {
            grant(player, "acquireIron");
        }
        if (itemId == 350) {
            grant(player, "cookFish");
        }
    }

    private void grant(Player player, String achievementId) {
        plugin.getAchievements().unlock(player, achievementId, false);
    }

    @EventHandler
    public void vehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart)) {
            return;
        }
        Entity passenger = event.getVehicle().getPassenger();
        if (!(passenger instanceof Player)) {
            return;
        }

        Player player = (Player) passenger;
        if (plugin.getAchievements().isUnlocked(player, "onARail")) {
            railStarts.remove(player.getUniqueId());
            return;
        }

        Location first = railStarts.get(player.getUniqueId());
        if (first == null || !first.getWorld().equals(event.getTo().getWorld())) {
            railStarts.put(player.getUniqueId(), event.getFrom());
            return;
        }

        double dx = event.getTo().getX() - first.getX();
        double dy = event.getTo().getY() - first.getY();
        double dz = event.getTo().getZ() - first.getZ();
        if (dx * dx + dy * dy + dz * dz >= 1000000D) {
            grant(player, "onARail");
            railStarts.remove(player.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent)) {
            return;
        }
        EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) event;
        Player attacker = attackingPlayer(damageEvent.getDamager());
        if (attacker == null) {
            return;
        }

        Entity victim = event.getEntity();
        boolean monster = victim instanceof Monster || victim instanceof Slime || victim instanceof Ghast;
        if (plugin.getAchievements().isDebugging(attacker, "killEnemy")) {
            plugin.getAchievements().debug(
                attacker,
                "伤害来源=" + damageEvent.getDamager().getClass().getSimpleName()
                    + "，目标=" + victim.getClass().getSimpleName()
                    + " (id=" + victim.getEntityId() + ")，是否怪物=" + monster
            );
        }
        if (!monster) {
            return;
        }

        lastAttackers.put(victim.getEntityId(), new AttackRecord(attacker));
        if (lastAttackers.size() > 256) {
            long cutoff = System.currentTimeMillis() - 30000L;
            Iterator<AttackRecord> iterator = lastAttackers.values().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().time < cutoff) {
                    iterator.remove();
                }
            }
        }
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            Entity shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    @EventHandler
    public void death(EntityDeathEvent event) {
        AttackRecord record = lastAttackers.remove(event.getEntity().getEntityId());
        if (record == null) {
            return;
        }

        long age = System.currentTimeMillis() - record.time;
        Player killer = record.player;
        if (plugin.getAchievements().isDebugging(killer, "killEnemy")) {
            plugin.getAchievements().debug(
                killer,
                "死亡事件=" + event.getEntity().getClass().getSimpleName()
                    + " (id=" + event.getEntity().getEntityId() + ")，攻击记录存在（"
                    + age + "ms 前）" + (age > 30000L ? "，已超时" : "")
            );
        }
        if (age > 30000L) {
            return;
        }

        try {
            net.minecraft.server.Entity entity = ((CraftEntity) event.getEntity()).getHandle();
            boolean monster = entity instanceof EntityMonster
                || entity instanceof EntitySlime
                || entity instanceof EntityGhast;
            if (monster) {
                grant(killer, "killEnemy");
            }
        } catch (Throwable ignored) {
            Entity entity = event.getEntity();
            if (entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast) {
                grant(killer, "killEnemy");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void pigFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
            || event.getDamage() <= 2
            || !(event.getEntity() instanceof Pig)) {
            return;
        }
        Entity rider = event.getEntity().getPassenger();
        if (rider instanceof Player) {
            grant((Player) rider, "flyPig");
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        plugin.getAchievements().load(event.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        railStarts.remove(playerId);
        Iterator<AttackRecord> iterator = lastAttackers.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().player.equals(event.getPlayer())) {
                iterator.remove();
            }
        }
        plugin.getAchievements().unload(event.getPlayer());
    }
}
