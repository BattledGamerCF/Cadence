package dev.cadence.shaders.cadence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/**
 * Cadence self-test (scene=busy): a repeatable entity-heavy scene around the player, built with server commands relative to
 * where the player stands and looks (run it on a copy of a world: CADENCE_WORLD_SNAPSHOT).
 *
 * <ul>
 * <li>A sealed stone room 18-28 blocks under the player, 20-44 blocks ahead: 120 mobs (zombies, skeletons, villagers,
 * cows), 63 chests and 63 lit campfires (smoke particles). Hidden by the ground: what culling should skip.</li>
 * <li>On the surface ahead, in view: 60 animals, 80 dropped items, 6 campfires.</li>
 * <li>On the surface behind the player: 60 animals (out of view; only their shadows can matter).</li>
 * </ul>
 */
public final class BusyScene {
	private BusyScene() {
	}

	public static void build(Minecraft mc) {
		var server = mc.getSingleplayerServer();
		if (server == null) return;
		String self = "execute as " + mc.getUser().getName() + " at @s rotated ~ 0 ";
		List<String> commands = new ArrayList<>();
		commands.add(self + "run fill ^-13 ^-29 ^19 ^13 ^-17 ^45 minecraft:stone hollow");
		commands.add(self + "run fill ^-10 ^-28 ^22 ^10 ^-28 ^24 minecraft:chest");
		commands.add(self + "run fill ^-10 ^-28 ^40 ^10 ^-28 ^42 minecraft:campfire");
		commands.add(self + "run fill ^-12 ^-18 ^20 ^12 ^-18 ^44 minecraft:glowstone");
		String[] hidden = {"zombie", "skeleton", "villager", "cow"};
		for (int i = 0; i < 120; i++) {
			int dx = -10 + (i % 11) * 2, dz = 26 + (i / 11) % 12;
			commands.add(self + "run summon minecraft:" + hidden[i % 4] + " ^" + dx + " ^-27 ^" + dz + " {PersistenceRequired:1b}");
		}
		String[] animals = {"cow", "sheep", "pig", "chicken"};
		for (int i = 0; i < 60; i++) {
			int dx = -14 + (i % 15) * 2, dz = 10 + (i / 15) * 3;
			commands.add(self + "positioned ^" + dx + " ^ ^" + dz + " positioned over motion_blocking run summon minecraft:"
				+ animals[i % 4] + " ~ ~ ~ {PersistenceRequired:1b,NoAI:1b}");
			commands.add(self + "positioned ^" + dx + " ^ ^" + (-dz - 20) + " positioned over motion_blocking run summon minecraft:"
				+ animals[i % 4] + " ~ ~ ~ {PersistenceRequired:1b,NoAI:1b}");
		}
		for (int i = 0; i < 80; i++) {
			int dx = -8 + (i % 9) * 2, dz = 6 + (i / 9);
			commands.add(self + "positioned ^" + dx + " ^ ^" + dz + " positioned over motion_blocking run summon minecraft:item ~ ~ ~ "
				+ "{Item:{id:\"minecraft:cobblestone\",count:1},PickupDelay:32767,Age:-32768}");
		}
		for (int i = 0; i < 6; i++)
			commands.add(self + "positioned ^" + (-10 + i * 4) + " ^ ^14 positioned over motion_blocking run setblock ~ ~ ~ minecraft:campfire");
		server.execute(() -> {
			int failed = 0;
			for (String command : commands) {
				try {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
				} catch (RuntimeException e) {
					failed++;
				}
			}
			LoggerFactory.getLogger("cadence-selftest").info("[selftest] busy scene: {} commands, {} failed", commands.size(), failed);
		});
	}
}
