package dev.cadence.api;

/** Something an addon draws at a {@link RenderStage}. Render thread; keep it cheap: it runs every frame. */
@FunctionalInterface
public interface RenderHook {
	void render(RenderContext context);
}
