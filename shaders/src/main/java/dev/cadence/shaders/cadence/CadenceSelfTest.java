package dev.cadence.shaders.cadence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence self-test for development runs only: runs a list of shader switches in a world with no one at the keyboard, and
 * records what happened. Off unless -Dcadence.selftest is set; never active in normal play.
 *
 * <p>-Dcadence.selftest=STEP,STEP,... where STEP is a pack file name in shaderpacks/ (optionally "@mode", see
 * ShaderSwitcher.reload), "off", "f3a" (vanilla's reload of every chunk, as F3+A), or "scale=0.75" (RenderScale). -Dcadence.selftest.settle=SECONDS (default 30) per step. After entering the world and after each
 * step it waits, then logs FPS (min/avg/max over the last 10 s), the terrain probe, and saves screenshots/selftest-N-STEP.png.
 * Then it quits the game. Lines start with "[selftest]".
 */
public final class CadenceSelfTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence-selftest");

	private CadenceSelfTest() {
	}

	public static void maybeStart() {
		String plan = System.getProperty("cadence.selftest");
		if (plan == null || plan.isBlank()) return;
		int settle = Integer.getInteger("cadence.selftest.settle", 30);
		List<String> steps = new ArrayList<>();
		for (String s : plan.split(",")) if (!s.isBlank()) steps.add(s.strip());
		// The steps save the pack, profile and scale like the player would; the test puts the config back as it found it.
		byte[] config;
		try {
			config = java.nio.file.Files.readAllBytes(ShaderToggle.readableConfigFile());
		} catch (java.io.IOException e) {
			config = null;
		}
		byte[] original = config;
		Thread t = new Thread(() -> run(steps, settle, original), "cadence-selftest");
		t.setDaemon(true);
		t.start();
		LOGGER.info("[selftest] armed: {} ({} s per step)", steps, settle);
	}

	private static void run(List<String> steps, int settle, byte[] originalConfig) {
		Minecraft mc = Minecraft.getInstance();
		try {
			for (int i = 0; i < 300 && (mc.level == null || mc.player == null); i++) sleep(1000);
			if (mc.level == null) {
				LOGGER.info("[selftest] FAIL: no world after 300 s");
				return;
			}
			LOGGER.info("[selftest] in world; pack={}", ShaderSwitcher.active().isEmpty() ? "off" : ShaderSwitcher.active());
			// Comparable numbers: the same noon light and clear sky for every step (night and rain change a pack's cost).
			var server = mc.getSingleplayerServer();
			if (server != null) {
				server.execute(() -> {
					for (String command : new String[] {"time set 6000", "weather clear", "gamerule advance_time false",
						"gamerule advance_weather false"}) {
						try {
							server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
						} catch (RuntimeException e) {
							LOGGER.info("[selftest] '{}' failed: {}", command, e.toString());
						}
					}
				});
				LOGGER.info("[selftest] world frozen at noon, clear");
			}
			observe(mc, 0, "start", settle);
			for (int i = 0; i < steps.size(); i++) {
				String step = steps.get(i);
				String lower = step.toLowerCase(Locale.ROOT);
				LOGGER.info("[selftest] step {}: {}", i + 1, step);
				if (lower.equals("f3a")) {
					mc.execute(() -> mc.levelExtractor.allChanged());
				} else if (lower.startsWith("profile=")) {
					String profile = step.substring(8).strip();
					String chosen = profile.equalsIgnoreCase("default") ? "" : profile;
					mc.execute(() -> {
						ShaderToggle.saveProfile(ShaderToggle.selectedPack(), chosen);
						ShaderSwitcher.reload();
					});
				} else if (lower.startsWith("compact=")) {
					boolean on = lower.endsWith("on") || lower.endsWith("true");
					mc.execute(() -> {
						System.setProperty("cadence.shaders.compactTerrainVertices", Boolean.toString(on));
						ShaderSwitcher.reload();
					});
				} else if (lower.startsWith("upscaler=")) {
					String mode = lower.substring(9);
					mc.execute(() -> RenderScale.applyUpscaler(mode));
				} else if (lower.startsWith("rd=")) {
					// "rd=N": change the render distance as the options screen does (vanilla rebuilds the view area)
					int rd = Integer.parseInt(lower.substring(3));
					mc.execute(() -> {
						mc.options.renderDistance().set(rd);
						mc.options.save();
					});
				} else if (lower.startsWith("dim=")) {
					// "dim=the_nether|overworld|the_end": move the player to that dimension (near its origin)
					String dim = lower.substring(4);
					String y = dim.equals("the_nether") ? "70" : dim.equals("the_end") ? "70" : "100";
					String command = "execute in minecraft:" + dim + " run tp " + mc.getUser().getName() + " 8 " + y + " 8";
					var dimServer = mc.getSingleplayerServer();
					if (dimServer != null) dimServer.execute(() -> dimServer.getCommands().performPrefixedCommand(dimServer.createCommandSourceStack().withSuppressedOutput(), command));
				} else if (lower.startsWith("turn=") || lower.startsWith("time=")) {
					// "turn=DEG": rotate the player's view; "time=T": set the (frozen) time of day (sun angle)
					String arg = lower.substring(5);
					String command = lower.startsWith("turn=")
						? "execute as " + mc.getUser().getName() + " at @s run tp @s ~ ~ ~ ~" + arg + " ~"
						: "time set " + arg;
					var cmdServer = mc.getSingleplayerServer();
					if (cmdServer != null) cmdServer.execute(() -> cmdServer.getCommands().performPrefixedCommand(cmdServer.createCommandSourceStack().withSuppressedOutput(), command));
				} else if (lower.startsWith("tp=")) {
					// "tp=DX": move the player DX blocks east (new chunks to build, old tasks cancelled; the camera moves)
					String dx = lower.substring(3);
					var tpServer = mc.getSingleplayerServer();
					if (tpServer != null) tpServer.execute(() -> tpServer.getCommands().performPrefixedCommand(tpServer.createCommandSourceStack(),
						"execute as " + mc.getUser().getName() + " at @s run tp @s ~" + dx + " ~ ~"));
				} else if (lower.startsWith("entitycull=")) {
					boolean on = lower.endsWith("on") || lower.endsWith("true");
					mc.execute(() -> EntityCull.set(on));
				} else if (lower.startsWith("shadowrate=")) {
					int n = Integer.parseInt(lower.substring(11));
					mc.execute(() -> ShadowReuse.setRate(n));
				} else if (lower.equals("occlusion")) {
					mc.execute(() -> OcclusionEstimate.run(mc));
				} else if (lower.startsWith("ab=")) {
					// "ab=FEATURE:SECONDS": flips FEATURE every 2 s for SECONDS and compares GPU/interval ms of the 1-s windows that
					// fall wholly in each state (thermal drift is slow next to 2 s, so both states see the same Mac)
					String[] a = lower.substring(3).split(":");
					dev.cadence.shaders.cadence.mesh.MeshStateCache.drawListModes();
					abTest(mc, a[0], Integer.parseInt(a[1]));
					LOGGER.info("[selftest] ab {} draw lists: {}", a[0], dev.cadence.shaders.cadence.mesh.MeshStateCache.drawListModes());
				} else if (lower.startsWith("join=")) {
					// "join=WORLD": open a singleplayer world from the title screen (after "leave")
					String world = step.substring(5);
					mc.execute(() -> mc.createWorldOpenFlows().openWorld(world, () -> {}));
				} else if (lower.startsWith("feature=")) {
					// "feature=NAME:on|off": one of the A/B features (abSet) set for the following steps (picture comparisons)
					String[] f = lower.substring(8).split(":");
					abSet(mc, f[0], f.length < 2 || f[1].equals("on") || f[1].equals("true"));
				} else if (lower.startsWith("lan=")) {
					// "lan=PORT": this world opened to LAN on PORT (cheats on, no account check: the dev accounts can't sign in) for a
					// second client (tools/cadence-guest.sh) to join as another player
					int port = Integer.parseInt(lower.substring(4));
					var host = mc.getSingleplayerServer();
					if (host == null) LOGGER.info("[selftest] lan: no integrated server");
					else mc.execute(() -> {
						host.setUsesAuthentication(false);
						boolean ok = host.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, true, port);
						LOGGER.info("[selftest] lan: published on port {}: {}", port, ok);
					});
				} else if (lower.equals("players")) {
					// "players": who this client sees in the world (multiplayer checks)
					mc.execute(() -> LOGGER.info("[selftest] players: {} (server here: {})", mc.level == null ? "no level"
						: mc.level.players().stream().map(p -> p.getName().getString()).toList(), mc.getSingleplayerServer() != null));
				} else if (lower.equals("leave")) {
					// "leave": save and quit to the title screen, as the pause menu's button does (what's left in memory after a
					// world is the next steps' business)
					mc.execute(() -> mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
				} else if (lower.startsWith("key=")) {
					// "key=NAME:down|up": the key binding named NAME (or else the first whose name contains it; for example
					// "zoomify.key.zoom") held or let go, as if its key were (other mods' key bindings in compatibility tests)
					String[] k = lower.substring(4).split(":");
					boolean down = k.length < 2 || k[1].equals("down");
					mc.execute(() -> {
						net.minecraft.client.KeyMapping found = null;
						for (var mapping : mc.options.keyMappings) {
							String name = mapping.getName().toLowerCase(java.util.Locale.ROOT);
							if (name.equals(k[0])) { found = mapping; break; }
							if (found == null && name.contains(k[0])) found = mapping;
						}
						if (found == null) { LOGGER.info("[selftest] key {}: no such key binding", k[0]); return; }
						if (k.length > 1 && k[1].equals("press")) {
							// "key=NAME:press": one press, as tapping the key bound to it
							net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(found.saveString()));
							LOGGER.info("[selftest] key {} pressed", found.getName());
							return;
						}
						found.setDown(down);
						LOGGER.info("[selftest] key {} {}", found.getName(), down ? "down" : "up");
					});
				} else if (lower.startsWith("modmenu=")) {
					// "modmenu=MODID": the screen Mod Menu's settings button opens for that mod (through Mod Menu's own lookup), or
					// "none" in the log when it has none
					String id = lower.substring(8);
					mc.execute(() -> {
						try {
							Class<?> modMenu = Class.forName("com.terraformersmc.modmenu.ModMenu");
							Object screen = modMenu.getMethod("getConfigScreen", String.class, net.minecraft.client.gui.screens.Screen.class)
								.invoke(null, id, mc.gui.screen());
							LOGGER.info("[selftest] modmenu {}: {}", id, screen == null ? "none" : screen.getClass().getSimpleName());
							if (screen != null) mc.gui.setScreen((net.minecraft.client.gui.screens.Screen) screen);
						} catch (ReflectiveOperationException e) {
							LOGGER.info("[selftest] modmenu {}: no Mod Menu ({})", id, e.toString());
						}
					});
				} else if (lower.equals("packcheck")) {
					// "packcheck": Video Settings opened (the game pauses), then the pack check runs; the next steps wait it out
					mc.execute(() -> {
						mc.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options));
						boolean started = dev.cadence.shaders.cadence.packcheck.PackCheck.start(null);
						LOGGER.info("[selftest] pack check started: {}", started);
					});
				} else if (lower.startsWith("screen=")) {
					// "screen=video": open Video Settings (its screenshot shows the Cadence rows); "screen=close": close it
					boolean open = lower.endsWith("video");
					if (lower.endsWith("packsettings")) mc.execute(() -> mc.gui.setScreen(PackSettingsScreen.open(null)));
					else if (lower.endsWith("=packs")) mc.execute(() -> mc.gui.setScreen(new ShaderPacksScreen(
						new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options))));
					else if (lower.startsWith("screen=packpage:")) {
						// "screen=packpage:N": the open settings page's Nth page button (1 = first) pressed
						int n = Integer.parseInt(lower.substring(16));
						mc.execute(() -> PackSettingsScreen.pressPage(mc.gui.screen(), n));
					} else if (lower.startsWith("screen=packclick:")) {
						// "screen=packclick:N": the open settings page's Nth option button pressed (one value on)
						int n = Integer.parseInt(lower.substring(17));
						mc.execute(() -> PackSettingsScreen.pressOption(mc.gui.screen(), n));
					} else if (lower.endsWith("packclose")) mc.execute(() -> mc.gui.screen().onClose());
					else if (lower.endsWith("pause")) mc.execute(() -> mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
					else if (lower.endsWith("inventory")) mc.execute(() -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
					else if (lower.endsWith("offer")) mc.execute(() -> mc.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.offer(null)));
					else if (lower.endsWith("goal")) mc.execute(() -> mc.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.goal(null)));
					else if (lower.endsWith("setupmax")) {
						var r = dev.cadence.shaders.cadence.setup.QuickSetup.run(stage -> { }, dev.cadence.shaders.cadence.setup.QuickSetup.Goal.MAX_FPS);
						mc.execute(() -> mc.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.results(r, null)));
					}
					else if (lower.endsWith("setupresult")) {
						var r = dev.cadence.shaders.cadence.setup.QuickSetup.run(stage -> { });
						mc.execute(() -> mc.gui.setScreen(dev.cadence.shaders.cadence.setup.QuickSetupScreens.results(r, null)));
					} else mc.execute(() -> mc.gui.setScreen(open
						? new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options) : null));
				} else if (lower.startsWith("packpick=")) {
					// "packpick=NAME": in the open Shader Packs list, the first pack whose name starts with NAME ("off" for Off) clicked
					String name = lower.substring(9);
					mc.execute(() -> LOGGER.info("[selftest] packpick {}: {}", name, ShaderPacksScreen.pick(mc.gui.screen(), name)));
				} else if (lower.startsWith("drop=")) {
					// "drop=PATH": PATH dropped on the open screen's window, as Finder would (a path relative to the game folder)
					java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(step.substring(5));
					mc.execute(() -> { if (mc.gui.screen() != null) mc.gui.screen().onFilesDrop(java.util.List.of(path)); });
				} else if (lower.startsWith("guiscale=")) {
					// "guiscale=N": GUI Scale N (0 = Auto), the window re-laid out as the option does
					int n = Integer.parseInt(lower.substring(9));
					mc.execute(() -> {
						mc.options.guiScale().set(n);
						mc.resizeGui();
					});
				} else if (lower.equals("pipestats")) {
					var P = dev.cadence.shaders.runtime.PackPipeline.class;
					LOGGER.info("[selftest] pack pipelines: screen {} in {} ms, geometry {} in {} ms",
						dev.cadence.shaders.runtime.PackPipeline.STAT_SCREEN_N.getAndSet(0), dev.cadence.shaders.runtime.PackPipeline.STAT_SCREEN_NS.getAndSet(0) / 1_000_000,
						dev.cadence.shaders.runtime.PackPipeline.STAT_GEOM_N.getAndSet(0), dev.cadence.shaders.runtime.PackPipeline.STAT_GEOM_NS.getAndSet(0) / 1_000_000);
				} else if (lower.startsWith("click=")) {
					// "click=TEXT": the open screen's first button whose label starts with TEXT pressed (as a mouse click)
					String want = step.substring(6).strip();
					mc.execute(() -> {
						var screen = mc.gui.screen();
						var w = screen == null ? null : findButton(screen.children(), want);
						if (w instanceof net.minecraft.client.gui.components.Button b && b.active) b.onPress(null);
						else LOGGER.info("[selftest] click: no active button starting with '{}'", want);
					});
				} else if (lower.startsWith("window=")) {
					// "window=WxH": the window resized to W x H (points), as dragging its corner does
					String[] wh = lower.substring(7).split("x");
					int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
					mc.execute(() -> mc.getWindow().setWindowed(w, h));
				} else if (lower.equals("fullscreen")) {
					// "fullscreen": toggles fullscreen as F11 does
					mc.execute(() -> {
						mc.options.fullscreen().set(!mc.options.fullscreen().get());  // (the option applies it to the window, as Video Settings does)
					});
				} else if (lower.startsWith("hover=")) {
					// "hover=TEXT": the mouse put on the open screen's first widget whose label starts with TEXT (its tooltip shows)
					String want = step.substring(6).strip();
					mc.execute(() -> hover(mc, want));
				} else if (lower.startsWith("scroll=")) {
					// "scroll=PX": the open options screen's list scrolled to PX
					double px = Double.parseDouble(lower.substring(7));
					mc.execute(() -> {
						try {
							var f = net.minecraft.client.gui.screens.options.OptionsSubScreen.class.getDeclaredField("list");
							f.setAccessible(true);
							Object l = f.get(mc.gui.screen());
							if (l instanceof net.minecraft.client.gui.components.AbstractScrollArea a) a.setScrollAmount(px);
						} catch (ReflectiveOperationException | RuntimeException e) {
							LOGGER.info("[selftest] scroll failed: {}", e.toString());
						}
					});
				} else if (lower.startsWith("cooling=")) {
					// "cooling=quiet|balanced|performance|off": Video Settings' Cooling
					dev.cadence.shaders.cadence.system.HeatControl.setCooling(dev.cadence.shaders.cadence.system.HeatControl.Cooling
						.valueOf(lower.substring(8).toUpperCase(java.util.Locale.ROOT)));
				} else if (lower.startsWith("thermal=")) {
					// "thermal=N": pretend macOS reports thermal state N (0-3), "thermal=real" to stop pretending
					String v = lower.substring(8);
					dev.cadence.shaders.cadence.system.HeatControl.fakeThermal = v.equals("real") ? -1 : Integer.parseInt(v);
				} else if (lower.startsWith("pressure=")) {
					String v = lower.substring(9);
					dev.cadence.shaders.cadence.system.MemoryGuard.fakePressure = v.equals("real") ? -1 : Integer.parseInt(v);
				} else if (lower.startsWith("heapcompact=")) {
					dev.cadence.shaders.cadence.memory.ChunkHeapCompactor.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("packeddraws=")) {
					dev.cadence.shaders.cadence.alloc.PackedDraws.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("hizvanilla=")) {
					dev.cadence.shaders.cadence.hiz.HiZ.vanilla = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("hiz=")) {
					dev.cadence.shaders.cadence.hiz.HiZ.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("meshcache=")) {
					dev.cadence.shaders.cadence.mesh.MeshStateCache.report("before " + lower);
					dev.cadence.shaders.cadence.mesh.MeshStateCache.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("meshlight=")) {
					dev.cadence.shaders.cadence.mesh.MeshStateCache.report("before " + lower);
					dev.cadence.shaders.cadence.mesh.MeshLightCache.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("meshtint=")) {
					dev.cadence.shaders.cadence.mesh.MeshStateCache.report("before " + lower);
					dev.cadence.shaders.cadence.mesh.MeshTintCache.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("releaseregions=")) {
					dev.cadence.shaders.cadence.mesh.MeshStateCache.releaseRegions = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("facepacked=")) {
					dev.cadence.shaders.cadence.mesh.MeshStateCache.report("before " + lower);
					dev.cadence.shaders.cadence.facecull.FaceCull.packedGroups = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("meshreport=")) {
					dev.cadence.shaders.cadence.mesh.MeshStateCache.report(lower.substring(11));
				} else if (lower.startsWith("tpbiome=")) {
					// "tpbiome=dark_forest": the player to the surface of the nearest such biome (within 6400 blocks), facing east
					String biome = lower.substring(8).strip();
					var srv = mc.getSingleplayerServer();
					if (srv != null) {
						String name = mc.getUser().getName();
						srv.execute(() -> {
							var level = srv.overworld();
							var player = srv.getPlayerList().getPlayerByName(name);
							if (player == null) return;
							var found = level.findClosestBiome3d(h -> h.unwrapKey().map(k -> k.identifier().getPath().equals(biome)).orElse(false),
								player.blockPosition(), 6400, 32, 64);
							if (found == null) {
								LOGGER.info("[selftest] tpbiome: no {} found", biome);
								return;
							}
							var p = found.getFirst();
							int y = level.getChunk(p).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX() & 15, p.getZ() & 15) + 2;
							srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(),
								"tp " + name + " " + p.getX() + " " + y + " " + p.getZ() + " -90 10");
							LOGGER.info("[selftest] tpbiome {}: {} {} {}", biome, p.getX(), y, p.getZ());
						});
					}
				} else if (lower.equals("leaves=smart") || lower.equals("leaves=detailed")) {
					boolean smart = lower.endsWith("smart");
					mc.execute(() -> dev.cadence.shaders.cadence.mesh.SmartLeaves.setEnabled(smart));
				} else if (lower.startsWith("leaves=")) {
					// "leaves=fancy|fast": the game's See-Through Leaves option (cutoutLeaves), chunks rebuilt
					boolean cut = lower.endsWith("fancy");
					mc.execute(() -> {
						mc.options.cutoutLeaves().set(cut);
						mc.levelExtractor.allChanged();
					});
				} else if (lower.equals("quicksetup") || lower.equals("quicksetup=apply")) {
					// "quicksetup": Quick Setup's test and recommendations, logged; "=apply" also applies them
					var r = dev.cadence.shaders.cadence.setup.QuickSetup.run(stage -> LOGGER.info("[selftest] quick setup: {}", stage));
					for (var c : r.changes()) LOGGER.info("[selftest] quick setup recommends {}: {} -> {} ({})", c.what(), c.from(), c.to(), c.why());
					LOGGER.info("[selftest] quick setup: measured {} gpu {} cpu {} tier {}", r.measured(), String.format(Locale.ROOT, "%.2f", r.gpuScore()),
						String.format(Locale.ROOT, "%.2f", r.cpuScore()), r.tier());
					if (lower.endsWith("apply")) mc.execute(() -> dev.cadence.shaders.cadence.setup.QuickSetup.apply(r));
				} else if (lower.startsWith("clickcmd=")) {
					// "clickcmd=COMMAND": as if a chat link running COMMAND was clicked (the path [Hide these messages] takes)
					String clicked = step.substring(9).strip();
					mc.execute(() -> {
						if (mc.player != null) mc.player.connection.sendUnattendedCommand(clicked, null);
					});
				} else if (lower.startsWith("background=")) {
					// "background=on": behave as if another app were active (BackgroundFps), "off": the window's real state
					dev.cadence.shaders.cadence.system.BackgroundFps.fakeBackground = lower.endsWith("on");
				} else if (lower.equals("reload")) {
					// "reload": the resource packs reloaded, as F3+T
					mc.execute(() -> mc.reloadResourcePacks());
				} else if (lower.startsWith("uniforms=")) {
					// uniforms=a+b: those uniforms' values now (the pack's custom ones too), in the log
					String[] names = step.substring(9).split("\\+");
					mc.execute(() -> {
						var rt = dev.cadence.shaders.runtime.ShaderRuntime.get();
						if (rt == null) return;
						StringBuilder line = new StringBuilder();
						for (String n : names) line.append(rt.uniforms().describe(n.strip())).append("  ");
						LOGGER.info("[selftest] uniforms: {}", line);
					});
				} else if (lower.startsWith("skipprograms=")) {
					// skipprograms=a+b: the pack programs a and b draw nothing (empty: all draw again), for finding which draw makes a glitch
					dev.cadence.shaders.runtime.ShaderRenderPass.SKIP_PROGRAMS = java.util.Arrays.stream(step.substring(13).split("\\+"))
						.filter(n -> !n.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
				} else if (lower.startsWith("debugbuffer=")) {
					// debugbuffer=N: the final pass shows colortexN (debugbuffer= : the pack's own final again), then reloaded
					String n = step.substring(12);
					if (n.isEmpty()) System.clearProperty("cadence.shaders.debugBuffer");
					else System.setProperty("cadence.shaders.debugBuffer", n);
					mc.execute(ShaderSwitcher::reload);
				} else if (lower.startsWith("option=")) {
					// option=NAME=VALUE: the active pack's option NAME set to VALUE for this run (empty: the pack's default), then reloaded
					String rest = step.substring(7);
					int eq = rest.indexOf('=');
					String name = eq < 0 ? rest : rest.substring(0, eq), value = eq < 0 ? "" : rest.substring(eq + 1);
					if (value.isEmpty()) System.clearProperty("cadence.shaders.option." + name);
					else System.setProperty("cadence.shaders.option." + name, value);
					mc.execute(ShaderSwitcher::reload);
				} else if (lower.startsWith("wait=")) {
					// "wait=SECONDS": nothing for that long (a running glide keeps going; soak tests)
					sleep(Integer.parseInt(lower.substring(5)) * 1000L);
				} else if (lower.equals("failframe")) {
					// "failframe": the next sky pass throws (FrameRecovery), as a pack frame failing part way
					FrameRecovery.failNextSky = true;
				} else if (lower.startsWith("vanillargss=")) {
					// "vanillargss=on|off": VanillaRgss for the next resource reload (step reload)
					VanillaRgss.MODE = lower.endsWith("on") || lower.endsWith("true") ? "true" : "false";
				} else if (lower.startsWith("rgssskip=")) {
					boolean on = lower.endsWith("on") || lower.endsWith("true");
					mc.execute(() -> dev.cadence.metal.own.OwnTerrain.rgssSkip(on));
				} else if (lower.startsWith("facecull=")) {
					dev.cadence.shaders.cadence.facecull.FaceCull.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("shadowproxy=")) {
					int d = Integer.parseInt(lower.substring(12));
					mc.execute(() -> dev.cadence.shaders.cadence.shadow.ShadowProxies.setDistance(d));
				} else if (lower.startsWith("parallelgen=")) {
					dev.cadence.shaders.cadence.worldgen.ParallelWorldgen.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.startsWith("particlephysics=")) {
					ParticlePhysics.enabled = lower.endsWith("on") || lower.endsWith("true");
				} else if (lower.equals("gc")) {
					// "gc": a full collection, then the live heap (what the game really keeps)
					System.gc();
					sleep(1000);
					System.gc();
					Runtime rt = Runtime.getRuntime();
					LOGGER.info("[selftest] heap after GC: {} MB live, {} MB committed", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.totalMemory() >> 20);
				} else if (lower.equals("histo")) {
					// "histo": the live heap's class histogram (full GC) to run/histo-HHmmss.txt, top 12 logged
					try {
						Object out = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
							new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
							new Object[] {null}, new String[] {String[].class.getName()});
						java.nio.file.Path file = mc.gameDirectory.toPath().resolve("histo-" + java.time.LocalTime.now().format(
							java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".txt");
						java.nio.file.Files.writeString(file, String.valueOf(out));
						String[] lines = String.valueOf(out).split("\n");
						for (int h = 0; h < Math.min(lines.length, 15); h++) LOGGER.info("[selftest] histo {}", lines[h]);
						LOGGER.info("[selftest] histo {}", lines[lines.length - 1]);
					} catch (Exception e) {
						LOGGER.info("[selftest] histo failed: {}", e.toString());
					}
				} else if (lower.startsWith("cmd=")) {
					// "cmd=COMMAND": a server command (no commas; "@me" = the player)
					String command = step.substring(4).replace("@me", mc.getUser().getName());
					var cmdServer = mc.getSingleplayerServer();
					if (cmdServer != null) cmdServer.execute(() -> cmdServer.getCommands().performPrefixedCommand(cmdServer.createCommandSourceStack().withSuppressedOutput(), command));
				} else if (lower.startsWith("cmds=")) {
					// "cmds=FILE": every line of FILE (relative to the game folder) as a server command, in order (commands with commas)
					var cmdServer = mc.getSingleplayerServer();
					java.util.List<String> lines;
					try {
						lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(step.substring(5)));
					} catch (java.io.IOException e) {
						LOGGER.info("[selftest] cmds: can't read {}: {}", step.substring(5), e.toString());
						lines = java.util.List.of();
					}
					if (cmdServer != null)
						for (String line : lines) {
							String command = line.strip().replace("@me", mc.getUser().getName());
							if (command.isEmpty() || command.startsWith("#")) continue;
							cmdServer.execute(() -> cmdServer.getCommands().performPrefixedCommand(cmdServer.createCommandSourceStack(), command));
						}
				} else if (lower.startsWith("transparency=")) {
					// "transparency=on|off": the Improved Transparency video option (vanilla's OIT path)
					boolean t = lower.endsWith("on");
					mc.execute(() -> {
						mc.options.improvedTransparency().set(t);
						mc.levelExtractor.allChanged();
					});
				} else if (lower.startsWith("maxfps=")) {
					int f = Integer.parseInt(lower.substring(7));
					mc.execute(() -> mc.options.framerateLimit().set(f));
				} else if (lower.startsWith("clouds=")) {
					// "clouds=fancy|fast|off": the Clouds video option
					var status = switch (lower.substring(7)) {
						case "fast" -> net.minecraft.client.CloudStatus.FAST;
						case "off" -> net.minecraft.client.CloudStatus.OFF;
						default -> net.minecraft.client.CloudStatus.FANCY;
					};
					mc.execute(() -> mc.options.cloudStatus().set(status));
				} else if (lower.startsWith("hud=")) {
					// "hud=off|on": vanilla's F1 (HUD and hand hidden; gallery screenshots)
					boolean hide = lower.endsWith("off");
					mc.execute(() -> {
						try {
							var f = net.minecraft.client.gui.Hud.class.getDeclaredField("isHidden");
							f.setAccessible(true);
							f.setBoolean(mc.gui.hud, hide);
						} catch (ReflectiveOperationException e) {
							LOGGER.info("[selftest] hud: {}", e.toString());
						}
					});
				} else if (lower.equals("jit")) {
					// "jit" (measurement): the code cache's heaps and metaspace, used / committed
					StringBuilder pools = new StringBuilder();
					for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
						String n = pool.getName();
						if (!n.contains("CodeHeap") && !n.contains("Metaspace") && !n.contains("Code")) continue;
						var u = pool.getUsage();
						pools.append(String.format(Locale.ROOT, " | %s %.1f/%.1f MB", n, u.getUsed() / 1048576.0, u.getCommitted() / 1048576.0));
					}
					var comp = java.lang.management.ManagementFactory.getCompilationMXBean();
					LOGGER.info("[selftest] jit{} | compile time {} s", pools, comp == null ? -1 : comp.getTotalCompilationTime() / 1000);
				} else if (lower.equals("spritemem")) {
					// "spritemem" (measurement): the CPU copies of every atlas sprite's pixels (originalImage + byMipLevel), static vs animated
					mc.execute(() -> {
						try {
							var byPath = net.minecraft.client.renderer.texture.TextureManager.class.getDeclaredField("byPath");
							byPath.setAccessible(true);
							var orig = net.minecraft.client.renderer.texture.SpriteContents.class.getDeclaredField("originalImage");
							var mips = net.minecraft.client.renderer.texture.SpriteContents.class.getDeclaredField("byMipLevel");
							var size = com.mojang.blaze3d.platform.NativeImage.class.getDeclaredField("size");
							orig.setAccessible(true);
							mips.setAccessible(true);
							size.setAccessible(true);
							var byName = net.minecraft.client.renderer.texture.TextureAtlas.class.getDeclaredField("texturesByName");
							byName.setAccessible(true);
							java.util.Map<?, ?> textures = (java.util.Map<?, ?>) byPath.get(mc.getTextureManager());
							long staticBytes = 0, animBytes = 0, staticPixels = 0;
							int staticN = 0, animN = 0;
							for (var e : textures.entrySet()) {
								if (!(e.getValue() instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) continue;
								long atlasBytes = 0;
								var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
								java.util.Map<?, ?> sprites = (java.util.Map<?, ?>) byName.get(atlas);
								for (Object sprite : sprites.values()) {
									var c = ((net.minecraft.client.renderer.texture.TextureAtlasSprite) sprite).contents();
									long b = 0;
									var o = (com.mojang.blaze3d.platform.NativeImage) orig.get(c);
									if (o != null && seen.add(o)) b += size.getLong(o);
									var m = (com.mojang.blaze3d.platform.NativeImage[]) mips.get(c);
									if (m != null) for (var im : m) if (im != null && seen.add(im)) b += size.getLong(im);
									atlasBytes += b;
									if (c.isAnimated()) {
										animBytes += b;
										animN++;
									} else {
										staticBytes += b;
										staticN++;
										staticPixels += (long) c.width() * c.height();
									}
								}
								LOGGER.info("[selftest] spritemem {}: {} sprites, {} KB", e.getKey(), sprites.size(), atlasBytes >> 10);
							}
							LOGGER.info("[selftest] spritemem: static {} sprites {} KB ({} px), animated {} sprites {} KB", staticN, staticBytes >> 10,
								staticPixels, animN, animBytes >> 10);
						} catch (ReflectiveOperationException | RuntimeException ex) {
							LOGGER.info("[selftest] spritemem failed: {}", ex.toString());
						}
					});
				} else if (lower.startsWith("dynlights=")) {
					// "dynlights=off|held|all": Dynamic Lights (not saved)
					var m = switch (lower.substring(10)) {
						case "held" -> DynamicLights.Mode.HELD;
						case "all" -> DynamicLights.Mode.ALL;
						default -> DynamicLights.Mode.OFF;
					};
					mc.execute(() -> DynamicLights.setForTest(m));
				} else if (lower.startsWith("camera=")) {
					// "camera=first|back|front": the camera view (F5)
					var type = switch (lower.substring(7)) {
						case "back" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
						case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
						default -> net.minecraft.client.CameraType.FIRST_PERSON;
					};
					mc.execute(() -> mc.options.setCameraType(type));
				} else if (lower.startsWith("genarea=")) {
					// "genarea=CX:CZ:R": force-load the (2R)x(2R) chunks around chunk CX,CZ (new terrain on the world snapshot),
					// time until every one is a full chunk, then release them. Measures world generation alone.
					String[] a = lower.substring(8).split(":");
					int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]), r = Integer.parseInt(a[2]);
					var gen = mc.getSingleplayerServer();
					if (gen != null) {
						String area = (cx - r) * 16 + " " + (cz - r) * 16 + " " + ((cx + r) * 16 - 1) + " " + ((cz + r) * 16 - 1);
						long t0 = System.nanoTime();
						gen.execute(() -> gen.getCommands().performPrefixedCommand(gen.createCommandSourceStack(), "forceload add " + area));
						int total = 4 * r * r, done = 0;
						while (System.nanoTime() - t0 < 180_000_000_000L) {
							sleep(250);
							done = gen.submit(() -> {
								int n = 0;
								var chunks = gen.overworld().getChunkSource();
								for (int x = cx - r; x < cx + r; x++)
									for (int z = cz - r; z < cz + r; z++)
										if (chunks.getChunkNow(x, z) != null) n++;
								return n;
							}).join();
							if (done >= total) break;
						}
						LOGGER.info("[selftest] genarea {}: {} of {} chunks full in {} s", lower.substring(8), done, total,
							String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e9));
						// Every block state and biome of every chunk, hashed per chunk (compare runs: run/genhash-*.txt)
						String hashes = gen.submit(() -> {
							StringBuilder out = new StringBuilder();
							long all = 1125899906842597L;
							it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
							var level = gen.overworld();
							for (int x = cx - r; x < cx + r; x++)
								for (int z = cz - r; z < cz + r; z++) {
									var chunk = level.getChunkSource().getChunkNow(x, z);
									if (chunk == null) {
										out.append(x).append(' ').append(z).append(" missing\n");
										continue;
									}
									long h = 1125899906842597L;
									for (var section : chunk.getSections()) {
										for (int b = 0; b < 4096; b++) {
											var st = section.getBlockState(b & 15, b >> 8, (b >> 4) & 15);
											h = 31 * h + net.minecraft.world.level.block.Block.getId(st);
											counts.addTo(st.getBlock().getDescriptionId(), 1);
										}
										for (int b = 0; b < 64; b++)
											h = 31 * h + section.getNoiseBiome(b & 3, b >> 4, (b >> 2) & 3).unwrapKey().map(k -> k.identifier().hashCode()).orElse(0);
									}
									h = 31 * h + chunk.getBlockEntities().size();
									all = 31 * all + h;
									out.append(x).append(' ').append(z).append(' ').append(Long.toHexString(h)).append('\n');
								}
							counts.object2IntEntrySet().stream().sorted((p1, p2) -> p1.getKey().compareTo(p2.getKey()))
								.forEach(e -> out.append("count ").append(e.getKey()).append(' ').append(e.getIntValue()).append('\n'));
							out.append("all ").append(Long.toHexString(all)).append('\n');
							return out.toString();
						}).join();
						try {
							java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("genhash-" + cx + "-" + cz + ".txt"), hashes);
						} catch (java.io.IOException e) {
							LOGGER.info("[selftest] genhash not written: {}", e.toString());
						}
						LOGGER.info("[selftest] genarea {}: {}", lower.substring(8), hashes.substring(hashes.lastIndexOf("all ")).strip());
						gen.execute(() -> gen.getCommands().performPrefixedCommand(gen.createCommandSourceStack(), "forceload remove " + area));
					}
				} else if (lower.startsWith("burst=")) {
					// "burst=N:MS": N screenshots MS apart (motion artifacts: shimmer, ghosting, flicker), named <step>-burst-K
					String[] b = lower.substring(6).split(":");
					int n = Integer.parseInt(b[0]), gap = b.length > 1 ? Integer.parseInt(b[1]) : 100;
					String base = "selftest-" + (i + 1) + "-burst";
					if (b.length > 2) { // "burst=N:MS:SCALE": the render scale changes just before (what a resize does to history)
						String value = b[2];
						mc.execute(() -> RenderScale.applyValue(value));
						sleep(40);
					}
					for (int k = 0; k < n; k++) {
						int frame = k;
						mc.execute(() -> {
							try {
								Screenshot.grab(mc.gameDirectory, base + "-" + frame + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> { });
							} catch (RuntimeException e) {
								LOGGER.info("[selftest] burst {} failed: {}", frame, e.toString());
							}
						});
						sleep(gap);
					}
				} else if (lower.startsWith("spin=")) {
					// "spin=S:DEG": the view turns DEG degrees per tick for S seconds (in the background, like glide)
					String[] g = lower.substring(5).split(":");
					int seconds = Integer.parseInt(g[0]);
					String deg = g.length > 1 ? g[1] : "1";
					var spinServer = mc.getSingleplayerServer();
					if (spinServer != null) {
						Thread spin = new Thread(() -> {
							long end = System.nanoTime() + seconds * 1_000_000_000L;
							while (System.nanoTime() < end) {
								spinServer.execute(() -> spinServer.getCommands().performPrefixedCommand(spinServer.createCommandSourceStack().withSuppressedOutput(),
									"execute as " + mc.getUser().getName() + " at @s run tp @s ~ ~ ~ ~" + deg + " ~"));
								sleep(50);
							}
						}, "cadence-selftest-spin");
						spin.setDaemon(true);
						spin.start();
					}
				} else if (lower.startsWith("glide=")) {
					// "glide=S", "glide=S:D" or "glide=S:D:Y": the player moves east D (0.5) blocks per tick for S seconds (runs on while
					// the step is observed); with Y, held at height Y (a flight over the terrain; the player doesn't fly by itself)
					String[] g = lower.substring(6).split(":");
					int seconds = Integer.parseInt(g[0]);
					String perTick = g.length > 1 ? g[1] : "0.5", height = g.length > 2 ? g[2] : "~";
					var glideServer = mc.getSingleplayerServer();
					if (glideServer != null) {
						Thread glide = new Thread(() -> {
							long end = System.nanoTime() + seconds * 1_000_000_000L, nextSample = System.nanoTime() + 1_000_000_000L;
							int samples = 0, sum = 0, min = Integer.MAX_VALUE;
							while (System.nanoTime() < end) {
								// (output suppressed: a chat line per tick was most of the render thread's garbage while gliding)
								glideServer.execute(() -> glideServer.getCommands().performPrefixedCommand(glideServer.createCommandSourceStack().withSuppressedOutput(),
									"execute as " + mc.getUser().getName() + " at @s run tp @s ~" + perTick + " " + height + " ~"));
								sleep(50);
								if (System.nanoTime() >= nextSample) {
									nextSample += 1_000_000_000L;
									// chunks the client has, counting east from the player's chunk (the direction of travel)
									int ahead = mc.submit(() -> {
										if (mc.player == null || mc.level == null) return 0;
										int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
										int rd = mc.options.getEffectiveRenderDistance(), k = 0;
										while (k < rd && mc.level.getChunkSource().hasChunk(pcx + k + 1, pcz)) k++;
										return k;
									}).join();
									samples++;
									sum += ahead;
									min = Math.min(min, ahead);
								}
							}
							LOGGER.info("[selftest] glide: chunks loaded ahead avg {} min {} over {} s (render distance {})",
								String.format(Locale.ROOT, "%.1f", samples == 0 ? 0.0 : (double) sum / samples), samples == 0 ? 0 : min, samples,
								mc.options.getEffectiveRenderDistance());
						}, "cadence-glide");
						glide.setDaemon(true);
						glide.start();
					}
				} else if (lower.equals("scene=busy")) {
					BusyScene.build(mc);
				} else if (lower.startsWith("receiver=")) {
					boolean on = lower.endsWith("on") || lower.endsWith("true");
					mc.execute(() -> dev.cadence.shaders.runtime.ShadowRenderer.setReceiverCulling(on));
				} else if (lower.startsWith("discard=")) {
					boolean on = lower.endsWith("on") || lower.endsWith("true");
					mc.execute(() -> dev.cadence.shaders.runtime.ShaderRuntime.setDiscardFullscreenLoads(on));
				} else if (lower.startsWith("scale=")) {
					String value = lower.substring(6);
					mc.execute(() -> {
						RenderScale.applyValue(value); // not saved: a test must not change the player's setting
						RenderScale.settle();
					});
				} else {
					// "pack@mode" picks the chunk reload mode for this step (ShaderSwitcher.reload)
					String name = step, mode = null;
					int at = step.lastIndexOf('@');
					if (at > 0) {
						name = step.substring(0, at);
						mode = step.substring(at + 1);
					}
					String pack = name.equalsIgnoreCase("off") ? "" : name;
					String chosenMode = mode;
					mc.execute(() -> {
						ShaderToggle.save(pack);
						if (chosenMode == null) ShaderSwitcher.apply(pack);
						else ShaderSwitcher.apply(pack, chosenMode);
					});
				}
				observe(mc, i + 1, step, settle);
			}
			LOGGER.info("[selftest] DONE");
		} catch (RuntimeException e) {
			LOGGER.info("[selftest] FAIL: {}", e.toString(), e);
		} finally {
			sleep(2000);
			if (originalConfig != null) {
				try {
					java.nio.file.Files.write(ShaderToggle.configFile(), originalConfig);
					LOGGER.info("[selftest] config restored");
				} catch (java.io.IOException e) {
					LOGGER.info("[selftest] config not restored: {}", e.toString());
				}
			}
			mc.execute(mc::stop);
		}
	}

	private static void hover(Minecraft mc, String prefix) {
		var screen = mc.gui.screen();
		if (screen == null) return;
		net.minecraft.client.gui.components.AbstractWidget w = find(screen.children(), prefix);
		if (w == null) {
			LOGGER.info("[selftest] hover: no widget starting with '{}'", prefix);
			return;
		}
		// (MouseHandler keeps window points: gui x * screen width / gui-scaled width)
		var win = mc.getWindow();
		double sx = (double) win.getScreenWidth() / win.getGuiScaledWidth(), sy = (double) win.getScreenHeight() / win.getGuiScaledHeight();
		try {
			var x = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
			var y = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
			x.setAccessible(true);
			y.setAccessible(true);
			x.setDouble(mc.mouseHandler, (w.getX() + w.getWidth() / 2.0) * sx);
			y.setDouble(mc.mouseHandler, (w.getY() + w.getHeight() / 2.0) * sy);
		} catch (ReflectiveOperationException e) {
			LOGGER.info("[selftest] hover failed: {}", e.toString());
		}
	}

	private static net.minecraft.client.gui.components.AbstractWidget find(java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children, String prefix) {
		for (net.minecraft.client.gui.components.events.GuiEventListener c : children) {
			if (c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().startsWith(prefix)) return w;
			if (c instanceof net.minecraft.client.gui.components.events.ContainerEventHandler h) {
				var found = find(h.children(), prefix);
				if (found != null) return found;
			}
		}
		return null;
	}

	/** As find, buttons only (a status line can start with a button's text too). */
	private static net.minecraft.client.gui.components.Button findButton(java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children, String prefix) {
		for (net.minecraft.client.gui.components.events.GuiEventListener c : children) {
			if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().startsWith(prefix)) return b;
			if (c instanceof net.minecraft.client.gui.components.events.ContainerEventHandler h) {
				var found = findButton(h.children(), prefix);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static void abSet(Minecraft mc, String feature, boolean on) {
		mc.execute(() -> {
			switch (feature) {
				case "facecull" -> dev.cadence.shaders.cadence.facecull.FaceCull.enabled = on;
				case "hiz" -> dev.cadence.shaders.cadence.hiz.HiZ.enabled = on;
				case "hizvanilla" -> dev.cadence.shaders.cadence.hiz.HiZ.vanilla = on;
				case "packeddraws" -> dev.cadence.shaders.cadence.alloc.PackedDraws.enabled = on;
				case "depthdiscard" -> dev.cadence.metal.FrameHints.depthDiscard = on;
				case "heapcompact" -> dev.cadence.shaders.cadence.memory.ChunkHeapCompactor.enabled = on;
				case "shadowproxy" -> dev.cadence.shaders.cadence.shadow.ShadowProxies.setDistance(on ? 96 : 0);
				case "shadowrate" -> ShadowReuse.setRate(on ? 2 : 1);
				case "entitycull" -> EntityCull.set(on);
				case "particlephysics" -> ParticlePhysics.enabled = on;
				case "meshcache" -> dev.cadence.shaders.cadence.mesh.MeshStateCache.enabled = on;
				case "meshlight" -> dev.cadence.shaders.cadence.mesh.MeshLightCache.enabled = on;
				case "meshtint" -> dev.cadence.shaders.cadence.mesh.MeshTintCache.enabled = on;
				case "facepacked" -> dev.cadence.shaders.cadence.facecull.FaceCull.packedGroups = on;
				case "lazyclears" -> dev.cadence.metal.FrameHints.lazyClears(on);
				case "modelwrite" -> dev.cadence.metal.cpu.Cpu.modelWrite = on;
				case "rgssskip" -> dev.cadence.metal.own.OwnTerrain.rgssSkip(on);
				case "s67apple" -> { RenderScale.applyUpscaler("metalfx"); RenderScale.applyValue(on ? "0.67" : "1.0"); }
				case "s67fast" -> { RenderScale.applyUpscaler("fast"); RenderScale.applyValue(on ? "0.67" : "1.0"); }
				case "halfres" -> { RenderScale.applyUpscaler("fast"); RenderScale.applyValue(on ? "0.5" : "1.0"); }
				case "crisptext" -> CrispText.ON = on;
				case "scalemerge" -> WorldScale.MERGE = on;
				case "itemids" -> dev.cadence.shaders.geometry.FeatureDrawContext.ITEM_IDS = on;
				case "flushsampled" -> dev.cadence.shaders.runtime.ShaderRuntime.FLUSH_SAMPLED_CLEARS = on;
				case "fsdiscard" -> dev.cadence.shaders.runtime.ShaderRuntime.setDiscardFullscreenLoads(on);
				case "nofeedback" -> dev.cadence.shaders.runtime.PackRenderTargets.SKIP_FEEDBACK_COPIES = on;
				case "feedbackreads" -> dev.cadence.shaders.runtime.ShaderRuntime.FEEDBACK_READS = on;
				case "dynlights" -> DynamicLights.setForTest(on ? DynamicLights.Mode.ALL : DynamicLights.Mode.OFF);
				case "finaldepth" -> dev.cadence.metal.MetalBridge.bandwidthTest(on ? 1 : 0);
				case "present2" -> dev.cadence.metal.MetalBridge.bandwidthTest(on ? 2 : 0);
				default -> {
					// "skip-PROGRAM": ON = that pack program draws nothing (its GPU cost, measured interleaved: thermal drift cancels)
					if (feature.startsWith("drop-")) dev.cadence.metal.FrameHints.dropPipeline = on ? feature.substring(5) : null;
					else if (feature.startsWith("skip-"))
						dev.cadence.shaders.runtime.ShaderRenderPass.SKIP_PROGRAMS = on ? java.util.Set.of(feature.substring(5)) : java.util.Set.of();
					else LOGGER.info("[selftest] ab: unknown feature {}", feature);
				}
			}
		});
	}

	private static void abTest(Minecraft mc, String feature, int seconds) {
		double[] cpuMs = new double[2];
		int[] cpuN = new int[2];
		double[] gpu = new double[2], interval = new double[2];
		int[] n = new int[2];
		boolean on = false;
		long end = System.nanoTime() + seconds * 1_000_000_000L;
		while (System.nanoTime() < end) {
			on = !on;
			abSet(mc, feature, on);
			long w0 = RenderScale.windows(), wStart = w0;
			long until = System.nanoTime() + 2_000_000_000L;
			// the render thread's CPU time per frame in this state (after 300 ms to settle): CPU work shows even when the GPU sets the pace
			sleep(300);
			java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
			long tid = RenderScale.renderThreadId, cpu0 = tid >= 0 ? mx.getThreadCpuTime(tid) : -1, f0 = RenderScale.frameCount;
			while (System.nanoTime() < until) {
				sleep(50);
				long w = RenderScale.windows();
				// skip the window the flip happened in; count the next ones that end inside this state
				if (w > w0 + 1) {
					int k = on ? 1 : 0;
					gpu[k] += RenderScale.lastGpuMs();
					interval[k] += RenderScale.lastIntervalMs();
					n[k]++;
					w0 = w;
				}
			}
			long cpu1 = tid >= 0 ? mx.getThreadCpuTime(tid) : -1, f1 = RenderScale.frameCount;
			if (cpu0 >= 0 && cpu1 > cpu0 && f1 > f0) {
				int k = on ? 1 : 0;
				cpuMs[k] += (cpu1 - cpu0) / 1e6 / (f1 - f0);
				cpuN[k]++;
			}
			// no pack (no render-scale windows): the frame time from the game's fps counter, which covers the second that
			// ended just now (wholly in this state)
			if (RenderScale.windows() == wStart && mc.getFps() > 0) {
				int k = on ? 1 : 0;
				interval[k] += 1000.0 / mc.getFps();
				n[k]++;
			}
		}
		abSet(mc, feature, true);
		LOGGER.info("[selftest] ab {}: render-thread CPU per frame OFF {} ms (n {}) | ON {} ms (n {}) | ON-OFF {} ms ({}%)", feature,
			String.format(Locale.ROOT, "%.3f", cpuN[0] == 0 ? 0 : cpuMs[0] / cpuN[0]), cpuN[0], String.format(Locale.ROOT, "%.3f", cpuN[1] == 0 ? 0 : cpuMs[1] / cpuN[1]), cpuN[1],
			String.format(Locale.ROOT, "%+.3f", (cpuN[1] == 0 ? 0 : cpuMs[1] / cpuN[1]) - (cpuN[0] == 0 ? 0 : cpuMs[0] / cpuN[0])),
			String.format(Locale.ROOT, "%+.1f", cpuN[0] == 0 || cpuMs[0] == 0 ? 0 : 100 * ((cpuMs[1] / Math.max(1, cpuN[1])) / (cpuMs[0] / cpuN[0]) - 1)));
		LOGGER.info("[selftest] ab {}: OFF gpu {} ms interval {} ms (n {}) | ON gpu {} ms interval {} ms (n {}) | ON-OFF gpu {} ms, interval {} ms",
			feature, String.format(Locale.ROOT, "%.2f", n[0] == 0 ? 0 : gpu[0] / n[0]), String.format(Locale.ROOT, "%.2f", n[0] == 0 ? 0 : interval[0] / n[0]), n[0],
			String.format(Locale.ROOT, "%.2f", n[1] == 0 ? 0 : gpu[1] / n[1]), String.format(Locale.ROOT, "%.2f", n[1] == 0 ? 0 : interval[1] / n[1]), n[1],
			String.format(Locale.ROOT, "%+.2f", (n[1] == 0 ? 0 : gpu[1] / n[1]) - (n[0] == 0 ? 0 : gpu[0] / n[0])),
			String.format(Locale.ROOT, "%+.3f", (n[1] == 0 ? 0 : interval[1] / n[1]) - (n[0] == 0 ? 0 : interval[0] / n[0])));
	}

	private static void observe(Minecraft mc, int index, String step, int settle) {
		List<Integer> fps = new ArrayList<>();
		// Chunk build time: from the step's start until the compile queue has been empty for a whole second (after work).
		long start = System.nanoTime(), emptySince = -1;
		boolean busy = false;
		double builtIn = -1;
		for (int tick = 1; tick <= settle * 10; tick++) {
			sleep(100);
			if (tick % 10 == 0) fps.add(mc.getFps());
			var dispatcher = mc.levelRenderer.sectionRenderDispatcher();
			int queued = dispatcher == null ? 0 : dispatcher.getCompileQueueSize();
			if (queued > 0) {
				busy = true;
				emptySince = -1;
				builtIn = -1;
			} else if (busy && emptySince < 0) {
				emptySince = System.nanoTime();
			} else if (busy && builtIn < 0 && System.nanoTime() - emptySince >= 1_000_000_000L) {
				builtIn = (emptySince - start) / 1e9;
			}
		}
		LOGGER.info("[selftest] {} {}: chunks {}", index, step,
			!busy ? "idle" : builtIn >= 0 ? String.format(Locale.ROOT, "built in %.1f s", builtIn) : "still building");
		List<Integer> last = fps.subList(Math.max(0, fps.size() - 10), fps.size());
		int min = last.stream().mapToInt(Integer::intValue).min().orElse(0);
		int max = last.stream().mapToInt(Integer::intValue).max().orElse(0);
		double avg = last.stream().mapToInt(Integer::intValue).average().orElse(0);
		LOGGER.info("[selftest] {} {}: fps last 10 s min {} avg {} max {} (scale {}{})", index, step, min, String.format(Locale.ROOT, "%.0f", avg), max,
			RenderScale.get(), (RenderScale.isAuto() ? " auto, " : ", ") + RenderScale.lastWindow() + "; " + RenderScale.stutter());
		String label = "selftest-" + index + "-" + step.replaceAll("[^A-Za-z0-9._-]", "_");
		mc.execute(() -> {
			SwitchProbe.log(mc, "selftest " + index + " " + step, settle);
			LOGGER.info("[selftest] {} {}: memory {}", index, step, MemoryProbe.summary(mc.levelRenderer.sectionRenderDispatcher()));
			LOGGER.info("[selftest] {} {}: {}", index, step, MemoryProbe.census(mc));
			try {
				LOGGER.info("[selftest] {} {}: gpu {}", index, step, dev.cadence.metal.GpuMemory.census(14));
			} catch (LinkageError e) {
				// not on Cadence's Metal backend
			}
			try {
				Screenshot.grab(mc.gameDirectory, label + ".png", mc.gameRenderer.mainRenderTarget(), 1,
					message -> LOGGER.info("[selftest] screenshot {}: {}", label, message.getString()));
			} catch (RuntimeException e) {
				LOGGER.info("[selftest] screenshot {} failed: {}", label, e.toString());
			}
		});
		sleep(1500);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
