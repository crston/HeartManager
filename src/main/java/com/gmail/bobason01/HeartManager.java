package com.gmail.bobason01;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedAttribute;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HeartManager extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private ProtocolManager protocolManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("heartmanager") != null) {
            getCommand("heartmanager").setExecutor(this);
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        registerPacketListener();

        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayer(player);
        }

        getLogger().info("HeartManager Enabled with ProtocolLib.");
    }

    private void registerPacketListener() {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();

                // 패킷의 엔티티 ID가 해당 플레이어인지 확인
                if (event.getPlayer().getEntityId() != packet.getIntegers().read(0)) {
                    return;
                }

                // 속성 리스트 가져오기
                List<WrappedAttribute> attributes = packet.getAttributeCollectionModifier().read(0);
                List<WrappedAttribute> newAttributes = new ArrayList<>();

                for (WrappedAttribute attr : attributes) {
                    // 최대 체력 속성인지 확인 (1.13+ 버전 대응 포함)
                    if (attr.getAttributeKey().equals("minecraft:generic.max_health") || attr.getAttributeKey().equals("generic.max_health")) {
                        // 새로운 빌더를 사용하여 베이스 값을 20.0으로 고정한 속성 생성
                        WrappedAttribute modified = WrappedAttribute.newBuilder(attr)
                                .baseValue(20.0)
                                .build();
                        newAttributes.add(modified);
                    } else {
                        newAttributes.add(attr);
                    }
                }

                // 수정된 리스트로 패킷 갱신
                packet.getAttributeCollectionModifier().write(0, newAttributes);
            }
        });
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
                refreshPlayer(player);
            }
            sender.sendMessage(ChatColor.GREEN + "HeartManager Config Reloaded.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "/hm reload - Config Reload.");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removeBossBar(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> refreshPlayer(event.getPlayer()), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            updateDisplay((Player) event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player) {
            updateDisplay((Player) event.getEntity());
        }
    }

    private void refreshPlayer(Player player) {
        // 기존 스케일 설정 해제
        player.setHealthScaled(false);
        updateDisplay(player);
    }

    private void updateDisplay(Player player) {
        if (!player.isOnline()) return;

        double currentHealth = player.getHealth();
        AttributeInstance maxAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = (maxAttr != null) ? maxAttr.getValue() : 20.0;

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