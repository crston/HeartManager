package com.gmail.bobason01;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HeartManager extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("heartmanager") != null) {
            getCommand("heartmanager").setExecutor(this);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            setupPlayer(player);
        }

        getLogger().info("HeartManager Plugin Enabled.");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("heartmanager.admin")) {
                sender.sendMessage(ChatColor.RED + "No Permissions.");
                return true;
            }

            reloadConfig();

            for (Player player : Bukkit.getOnlinePlayers()) {
                setupPlayer(player);
            }

            sender.sendMessage(ChatColor.GREEN + "HeartManager Config Reloaded.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "/hm reload - Config Reload.");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // 접속 직후 패킷 꼬임 방지를 위해 2틱 지연
        Bukkit.getScheduler().runTaskLater(this, () -> setupPlayer(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removeBossBar(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // 리스폰 시에는 체력이 가득 찬 상태이므로 안전하게 지연 적용
        Bukkit.getScheduler().runTaskLater(this, () -> setupPlayer(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            // 데미지를 입은 직후 바로 업데이트하지 않고 1틱 뒤에 호출 (정확한 체력 계산을 위함)
            Bukkit.getScheduler().runTask(this, () -> updateDisplay((Player) event.getEntity()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player) {
            Bukkit.getScheduler().runTask(this, () -> updateDisplay((Player) event.getEntity()));
        }
    }

    private void setupPlayer(Player player) {
        double scale = getConfig().getDouble("settings.heart-scale", 20.0);

        // 1. 먼저 스케일링을 비활성화하여 초기화
        player.setHealthScaled(false);

        // 2. 최대 체력 속성을 가져옴
        AttributeInstance maxAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxAttr == null) return;

        double maxHealth = maxAttr.getValue();

        // 3. 현재 체력이 최대 체력을 초과하지 않도록 강제 조정 (애니메이션 버그의 핵심 원인)
        if (player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }

        // 4. 스케일 적용 (20.0이면 하트 10칸)
        player.setHealthScale(scale);
        player.setHealthScaled(true);

        updateDisplay(player);
    }

    private void updateDisplay(Player player) {
        if (!player.isOnline()) return;

        double currentHealth = player.getHealth();
        AttributeInstance maxAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = (maxAttr != null) ? maxAttr.getValue() : 20.0;

        // 비율 계산 시 0으로 나누기 방지
        double ratio = (maxHealth > 0) ? Math.max(0.0, Math.min(1.0, currentHealth / maxHealth)) : 0;

        if (getConfig().getBoolean("settings.actionbar.enabled", true)) {
            sendActionBar(player, currentHealth, maxHealth);
        }

        if (getConfig().getBoolean("settings.bossbar.enabled", true)) {
            updateBossBar(player, currentHealth, maxHealth, ratio);
        } else {
            removeBossBar(player.getUniqueId());
        }
    }

    private void sendActionBar(Player player, double current, double max) {
        String format = getConfig().getString("settings.actionbar.format", "&7[ &c❤ &fHP: <current> / <max> &7]");
        if (format == null) return;

        String message = ChatColor.translateAlternateColorCodes('&', format
                .replace("<current>", String.format("%.1f", current))
                .replace("<max>", String.format("%.1f", max)));

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private void updateBossBar(Player player, double current, double max, double ratio) {
        BossBar bar = bossBars.get(player.getUniqueId());

        if (bar == null) {
            String styleStr = getConfig().getString("settings.bossbar.style", "SEGMENTED_10");
            BarStyle style;
            try {
                style = BarStyle.valueOf(styleStr.toUpperCase());
            } catch (Exception e) {
                style = BarStyle.SEGMENTED_10;
            }

            bar = Bukkit.createBossBar("", BarColor.GREEN, style);
            bar.addPlayer(player);
            bossBars.put(player.getUniqueId(), bar);
        }

        String titleFormat = getConfig().getString("settings.bossbar.title", "&c&lHP &f<current> / <max>");
        if (titleFormat != null) {
            String title = ChatColor.translateAlternateColorCodes('&', titleFormat
                    .replace("<current>", String.format("%.1f", current))
                    .replace("<max>", String.format("%.1f", max)));
            bar.setTitle(title);
        }

        bar.setProgress(ratio);

        if (ratio > 0.5) {
            bar.setColor(BarColor.GREEN);
        } else if (ratio > 0.2) {
            bar.setColor(BarColor.YELLOW);
        } else {
            bar.setColor(BarColor.RED);
        }
    }

    private void removeBossBar(UUID uuid) {
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
    }
}