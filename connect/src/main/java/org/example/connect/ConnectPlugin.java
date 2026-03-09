package org.example.connect;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectPlugin extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, BukkitTask> actionBarTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> noticeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerVerificationRecord> verificationDb = new ConcurrentHashMap<>();

    private File dbFile;
    private YamlConfiguration dbConfig;

    private String placeholderIsLinked;
    private Duration playtimeRequirement;
    private long actionBarPeriodTicks;
    private long noticePeriodTicks;
    private boolean actionBarEnabled;
    private boolean noticeEnabled;
    private boolean titleEnabled;
    private boolean subtitleEnabled;
    private boolean chatEnabled;
    private boolean cancelOnLink;
    private String bypassPermission;
    private boolean useWorldWhitelist;
    private Set<String> worldList;
    private Duration titleFadeIn;
    private Duration titleStay;
    private Duration titleFadeOut;
    private String actionBarText;
    private String titleText;
    private String subtitleText;
    private String chatText;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().severe("PlaceholderAPI not found. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        loadSettings();
        loadVerificationDb();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateVerificationStatus(player, isLinked(player));
            startTasks(player);
        }
    }

    @Override
    public void onDisable() {
        actionBarTasks.values().forEach(BukkitTask::cancel);
        noticeTasks.values().forEach(BukkitTask::cancel);
        actionBarTasks.clear();
        noticeTasks.clear();
        saveVerificationDb();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updateVerificationStatus(player, isLinked(player));
        startTasks(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopTasks(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"connect".equalsIgnoreCase(command.getName())) {
            return false;
        }
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("connect.reload")) {
                sender.sendMessage("No permission.");
                return true;
            }
            reloadConfig();
            loadSettings();
            loadVerificationDb();
            actionBarTasks.values().forEach(BukkitTask::cancel);
            noticeTasks.values().forEach(BukkitTask::cancel);
            actionBarTasks.clear();
            noticeTasks.clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateVerificationStatus(player, isLinked(player));
                startTasks(player);
            }
            sender.sendMessage("Connect reloaded.");
            return true;
        }

        if (args.length == 1 && "fine".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("connect.fine")) {
                sender.sendMessage("No permission.");
                return true;
            }
            int checked = 0;
            int fined = 0;
            int skipped = 0;
            long deadline = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli();

            for (org.bukkit.OfflinePlayer offlinePlayer : Bukkit.getWhitelistedPlayers()) {
                checked++;
                UUID uuid = offlinePlayer.getUniqueId();
                PlayerVerificationRecord record = verificationDb.computeIfAbsent(uuid,
                        ignored -> new PlayerVerificationRecord(getSafePlayerName(offlinePlayer), false));

                if (record.playerName == null || record.playerName.isBlank()) {
                    record.playerName = getSafePlayerName(offlinePlayer);
                }

                if (offlinePlayer.isBanned()) {
                    skipped++;
                    continue;
                }

                if (record.hasBypassPermission || hasLiveBypassPermission(offlinePlayer)) {
                    skipped++;
                    continue;
                }

                if (record.verified) {
                    skipped++;
                    continue;
                }

                if (record.fineIssuedAt > 0) {
                    skipped++;
                    continue;
                }

                long lastSeen = offlinePlayer.getLastSeen();
                if (lastSeen <= 0 || lastSeen > deadline) {
                    skipped++;
                    continue;
                }

                String nick = getSafePlayerName(offlinePlayer);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "fine add " + nick + " KooT_ 64 1w Да Автоматический штраф. Неверификация аккаунта.");
                record.fineIssuedAt = System.currentTimeMillis();
                fined++;
            }
            saveVerificationDb();
            sender.sendMessage("Connect fine wave done. Checked: " + checked + ", fined: " + fined + ", skipped: " + skipped + ".");
            return true;
        }

        sender.sendMessage("Usage: /connect reload | /connect fine");
        return true;
    }

    private void startTasks(Player player) {
        UUID uuid = player.getUniqueId();
        stopTasks(uuid);

        if (shouldStopForLinked(player)) {
            return;
        }
        if (!isEligible(player)) {
            return;
        }

        actionBarTasks.put(uuid, Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                stopTasks(uuid);
                return;
            }
            if (shouldStopForLinked(online)) {
                stopTasks(uuid);
                return;
            }
            if (!isEligible(online)) {
                stopTasks(uuid);
                return;
            }
            if (!actionBarEnabled) {
                return;
            }
            online.sendActionBar(LEGACY.deserialize(actionBarText));
        }, 0L, actionBarPeriodTicks));

        noticeTasks.put(uuid, Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                stopTasks(uuid);
                return;
            }
            if (shouldStopForLinked(online)) {
                stopTasks(uuid);
                return;
            }
            if (!isEligible(online)) {
                stopTasks(uuid);
                return;
            }
            if (!hasRequiredPlaytime(online)) {
                return;
            }
            if (!noticeEnabled) {
                return;
            }

            if (titleEnabled) {
                Component subtitle = subtitleEnabled ? LEGACY.deserialize(subtitleText) : Component.empty();
                online.showTitle(Title.title(
                        LEGACY.deserialize(titleText),
                        subtitle,
                        Title.Times.times(titleFadeIn, titleStay, titleFadeOut)
                ));
            }
            if (chatEnabled) {
                online.sendMessage(LEGACY.deserialize(chatText));
            }
        }, 0L, noticePeriodTicks));
    }

    private void stopTasks(UUID uuid) {
        BukkitTask actionTask = actionBarTasks.remove(uuid);
        if (actionTask != null) {
            actionTask.cancel();
        }
        BukkitTask noticeTask = noticeTasks.remove(uuid);
        if (noticeTask != null) {
            noticeTask.cancel();
        }
    }

    private boolean isLinked(Player player) {
        String value = PlaceholderAPI.setPlaceholders(player, placeholderIsLinked);
        String normalized = normalizePlaceholderValue(value);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("1") || normalized.contains("linked")) {
            updateVerificationStatus(player, true);
            return true;
        }
        if (normalized.equals("false") || normalized.equals("no") || normalized.equals("0")) {
            return false;
        }
        if (normalized.contains("верифиц")) {
            boolean verified = !normalized.contains("не");
            if (verified) {
                updateVerificationStatus(player, true);
            }
            return verified;
        }
        return false;
    }

    private boolean isEligible(Player player) {
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return false;
        }
        if (!isWorldAllowed(player)) {
            return false;
        }
        return true;
    }

    private boolean shouldStopForLinked(Player player) {
        if (!cancelOnLink) {
            return false;
        }
        if (!isLinked(player)) {
            return false;
        }
        updateVerificationStatus(player, true);
        player.sendActionBar(Component.empty());
        return true;
    }

    private boolean isWorldAllowed(Player player) {
        if (worldList == null || worldList.isEmpty()) {
            return true;
        }
        String worldName = player.getWorld().getName();
        boolean contains = worldList.contains(worldName);
        return useWorldWhitelist ? contains : !contains;
    }

    private boolean hasRequiredPlaytime(Player player) {
        int ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long seconds = ticks / 20L;
        return seconds >= playtimeRequirement.toSeconds();
    }

    private void loadSettings() {
        placeholderIsLinked = getConfig().getString("placeholder.is-linked", "%discordsrv_user_islinked%");
        playtimeRequirement = Duration.ofHours(getConfig().getLong("playtime.requirement-hours", 48));
        actionBarPeriodTicks = getConfig().getLong("actionbar.interval-ticks", 40L);
        noticePeriodTicks = getConfig().getLong("notice.interval-ticks", 1200L);
        actionBarEnabled = getConfig().getBoolean("actionbar.enabled", true);
        noticeEnabled = getConfig().getBoolean("notice.enabled", true);
        titleEnabled = getConfig().getBoolean("notice.title.enabled", true);
        subtitleEnabled = getConfig().getBoolean("notice.subtitle.enabled", false);
        chatEnabled = getConfig().getBoolean("notice.chat.enabled", true);
        cancelOnLink = getConfig().getBoolean("behavior.stop-tasks-when-linked", true);
        bypassPermission = getConfig().getString("behavior.bypass-permission", "connect.bypass");
        useWorldWhitelist = getConfig().getBoolean("worlds.use-whitelist", false);
        worldList = Set.copyOf(getConfig().getStringList("worlds.list"));
        titleFadeIn = Duration.ofMillis(getConfig().getLong("notice.title.fade-in-ms", 250));
        titleStay = Duration.ofMillis(getConfig().getLong("notice.title.stay-ms", 2000));
        titleFadeOut = Duration.ofMillis(getConfig().getLong("notice.title.fade-out-ms", 250));
        actionBarText = getConfig().getString("actionbar.text", "&cВерификация обязательна для игры на сервере!");
        titleText = getConfig().getString("notice.title.text", "&cПривяжите аккаунт к дискорду!");
        subtitleText = getConfig().getString("notice.subtitle.text", "");
        chatText = getConfig().getString("notice.chat.text", "&7Команда /v - чтобы привязать аккаунт");
    }

    private String normalizePlaceholderValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
        return cleaned.trim().toLowerCase(Locale.ROOT);
    }

    private void loadVerificationDb() {
        dbFile = new File(getDataFolder(), "verification.yml");
        if (!dbFile.exists()) {
            getDataFolder().mkdirs();
            dbConfig = new YamlConfiguration();
            dbConfig.set("players", new HashMap<>());
            saveVerificationDb();
            return;
        }

        dbConfig = YamlConfiguration.loadConfiguration(dbFile);
        Map<String, Object> players = dbConfig.getConfigurationSection("players") == null
                ? Map.of()
                : dbConfig.getConfigurationSection("players").getValues(false);
        verificationDb.clear();
        for (Map.Entry<String, Object> entry : players.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                Object raw = entry.getValue();
                PlayerVerificationRecord record;
                if (raw instanceof Boolean) {
                    record = new PlayerVerificationRecord(null, (Boolean) raw);
                } else if (raw instanceof org.bukkit.configuration.ConfigurationSection section) {
                    record = new PlayerVerificationRecord(
                            section.getString("name", null),
                            section.getBoolean("verified", false)
                    );
                    record.lastUpdated = section.getLong("last-updated", 0L);
                    record.hasBypassPermission = section.getBoolean("bypass", false);
                    record.fineIssuedAt = section.getLong("fine-issued-at", 0L);
                } else {
                    boolean verified = "true".equalsIgnoreCase(String.valueOf(raw));
                    record = new PlayerVerificationRecord(null, verified);
                }
                verificationDb.put(uuid, record);
            } catch (IllegalArgumentException ignored) {
                // skip invalid UUIDs
            }
        }
    }

    private void saveVerificationDb() {
        if (dbConfig == null || dbFile == null) {
            return;
        }
        Map<String, Object> players = new HashMap<>();
        for (Map.Entry<UUID, PlayerVerificationRecord> entry : verificationDb.entrySet()) {
            PlayerVerificationRecord record = entry.getValue();
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("name", record.playerName);
            serialized.put("verified", record.verified);
            serialized.put("last-updated", record.lastUpdated);
            serialized.put("bypass", record.hasBypassPermission);
            serialized.put("fine-issued-at", record.fineIssuedAt);
            players.put(entry.getKey().toString(), serialized);
        }
        dbConfig.set("players", players);
        try {
            dbConfig.save(dbFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save verification.yml: " + e.getMessage());
        }
    }

    private void updateVerificationStatus(Player player, boolean verified) {
        UUID uuid = player.getUniqueId();
        PlayerVerificationRecord record = verificationDb.get(uuid);
        if (record == null) {
            record = new PlayerVerificationRecord(player.getName(), verified);
        }

        String oldName = record.playerName;
        boolean oldVerified = record.verified;
        boolean oldBypass = record.hasBypassPermission;

        record.playerName = player.getName();
        record.verified = verified;
        record.lastUpdated = System.currentTimeMillis();
        record.hasBypassPermission = bypassPermission != null
                && !bypassPermission.isBlank()
                && player.hasPermission(bypassPermission);
        if (verified) {
            record.fineIssuedAt = 0L;
        }

        verificationDb.put(uuid, record);
        boolean changed = oldVerified != record.verified
                || oldBypass != record.hasBypassPermission
                || !safeEquals(oldName, record.playerName);
        if (changed) {
            saveVerificationDb();
        }
    }

    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private boolean hasLiveBypassPermission(org.bukkit.OfflinePlayer offlinePlayer) {
        if (bypassPermission == null || bypassPermission.isBlank()) {
            return false;
        }
        if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
            return offlinePlayer.getPlayer().hasPermission(bypassPermission);
        }
        return false;
    }

    private String getSafePlayerName(org.bukkit.OfflinePlayer player) {
        String name = player.getName();
        if (name == null || name.isBlank()) {
            return player.getUniqueId().toString();
        }
        return name;
    }

    private static final class PlayerVerificationRecord {
        private String playerName;
        private boolean verified;
        private long lastUpdated;
        private boolean hasBypassPermission;
        private long fineIssuedAt;

        private PlayerVerificationRecord(String playerName, boolean verified) {
            this.playerName = playerName;
            this.verified = verified;
            this.lastUpdated = System.currentTimeMillis();
            this.hasBypassPermission = false;
            this.fineIssuedAt = 0L;
        }
    }
}
