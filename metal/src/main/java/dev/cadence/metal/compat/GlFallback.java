package dev.cadence.metal.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLVideo;

/**
 * Mods that call OpenGL directly (LWJGL's GL classes, or Minecraft's own OpenGL backend classes) crash under Metal: there is no
 * OpenGL context, so their first GL call throws. This makes one: a hidden 1x1 window with a 3.2 core context (as Minecraft's
 * own OpenGL backend asks for), current on the render thread. Their calls then run, their textures and buffers exist, their
 * state changes are harmless, and what they draw is laid over the frame (mcgl.m, MetalSurface: an IOSurface shared with Metal).
 *
 * <p>Only when a mod needs it: at launch every other mod's classes are searched for OpenGL references (once per set of mods;
 * the answer is cached in config/cadence-gl-users.txt). Nothing is made otherwise, so it costs nothing. A context that sits
 * idle costs a few MB and no GPU time. -Dcadence.glFallback=true: always, false: never.
 */
public final class GlFallback {
	private static final String MODE = System.getProperty("cadence.glFallback", "auto");
	private static boolean tried;
	private static long window, context;
	private static List<String> users = List.of();

	private GlFallback() {
	}

	/** The mods found to call OpenGL (empty when none, or not searched). */
	public static List<String> users() {
		return users;
	}

	public static boolean active() {
		return context != 0;
	}

	/** Render thread, right after Metal's device was made (MetalBackend.createDevice). */
	public static void ensure() {
		if (tried) return;
		tried = true;
		if ("false".equals(MODE)) return;
		try {
			users = findUsers();
			if (users.isEmpty() && !"true".equals(MODE)) return;
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 2);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, SDLVideo.SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
			window = SDLVideo.SDL_CreateWindow("Cadence OpenGL compatibility", 1, 1, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
			if (window == 0) throw new IllegalStateException("no window: " + org.lwjgl.sdl.SDLError.SDL_GetError());
			context = SDLVideo.SDL_GL_CreateContext(window);
			if (context == 0) throw new IllegalStateException("no context: " + org.lwjgl.sdl.SDLError.SDL_GetError());
			SDLVideo.SDL_GL_MakeCurrent(window, context);
			GL.createCapabilities();
			System.out.println("[cadence] OpenGL compatibility context made for " + (users.isEmpty() ? "-Dcadence.glFallback=true" : String.join(", ", users))
				+ ": their OpenGL calls run and what they draw is laid over the frame");
		} catch (Throwable t) {
			System.out.println("[cadence] OpenGL compatibility context couldn't be made: " + t);
			if (context != 0) SDLVideo.SDL_GL_DestroyContext(context);
			if (window != 0) SDLVideo.SDL_DestroyWindow(window);
			context = window = 0;
		}
	}

	/** The mods (names) with classes that reference OpenGL. Cached per set of installed mods and versions. */
	static List<String> findUsers() throws IOException {
		var loader = FabricLoader.getInstance();
		List<ModContainer> mods = new ArrayList<>();
		StringBuilder key = new StringBuilder();
		for (ModContainer mod : loader.getAllMods()) {
			String id = mod.getMetadata().getId();
			if (builtin(id) || KNOWN_FINE.contains(id) || mod.getContainingMod().isPresent()) continue;  // (nested jars are searched with their parent)
			mods.add(mod);
		}
		mods.sort(java.util.Comparator.comparing(m -> m.getMetadata().getId()));
		for (ModContainer mod : mods) key.append(mod.getMetadata().getId()).append('@').append(mod.getMetadata().getVersion().getFriendlyString()).append(' ');
		Path cache = loader.getConfigDir().resolve("cadence-gl-users.txt");
		String keyLine = "v2 " + Integer.toHexString(key.toString().hashCode());
		if (Files.isRegularFile(cache)) {
			List<String> lines = Files.readAllLines(cache);
			if (!lines.isEmpty() && lines.get(0).equals(keyLine)) return List.copyOf(lines.subList(1, lines.size()));
		}
		long t = System.nanoTime();
		List<String> found = new ArrayList<>();
		for (ModContainer mod : mods) if (referencesGl(mod)) found.add(mod.getMetadata().getName());
		List<String> out = new ArrayList<>();
		out.add(keyLine);
		out.addAll(found);
		try {
			Files.createDirectories(cache.getParent());
			Files.write(cache, out);
		} catch (IOException e) {
			// searched again next launch
		}
		System.out.println("[cadence] OpenGL users searched in " + (System.nanoTime() - t) / 1_000_000 + " ms: " + (found.isEmpty() ? "none" : found));
		return found;
	}

	/**
	 * Mods with OpenGL code that only runs when the game itself is on OpenGL: tested with Cadence and fine without the context
	 * (docs/COMPATIBILITY.md), so none is made for them.
	 */
	private static final java.util.Set<String> KNOWN_FINE = java.util.Set.of("essential", "essential-container", "elementa", "universalcraft",
		"immediatelyfast", "xaerominimap", "xaeroworldmap", "xaerolib");

	private static boolean builtin(String id) {
		return id.equals("minecraft") || id.equals("java") || id.equals("fabricloader") || id.startsWith("cadence") || id.startsWith("fabric-")
			|| id.equals("fabric-api") || id.equals("mixinextras");
	}

	private static final byte[][] NEEDLES = {"org/lwjgl/opengl/".getBytes(), "renderpearl/backend/opengl/".getBytes()};

	private static boolean referencesGl(ModContainer mod) {
		for (Path root : mod.getRootPaths()) {
			try (Stream<Path> files = Files.walk(root)) {
				if (files.filter(p -> p.toString().endsWith(".class")).anyMatch(GlFallback::classReferencesGl)) return true;
			} catch (IOException | RuntimeException e) {
				// unreadable: treated as not using OpenGL
			}
		}
		return false;
	}

	private static boolean classReferencesGl(Path p) {
		try (InputStream in = Files.newInputStream(p)) {
			byte[] b = in.readAllBytes();
			for (byte[] n : NEEDLES) if (indexOf(b, n) >= 0) return true;
		} catch (IOException e) {
			// skipped
		}
		return false;
	}

	private static int indexOf(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i <= hay.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
			return i;
		}
		return -1;
	}
}
