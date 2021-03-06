package fr.xephi.authme.listener;

import fr.xephi.authme.AntiBot;
import fr.xephi.authme.cache.auth.PlayerAuth;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.output.MessageKey;
import fr.xephi.authme.output.Messages;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.settings.NewSetting;
import fr.xephi.authme.settings.SpawnLoader;
import fr.xephi.authme.settings.properties.HooksSettings;
import fr.xephi.authme.settings.properties.RegistrationSettings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.util.BukkitService;
import fr.xephi.authme.util.Utils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;

import javax.inject.Inject;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static fr.xephi.authme.settings.properties.RestrictionSettings.ALLOWED_MOVEMENT_RADIUS;
import static fr.xephi.authme.settings.properties.RestrictionSettings.ALLOW_UNAUTHED_MOVEMENT;

/**
 * Listener class for player events.
 */
public class AuthMePlayerListener implements Listener {

    public static final ConcurrentHashMap<String, String> joinMessage = new ConcurrentHashMap<>();

    @Inject
    private NewSetting settings;
    @Inject
    private Messages m;
    @Inject
    private DataSource dataSource;
    @Inject
    private AntiBot antiBot;
    @Inject
    private Management management;
    @Inject
    private BukkitService bukkitService;
    @Inject
    private SpawnLoader spawnLoader;
    @Inject
    private OnJoinVerifier onJoinVerifier;
    @Inject
    private ListenerService listenerService;

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().split(" ")[0].toLowerCase();
        if (settings.getProperty(HooksSettings.USE_ESSENTIALS_MOTD) && cmd.equals("/motd")) {
            return;
        }
        if (settings.getProperty(RestrictionSettings.ALLOW_COMMANDS).contains(cmd)) {
            return;
        }
        final Player player = event.getPlayer();
        if (listenerService.shouldCancelEvent(player)) {
            event.setCancelled(true);
            m.send(player, MessageKey.DENIED_COMMAND);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (settings.getProperty(RestrictionSettings.ALLOW_CHAT)) {
            return;
        }

        final Player player = event.getPlayer();
        if (listenerService.shouldCancelEvent(player)) {
            event.setCancelled(true);
            m.send(player, MessageKey.DENIED_CHAT);
        } else if (settings.getProperty(RestrictionSettings.HIDE_CHAT)) {
            Set<Player> recipients = event.getRecipients();
            Iterator<Player> iter = recipients.iterator();
            while (iter.hasNext()) {
                Player p = iter.next();
                if (listenerService.shouldCancelEvent(p)) {
                    iter.remove();
                }
            }
            if (recipients.size() == 0) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (settings.getProperty(ALLOW_UNAUTHED_MOVEMENT) && settings.getProperty(ALLOWED_MOVEMENT_RADIUS) <= 0) {
            return;
        }

        /*
         * Limit player X and Z movements to 1 block
         * Deny player Y+ movements (allows falling)
         */
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
            && from.getBlockZ() == to.getBlockZ()
            && from.getY() - to.getY() >= 0) {
            return;
        }

        Player player = event.getPlayer();
        if (!listenerService.shouldCancelEvent(player)) {
            return;
        }

        if (!settings.getProperty(RestrictionSettings.ALLOW_UNAUTHED_MOVEMENT)) {
            // "cancel" the event
            event.setTo(event.getFrom());
            if (settings.getProperty(RestrictionSettings.REMOVE_SPEED)) {
                player.setFlySpeed(0.0f);
                player.setWalkSpeed(0.0f);
            }
            return;
        }

        if (settings.getProperty(RestrictionSettings.NO_TELEPORT)) {
            return;
        }

        Location spawn = spawnLoader.getSpawnLocation(player);
        if (spawn != null && spawn.getWorld() != null) {
            if (!player.getWorld().equals(spawn.getWorld())) {
                player.teleport(spawn);
                return;
            }
            if (spawn.distance(player.getLocation()) > settings.getProperty(ALLOWED_MOVEMENT_RADIUS)) {
                player.teleport(spawn);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinMessage(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        if (settings.getProperty(RegistrationSettings.REMOVE_JOIN_MESSAGE)) {
            event.setJoinMessage(null);
            return;
        }
        if (!settings.getProperty(RegistrationSettings.DELAY_JOIN_MESSAGE)) {
            return;
        }

        String name = player.getName().toLowerCase();
        String joinMsg = event.getJoinMessage();

        // Remove the join message while the player isn't logging in
        if (joinMsg != null) {
            event.setJoinMessage(null);
            joinMessage.put(name, joinMsg);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (player != null) {
            // Schedule login task so works after the prelogin
            // (Fix found by Koolaid5000)
            bukkitService.runTask(new Runnable() {
                @Override
                public void run() {
                    management.performJoin(player);
                }
            });
        }
    }

    // Note ljacqu 20160528: AsyncPlayerPreLoginEvent is not fired by all servers in offline mode
    // e.g. CraftBukkit does not. So we need to run crucial things in onPlayerLogin, too
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        final String name = event.getName().toLowerCase();
        final boolean isAuthAvailable = dataSource.isAuthAvailable(event.getName());

        try {
            // Potential performance improvement: make checkAntiBot not require `isAuthAvailable` info and use
            // "checkKickNonRegistered" as last -> no need to query the DB before checking antibot / name
            onJoinVerifier.checkAntibot(name, isAuthAvailable);
            onJoinVerifier.checkKickNonRegistered(isAuthAvailable);
            onJoinVerifier.checkIsValidName(name);
        } catch (FailedVerificationException e) {
            event.setKickMessage(m.retrieveSingle(e.getReason(), e.getArgs()));
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        final Player player = event.getPlayer();
        if (Utils.isUnrestricted(player)) {
            return;
        } else if (onJoinVerifier.refusePlayerForFullServer(event)) {
            return;
        } else if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }

        final String name = player.getName().toLowerCase();
        final PlayerAuth auth = dataSource.getAuth(player.getName());
        final boolean isAuthAvailable = (auth != null);

        try {
            onJoinVerifier.checkAntibot(name, isAuthAvailable);
            onJoinVerifier.checkKickNonRegistered(isAuthAvailable);
            onJoinVerifier.checkIsValidName(name);
            onJoinVerifier.checkNameCasing(player, auth);
            onJoinVerifier.checkSingleSession(player);
            onJoinVerifier.checkPlayerCountry(isAuthAvailable, event);
        } catch (FailedVerificationException e) {
            event.setKickMessage(m.retrieveSingle(e.getReason(), e.getArgs()));
            event.setResult(PlayerLoginEvent.Result.KICK_OTHER);
            return;
        }

        antiBot.handlePlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (settings.getProperty(RegistrationSettings.REMOVE_LEAVE_MESSAGE)) {
            event.setQuitMessage(null);
        }

        if (antiBot.antibotKicked.contains(player.getName())) {
            return;
        }

        management.performQuit(player, false);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();

        if (!antiBot.antibotKicked.contains(player.getName())) {
            management.performQuit(player, true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerConsumeItem(PlayerItemConsumeEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerInventoryOpen(InventoryOpenEvent event) {
        final Player player = (Player) event.getPlayer();

        if (!listenerService.shouldCancelEvent(player)) {
            return;
        }
        event.setCancelled(true);

        /*
         * @note little hack cause InventoryOpenEvent cannot be cancelled for
         * real, cause no packet is send to server by client for the main inv
         */
        bukkitService.scheduleSyncDelayedTask(new Runnable() {
            @Override
            public void run() {
                player.closeInventory();
            }
        }, 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!listenerService.shouldCancelEvent(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerHitPlayerEvent(EntityDamageByEntityEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (listenerService.shouldCancelEvent(player)) {
            event.setCancelled(true);
        }
    }

    // TODO: check this, why do we need to update the quit loc? -sgdc3
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (settings.getProperty(RestrictionSettings.NO_TELEPORT)) {
            return;
        }
        if (!listenerService.shouldCancelEvent(event)) {
            return;
        }
        Player player = event.getPlayer();
        String name = player.getName().toLowerCase();
        Location spawn = spawnLoader.getSpawnLocation(player);
        if (settings.getProperty(RestrictionSettings.SAVE_QUIT_LOCATION) && dataSource.isAuthAvailable(name)) {
            PlayerAuth auth = PlayerAuth.builder()
                .name(name)
                .realName(player.getName())
                .location(spawn)
                .build();
            dataSource.updateQuitLoc(auth);
        }
        if (spawn != null && spawn.getWorld() != null) {
            event.setRespawnLocation(spawn);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerShear(PlayerShearEntityEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerFish(PlayerFishEvent event) {
        if (listenerService.shouldCancelEvent(event)) {
            event.setCancelled(true);
        }
    }

}
