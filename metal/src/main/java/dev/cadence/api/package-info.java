/**
 * Cadence's API for other mods (stable within an {@link dev.cadence.api.Cadence#API_VERSION}).
 *
 * <p>Cadence draws Minecraft with Apple's Metal instead of OpenGL. Mods that only use Minecraft's own rendering classes
 * (render types, pipelines, vertex consumers) work unchanged: Cadence implements those on Metal. Mods that call OpenGL directly
 * (LWJGL's GL classes, GlStateManager) have no OpenGL context to call. This API is how an addon mod fills that gap: it can tell
 * where the game runs ({@link dev.cadence.api.Cadence#isMetal}), draw at fixed points of Cadence's frame with Minecraft's
 * backend-neutral render passes or with Metal itself ({@link dev.cadence.api.Cadence#onRender}, {@link
 * dev.cadence.api.RenderContext#metal}), and ask Cadence to leave an area of the game on its vanilla code path for a mod that
 * needs it there (the {@code "cadence": {"stepAside": [...]}} custom value in fabric.mod.json; see {@link
 * dev.cadence.api.Cadence}).
 *
 * <p>Nothing here costs anything while no mod uses it: an unused hook is one array-length check per frame.
 */
package dev.cadence.api;
