package ink.yzfs.angecoordinate;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.PacketType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Angecoordinate extends JavaPlugin implements Listener {

    private final Map<UUID, List<CoordinateRecord>> playerCoordinates = new HashMap<>();
    private final Map<UUID, List<Long>> playerTeleportCooldowns = new HashMap<>();
    private final Map<UUID, Boolean> waitingForSignInput = new HashMap<>();
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private final Map<UUID, Location> signLocations = new HashMap<>();
    private final Map<UUID, BukkitTask> teleportTasks = new HashMap<>();

    // 配置项
    private int teleportWaitTime;
    private boolean enableTeleportWait;
    private int maxTeleportsPer5Min;
    private boolean adminBypassLimit;
    private boolean enableSneakFKey;

    private final Set<Material> nonSolidBlocks = new HashSet<>(Arrays.asList(
            Material.REDSTONE_WIRE, Material.WATER, Material.LAVA, Material.CHEST, Material.TRAPPED_CHEST,
            Material.ENDER_CHEST, Material.FLOWER_POT, Material.TALL_GRASS, Material.DANDELION, Material.POPPY, Material.LILY_PAD,
            Material.OAK_SLAB, Material.SPRUCE_SLAB, Material.BIRCH_SLAB, Material.JUNGLE_SLAB,
            Material.ACACIA_SLAB, Material.DARK_OAK_SLAB, Material.STONE_SLAB, Material.SANDSTONE_SLAB,
            Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR, Material.JUNGLE_DOOR,
            Material.REDSTONE_TORCH, Material.REPEATER, Material.COMPARATOR, Material.DRAGON_EGG,
            Material.DAYLIGHT_DETECTOR, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.ENCHANTING_TABLE,
            Material.SKELETON_SKULL, Material.CREEPER_HEAD, Material.ZOMBIE_HEAD, Material.DRAGON_HEAD,
            Material.WHITE_BED, Material.RED_BED, Material.BLUE_BED, Material.BLACK_BED
    ));

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        protocolManager.addPacketListener(new PacketAdapter(this, PacketType.Play.Client.UPDATE_SIGN) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (waitingForSignInput.getOrDefault(player.getUniqueId(), false)) {
                    String[] lines = event.getPacket().getStringArrays().read(0);
                    String note = lines[0].isEmpty() ? "未命名坐标点" : lines[0];

                    if (player.hasMetadata("editingRecord")) {
                        CoordinateRecord record = (CoordinateRecord) player.getMetadata("editingRecord").get(0).value();
                        record.setName(note);
                        player.sendMessage(ChatColor.GREEN + "备注名称已更新为: " + ChatColor.AQUA + note);
                        savePlayerCoordinates(player);
                        player.removeMetadata("editingRecord", Angecoordinate.this);
                    } else {
                        Location loc = player.getLocation();
                        CoordinateRecord record = new CoordinateRecord(note, loc);
                        playerCoordinates.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(record);
                        savePlayerCoordinates(player);
                        player.sendMessage(ChatColor.GREEN + "坐标记录添加成功！备注: " + ChatColor.AQUA + note);
                    }

                    waitingForSignInput.put(player.getUniqueId(), false);

                    Bukkit.getScheduler().runTaskLater(Angecoordinate.this, () -> {
                        deleteTemporarySign(player);
                        openMainGui(player);
                    }, 5L);
                }
            }
        });

        // 载入配置
        loadConfig();

        getCommand("an").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                loadPlayerCoordinates(player);
                openMainGui(player);
            }
            return true;
        });

        getCommand("a").setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (sender.hasPermission("angecoordinate.admin")) {
                    reloadConfig();
                    loadConfig();
                    sender.sendMessage(ChatColor.GREEN + "配置文件已重新加载！");
                } else {
                    sender.sendMessage(ChatColor.RED + "你没有权限执行此命令！");
                }
                return true;
            }
            return false;
        });

        // 注册 Tab 补全
        getCommand("a").setTabCompleter((sender, command, alias, args) -> {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                if ("reload".startsWith(args[0].toLowerCase())) {
                    completions.add("reload");
                }
            }
            return completions;
        });
    }


    private void loadConfig() {
        saveDefaultConfig();
        teleportWaitTime = getConfig().getInt("teleport-wait-time", 3);
        enableTeleportWait = getConfig().getBoolean("enable-teleport-wait", true);
        maxTeleportsPer5Min = getConfig().getInt("max-teleports-per-5min", 2);
        adminBypassLimit = getConfig().getBoolean("admin-bypass-limit", true);
        enableSneakFKey = getConfig().getBoolean("enable-sneak-f-key", false);
    }

    private void savePlayerCoordinates(Player player) {
        File playerFile = new File(getDataFolder(), "players/" + player.getUniqueId() + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

        List<CoordinateRecord> records = playerCoordinates.get(player.getUniqueId());
        if (records != null) {
            config.set("coordinates", null);
            for (int i = 0; i < records.size(); i++) {
                CoordinateRecord record = records.get(i);
                config.set("coordinates." + i + ".name", record.getName());
                config.set("coordinates." + i + ".world", record.getLocation().getWorld().getName());
                config.set("coordinates." + i + ".x", record.getLocation().getX());
                config.set("coordinates." + i + ".y", record.getLocation().getY());
                config.set("coordinates." + i + ".z", record.getLocation().getZ());
            }
        }

        try {
            config.save(playerFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPlayerCoordinates(Player player) {
        File playerFile = new File(getDataFolder(), "players/" + player.getUniqueId() + ".yml");
        if (!playerFile.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        List<CoordinateRecord> records = new ArrayList<>();

        if (config.contains("coordinates")) {
            for (String key : config.getConfigurationSection("coordinates").getKeys(false)) {
                String name = config.getString("coordinates." + key + ".name");
                String worldName = config.getString("coordinates." + key + ".world");
                double x = config.getDouble("coordinates." + key + ".x");
                double y = config.getDouble("coordinates." + key + ".y");
                double z = config.getDouble("coordinates." + key + ".z");
                World world = Bukkit.getWorld(worldName);

                if (world != null) {
                    Location location = new Location(world, x, y, z);
                    records.add(new CoordinateRecord(name, location));
                }
            }
        }

        playerCoordinates.put(player.getUniqueId(), records);
    }

    private void openMainGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.BOLD + "坐标记录菜单");
        List<CoordinateRecord> coordinates = playerCoordinates.getOrDefault(player.getUniqueId(), new ArrayList<>());

        for (int i = 0; i < Math.min(coordinates.size(), 45); i++) {
            CoordinateRecord record = coordinates.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + record.getName());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "坐标: " + record.getLocation().getBlockX() + ", " +
                    record.getLocation().getBlockY() + ", " +
                    record.getLocation().getBlockZ());
            lore.add(ChatColor.GRAY + "世界: " + record.getLocation().getWorld().getName());
            meta.setLore(lore);

            meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            meta.setUnbreakable(true);

            item.setItemMeta(meta);
            gui.setItem(i, item);
        }

        ItemStack addButton = new ItemStack(Material.EMERALD);
        ItemMeta addMeta = addButton.getItemMeta();
        addMeta.setDisplayName(ChatColor.GREEN + "添加一个坐标记录");
        addButton.setItemMeta(addMeta);
        gui.setItem(53, addButton);

        ItemStack closeButton = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeButton.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + "关闭菜单");
        closeButton.setItemMeta(closeMeta);
        gui.setItem(45, closeButton);

        player.openInventory(gui);
        playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    private void openConfirmAddGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.BOLD + "确认要添加一个吗？");

        ItemStack confirmButton = new ItemStack(Material.LIME_WOOL);
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.GREEN + "确认");
        confirmButton.setItemMeta(confirmMeta);

        ItemStack cancelButton = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancelButton.getItemMeta();
        cancelMeta.setDisplayName(ChatColor.RED + "点错了");
        cancelButton.setItemMeta(cancelMeta);

        gui.setItem(3, confirmButton);
        gui.setItem(5, cancelButton);

        player.openInventory(gui);
        playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL);
    }

    private void openCoordinateOptionsGui(Player player, CoordinateRecord record) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.BOLD + "坐标选项");

        ItemStack teleportButton = new ItemStack(Material.ENDER_PEARL);
        ItemMeta teleportMeta = teleportButton.getItemMeta();
        teleportMeta.setDisplayName(ChatColor.AQUA + "传送至这里");
        teleportButton.setItemMeta(teleportMeta);

        ItemStack deleteButton = new ItemStack(Material.BARRIER);
        ItemMeta deleteMeta = deleteButton.getItemMeta();
        deleteMeta.setDisplayName(ChatColor.RED + "删除这个记录点");
        deleteButton.setItemMeta(deleteMeta);

        ItemStack editButton = new ItemStack(Material.NAME_TAG);
        ItemMeta editMeta = editButton.getItemMeta();
        editMeta.setDisplayName(ChatColor.YELLOW + "更改备注名字");
        editButton.setItemMeta(editMeta);

        ItemStack backButton = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backButton.getItemMeta();
        backMeta.setDisplayName(ChatColor.GRAY + "返回");
        backButton.setItemMeta(backMeta);

        gui.setItem(2, teleportButton);
        gui.setItem(4, deleteButton);
        gui.setItem(6, editButton);
        gui.setItem(8, backButton);

        player.openInventory(gui);
        player.setMetadata("selectedRecord", new FixedMetadataValue(this, record));
        playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    private void openConfirmDeleteGui(Player player, CoordinateRecord record) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.BOLD + "确认要删除该点？");

        ItemStack confirmButton = new ItemStack(Material.LIME_WOOL);
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.GREEN + "确认删除");
        confirmButton.setItemMeta(confirmMeta);

        ItemStack cancelButton = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancelButton.getItemMeta();
        cancelMeta.setDisplayName(ChatColor.RED + "点错了");
        cancelButton.setItemMeta(cancelMeta);

        gui.setItem(3, confirmButton);
        gui.setItem(5, cancelButton);

        player.openInventory(gui);
        player.setMetadata("pendingDelete", new FixedMetadataValue(this, record));
        playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL);
    }

    private void initiateTeleport(Player player, Location location) {
        player.closeInventory();

        if (!enableTeleportWait || teleportWaitTime <= 0) {
            player.teleport(location);
            player.sendMessage(ChatColor.GREEN + "传送成功！");
            playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT);
            return;
        }

        if (teleportTasks.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "已在传送倒计时中，请稍候！");
            return;
        }

        Location startLocation = player.getLocation();
        BukkitTask task = new BukkitRunnable() {
            int countdown = teleportWaitTime;

            @Override
            public void run() {
                if (countdown <= 0) {
                    player.teleport(location);
                    player.sendMessage(ChatColor.GREEN + "传送成功！");
                    playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT);
                    teleportTasks.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                player.sendMessage(ChatColor.YELLOW + "传送倒计时 " + countdown + " 秒... 请勿移动！");
                playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);

                if (player.getLocation().distance(startLocation) > 2) {
                    player.sendMessage(ChatColor.RED + "传送已取消，因为你移动了！");
                    playSound(player, Sound.BLOCK_ANVIL_LAND);
                    teleportTasks.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                countdown--;
            }
        }.runTaskTimer(this, 0L, 20L);

        teleportTasks.put(player.getUniqueId(), task);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory gui = event.getInventory();
        String title = event.getView().getTitle();

        if (title.equals(ChatColor.BOLD + "坐标记录菜单")) {
            event.setCancelled(true);
            int slot = event.getSlot();

            if (slot < 45 && gui.getItem(slot) != null) {
                CoordinateRecord record = playerCoordinates.get(player.getUniqueId()).get(slot);
                openCoordinateOptionsGui(player, record);
            } else if (slot == 53) {
                player.closeInventory();
                openConfirmAddGui(player);
            } else if (slot == 45) {
                player.closeInventory();
            }
        }

        else if (title.equals(ChatColor.BOLD + "确认要添加一个吗？")) {
            event.setCancelled(true);
            if (event.getSlot() == 3) {
                player.closeInventory();
                openSignGui(player);
            } else if (event.getSlot() == 5) {
                player.closeInventory();
                openMainGui(player);
            }
        }

        else if (title.equals(ChatColor.BOLD + "坐标选项")) {
            event.setCancelled(true);
            CoordinateRecord record = (CoordinateRecord) player.getMetadata("selectedRecord").get(0).value();

            if (event.getSlot() == 2) {
                if (player.hasPermission("angecoordinate.admin") && adminBypassLimit || canTeleport(player)) {
                    initiateTeleport(player, record.getLocation());
                    addTeleportCooldown(player);
                } else {
                    long cooldownTime = getRemainingCooldown(player);
                    player.sendMessage(ChatColor.RED + "传送冷却中，剩余时间：" + cooldownTime + " 秒。5分钟内最多传送" + maxTeleportsPer5Min + "次！");
                }
            } else if (event.getSlot() == 4) {
                openConfirmDeleteGui(player, record);
            } else if (event.getSlot() == 6) {
                player.closeInventory();
                player.setMetadata("editingRecord", new FixedMetadataValue(this, record));
                openSignGui(player);
            } else if (event.getSlot() == 8) {
                openMainGui(player);
            }
        }

        else if (title.equals(ChatColor.BOLD + "确认要删除该点？")) {
            event.setCancelled(true);
            CoordinateRecord record = (CoordinateRecord) player.getMetadata("pendingDelete").get(0).value();

            if (event.getSlot() == 3) {
                playerCoordinates.get(player.getUniqueId()).remove(record);
                savePlayerCoordinates(player);
                player.sendMessage(ChatColor.YELLOW + "坐标记录已删除！");
                playSound(player, Sound.ENTITY_ITEM_BREAK);
                player.closeInventory();
                openMainGui(player);
            } else if (event.getSlot() == 5) {
                player.removeMetadata("pendingDelete", this);
                player.closeInventory();
                openMainGui(player);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (enableSneakFKey && player.isSneaking()) {
            openMainGui(player);
        }
    }

    public void openSignGui(Player player) {
        waitingForSignInput.put(player.getUniqueId(), true);

        Location baseLocation = player.getLocation().getBlock().getLocation();
        Block baseBlock = baseLocation.getBlock();

        if (nonSolidBlocks.contains(baseBlock.getType())) {
            baseLocation.add(0, 1, 0);
        }

        baseBlock = baseLocation.getBlock();
        baseBlock.setType(Material.OAK_SIGN);
        Sign sign = (Sign) baseBlock.getState();
        sign.update();

        signLocations.put(player.getUniqueId(), baseLocation);
        player.openSign(sign);
    }

    private void deleteTemporarySign(Player player) {
        Location signLocation = signLocations.remove(player.getUniqueId());
        if (signLocation != null) {
            Block block = signLocation.getBlock();
            if (block.getType() == Material.OAK_SIGN || block.getType() == Material.OAK_WALL_SIGN) {
                block.setType(Material.AIR);
                block.getState().update(true, false);
            }
        }
    }

    private boolean canTeleport(Player player) {
        List<Long> cooldowns = playerTeleportCooldowns.getOrDefault(player.getUniqueId(), new ArrayList<>());
        long now = System.currentTimeMillis();
        cooldowns.removeIf(cooldown -> now - cooldown > 300000);
        return cooldowns.size() < maxTeleportsPer5Min;
    }

    private long getRemainingCooldown(Player player) {
        List<Long> cooldowns = playerTeleportCooldowns.getOrDefault(player.getUniqueId(), new ArrayList<>());
        long now = System.currentTimeMillis();
        return cooldowns.isEmpty() ? 0 : (300 - (now - cooldowns.get(cooldowns.size() - 1)) / 1000);
    }

    private void addTeleportCooldown(Player player) {
        List<Long> cooldowns = playerTeleportCooldowns.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        cooldowns.add(System.currentTimeMillis());
    }

    private void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    static class CoordinateRecord {
        private String name;
        private final Location location;

        public CoordinateRecord(String name, Location location) {
            this.name = name;
            this.location = location;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Location getLocation() {
            return location;
        }
    }
}
