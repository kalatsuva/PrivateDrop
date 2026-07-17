package kalatsuva.privatedrop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DroppedItemListener implements Listener {
    private static final long LIFETIME_TICKS = 20L * 60L * 5L;
    // この距離内にある同一所有者のアイテムをバンドルへ統合する
    private static final double BUNDLE_RADIUS = 1.5;

    private final JavaPlugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey remainingKey;
    private final NamespacedKey contentsKey;
    private final NamespacedKey countKey;

    public DroppedItemListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "dropped_item_owner");
        this.remainingKey = new NamespacedKey(plugin, "remaining_ticks");
        this.contentsKey = new NamespacedKey(plugin, "bundle_contents");
        this.countKey = new NamespacedKey(plugin, "bundle_count");
    }

    // Qキー・インベントリ枠外クリック・カーソルに持ったまま閉じた場合はすべてこのイベントを通る
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        protect(event.getItemDrop(), event.getPlayer());
    }

    // 死亡時ドロップは PlayerDropItemEvent を通らないため、標準のドロップを止めて自前でスポーンさせる
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();

        for(ItemStack stack : drops) {
            final Item item = player.getWorld().dropItemNaturally(player.getLocation(), stack);
            protect(item, player);
        }
    }

    // 所有者が異なるアイテム同士の合体を防ぐ(合体すると所有権とタイマーが片方に飲み込まれるため)
    @EventHandler(ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        // バンドルの中身はPDCにあるためバニラの合体では引き継がれない。統合はDespawnTimer側で行う
        if(isBundle(event.getEntity()) || isBundle(event.getTarget())) {
            event.setCancelled(true);
            return;
        }

        final String owner = event.getEntity().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        final String targetOwner = event.getTarget().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if(owner == null ? targetOwner != null : !owner.equals(targetOwner)) {
            event.setCancelled(true);
        }
    }

    private void protect(Item item, Player owner) {
        item.setUnlimitedLifetime(true);
        item.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        item.getPersistentDataContainer().set(remainingKey, PersistentDataType.LONG, LIFETIME_TICKS);
        item.setGlowing(true);

        final TextDisplay display = createTimerDisplay(item, owner.getName());
        item.addPassenger(display);

        new DespawnTimer(item, display, owner.getName()).runTaskTimer(plugin, 0, 20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        final Item item = event.getItem();

        // バンドル自体はどのインベントリにも入れない。所有者が拾ったときは中身だけを展開する
        if(isBundle(item)) {
            event.setCancelled(true);

            final String ownerId = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if(event.getEntity() instanceof Player player
                    && ownerId != null
                    && player.getUniqueId().equals(UUID.fromString(ownerId))) {
                unpack(item, player);
            }
            return;
        }

        if(!(event.getEntity() instanceof Player player)) return;

        final String ownerId = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if(ownerId == null) return;

        if(!player.getUniqueId().equals(UUID.fromString(ownerId))) {
            event.setCancelled(true);
            return;
        }

        removeTimerDisplays(item);
    }

    // ホッパー等による回収を防ぐ
    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if(isBundle(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // 中身をインベントリへ展開し、入り切らなかった分はバンドルに残す(remainingKeyは触らないので寿命は維持される)
    private void unpack(Item bundle, Player player) {
        final List<ItemStack> leftovers = new ArrayList<>();
        boolean pickedAny = false;

        for(ItemStack stack : getContents(bundle)) {
            final int amount = stack.getAmount();
            final var rejected = player.getInventory().addItem(stack);

            int rejectedAmount = 0;
            for(ItemStack rest : rejected.values()) {
                rejectedAmount += rest.getAmount();
                leftovers.add(rest);
            }
            if(rejectedAmount < amount) {
                pickedAny = true;
            }
        }

        if(pickedAny) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.0f);
        }

        if(leftovers.isEmpty()) {
            removeTimerDisplays(bundle);
            bundle.remove();
        } else {
            setContents(bundle, leftovers);
            // 満杯のプレイヤーが上に立ち続けたときの毎tick再試行を抑える
            bundle.setPickupDelay(20);
        }
    }

    private boolean isBundle(Item item) {
        return item.getPersistentDataContainer().has(contentsKey, PersistentDataType.BYTE_ARRAY);
    }

    private List<ItemStack> getContents(Item bundle) {
        final byte[] bytes = bundle.getPersistentDataContainer().get(contentsKey, PersistentDataType.BYTE_ARRAY);
        if(bytes == null) return new ArrayList<>();
        return new ArrayList<>(List.of(ItemStack.deserializeItemsFromBytes(bytes)));
    }

    private void setContents(Item bundle, List<ItemStack> contents) {
        int total = 0;
        for(ItemStack stack : contents) {
            total += stack.getAmount();
        }

        bundle.getPersistentDataContainer().set(contentsKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(contents));
        bundle.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, total);
    }

    private static void removeTimerDisplays(Item item) {
        for(Entity passenger : item.getPassengers()) {
            if(passenger instanceof TextDisplay) {
                passenger.remove();
            }
        }
    }

    private TextDisplay createTimerDisplay(Item item, String ownerName) {
        return item.getWorld().spawn(item.getLocation(), TextDisplay.class, entity -> {
            Transformation t = new Transformation(
                    new Vector3f(0.f, 0.4f, 0.f),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(1, 1, 1),
                    new AxisAngle4f(0, 0, 0, 1)
            );

            entity.setTransformation(t);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.text(ownerLine(ownerName).append(formatTime(LIFETIME_TICKS)));
        });
    }

    private static Component ownerLine(String ownerName) {
        return Component.text(ownerName)
                .color(TextColor.color(0x55ffff))
                .append(Component.newline());
    }

    private static Component formatTime(long ticksLeft) {
        final long seconds = ticksLeft / 20;
        return Component.text(String.format("%d:%02d", seconds / 60, seconds % 60)).color(TextColor.color(0xffff55));
    }

    private class DespawnTimer extends BukkitRunnable {
        private final Item item;
        private final TextDisplay display;
        private final String ownerName;
        private long remaining;

        private DespawnTimer(Item item, TextDisplay display, String ownerName) {
            this.item = item;
            this.display = display;
            this.ownerName = ownerName;
            this.remaining = LIFETIME_TICKS;
        }

        @Override
        public void run() {
            if(!item.isValid()) {
                display.remove();
                cancel();
                return;
            }

            remaining -= 20;

            if(remaining <= 0) {
                item.remove();
                display.remove();
                cancel();
                return;
            }

            absorbNearby();

            item.getPersistentDataContainer().set(remainingKey, PersistentDataType.LONG, remaining);
            display.text(displayText());
        }

        // 付近の同一所有者アイテムをこのエンティティへ統合し、エンティティ数を減らす
        private void absorbNearby() {
            final String owner = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if(owner == null) return;

            for(Entity nearby : item.getNearbyEntities(BUNDLE_RADIUS, BUNDLE_RADIUS, BUNDLE_RADIUS)) {
                if(!(nearby instanceof Item victim) || !victim.isValid()) continue;
                if(!owner.equals(victim.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) continue;
                absorb(victim);
            }
        }

        private void absorb(Item victim) {
            final List<ItemStack> contents = isBundle(item)
                    ? getContents(item)
                    : new ArrayList<>(List.of(item.getItemStack()));

            if(isBundle(victim)) {
                contents.addAll(getContents(victim));
            } else {
                contents.add(victim.getItemStack());
            }

            // 寿命は最も長いアイテムに合わせる
            final Long victimRemaining = victim.getPersistentDataContainer().get(remainingKey, PersistentDataType.LONG);
            if(victimRemaining != null && victimRemaining > remaining) {
                remaining = victimRemaining;
            }

            removeTimerDisplays(victim);
            victim.remove();

            if(!isBundle(item)) {
                item.setItemStack(ItemStack.of(Material.BUNDLE));
            }
            setContents(item, contents);
        }

        private Component displayText() {
            Component text = ownerLine(ownerName).append(formatTime(remaining));

            final Integer count = item.getPersistentDataContainer().get(countKey, PersistentDataType.INTEGER);
            if(count != null) {
                text = text.append(Component.text(" ×" + count).color(TextColor.color(0xffffff)));
            }
            return text;
        }
    }
}
