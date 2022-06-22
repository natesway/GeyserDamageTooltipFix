package me.jacob.damagetooltipfix;

import com.comphenix.protocol.PacketType.Play.Client;
import com.comphenix.protocol.PacketType.Play.Server;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.google.common.collect.Multimap;
import lombok.val;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.geysermc.api.Geyser;
import org.geysermc.api.GeyserApiBase;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class GeyserDamageTooltipFix extends JavaPlugin {

	private final NamespacedKey loreKey = new NamespacedKey(this, "lore_index");

	private GeyserApiBase api;
	private String lore;

	private boolean isCombatUpdate;

	public void onEnable() {
		api = Geyser.api();

		try {
			Material.valueOf("ELYTRA");
			isCombatUpdate = true;
		} catch(Exception e) {
			isCombatUpdate = false;
		}

		val reloadCommand = getCommand("reloaddamagetooltipfix");
		reloadCommand.setTabCompleter((sender, cmd, label, args) -> Collections.emptyList());
		reloadCommand.setExecutor((sender, cmd, label, args) -> {
			if(!(sender instanceof ConsoleCommandSender)) {
				sender.sendMessage("Only console can run this!");
				return true;
			}

			reloadConfig();
			resetLore();

			sender.sendMessage("Reloaded config!");
			return true;
		});

		resetLore();
		new BukkitRunnable() {
			@Override
			public void run() {
				ProtocolManager manager = ProtocolLibrary.getProtocolManager();

				manager.addPacketListener(new PacketListener());
			}
		}.runTask(this);
	}

	private void resetLore() {
		val config = getConfig();

		lore = config.getString("lore");

		if(lore == null || lore.isEmpty()) {
			getLogger().warning("Lore is blank, resetting value");
			config.set("lore", "&9Actual Damage: %damage%");
			saveConfig();
			resetLore();
			return;
		}

		lore = ChatColor.translateAlternateColorCodes('&', lore);
		lore = lore.replaceFirst("%damage%", "%s");
	}

	private final class PacketListener extends PacketAdapter {
		private PacketListener() {
			super(
					GeyserDamageTooltipFix.this,
					Server.WINDOW_ITEMS, Server.SET_SLOT, Client.SET_CREATIVE_SLOT
			);
		}

		public void onPacketSending(PacketEvent event) {
			val player = event.getPlayer();

			if(api.connectionByUuid(player.getUniqueId()) == null) {
				// Java player
				return;
			}

			val packet = event.getPacket().deepClone();

			if(packet.getType() == Server.SET_SLOT) {
				StructureModifier<ItemStack> items = packet.getItemModifier();

				items.write(0, fixItem(items.read(0)));
			} else {
				StructureModifier<List<ItemStack>> items = packet.getItemListModifier();

				items.write(0,
						items.read(0)
								.stream()
								.map(this::fixItem)
								.collect(Collectors.toList()));
			}

			event.setPacket(packet);
		}

		private ItemStack fixItem(ItemStack item) {
			if(item == null)
				return null;

			ItemMeta meta = item.getItemMeta();

			if(meta == null)
				return item;
			if(item.getType().name().contains("SWORD") &&
					(!isCombatUpdate || !meta.hasEnchant(Enchantment.DAMAGE_ALL)))
				return item;

			double damage = getDamage(item);
			if(damage == -1)
				return item; // item is not a tool

			List<String> newLore =
					Optional.ofNullable(meta.getLore())
							.orElseGet(ArrayList::new);

			meta.getPersistentDataContainer().set(
					loreKey,
					PersistentDataType.INTEGER,
					newLore.size()
			);

			DecimalFormat decimalFormat = new DecimalFormat("##.#");
			String damageString = decimalFormat.format(damage + 1);

			newLore.add(String.format(lore, damageString));
			meta.setLore(newLore);
			item.setItemMeta(meta);

			return item;
		}

		private double getDamage(ItemStack item) {
			Material type = item.getType();

			Multimap<Attribute, AttributeModifier> map = type.getDefaultAttributeModifiers(EquipmentSlot.HAND);

			for(Map.Entry<Attribute, AttributeModifier> entry : map.entries()) {
				if(entry.getKey() == Attribute.GENERIC_ATTACK_DAMAGE) {
					double baseDamage = entry.getValue().getAmount();

					ItemMeta meta = item.getItemMeta();

					if(meta == null)
						return baseDamage;

					int sharpnessLevel = meta.getEnchantLevel(Enchantment.DAMAGE_ALL);

					if(sharpnessLevel == 0)
						return baseDamage;

					if(isCombatUpdate) {
						return baseDamage + (sharpnessLevel * .5 + .5);
					} else {
						return baseDamage + (sharpnessLevel * 1.25);
					}
				}
			}

			return -1;
		}

		public void onPacketReceiving(PacketEvent event) {
			val packet = event.getPacket();

			ItemStack item = packet.getItemModifier().read(0);

			if(item == null)
				return;

			ItemMeta meta = item.getItemMeta();

			if(meta == null)
				return;

			PersistentDataContainer container = meta.getPersistentDataContainer();

			if(!container.has(loreKey, PersistentDataType.INTEGER))
				return;

			int index = container.get(loreKey, PersistentDataType.INTEGER);
			List<String> lore = meta.getLore();

			if(lore == null || index >= lore.size())
				return;

			lore.remove(index);
			meta.setLore(lore);
			item.setItemMeta(meta);
		}
	}
}
