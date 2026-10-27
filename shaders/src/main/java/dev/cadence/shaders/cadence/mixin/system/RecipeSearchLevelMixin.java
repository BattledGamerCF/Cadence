package dev.cadence.shaders.cadence.mixin.system;

import java.util.Map;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: vanilla keeps the recipe search tree's rebuild (run again on a language change) as a lambda holding the level of the
 * last recipe book update. A dimension change sends no new recipe book, so the previous dimension's whole level (its chunks,
 * entities and light data) stayed in memory until the next one. The rebuild kept here takes the current level when it runs
 * instead (the tree itself is built now as before; it doesn't hold the level).
 */
@Mixin(SessionSearchTrees.class)
public abstract class RecipeSearchLevelMixin {
	/** SessionSearchTrees.RECIPE_COLLECTIONS (its Key type isn't visible here), null if it can't be found. */
	private static final Object cadence$recipeKey = cadence$key();

	@Shadow
	@Final
	private Map<Object, Runnable> reloaders;

	@Shadow
	public abstract void updateRecipes(ClientRecipeBook book, Level level);

	@Inject(method = "updateRecipes", at = @At("TAIL"))
	private void cadence$levelFree(ClientRecipeBook book, Level level, CallbackInfo ci) {
		if (cadence$recipeKey == null || !reloaders.containsKey(cadence$recipeKey)) return;
		// (replacing an existing key's value: safe inside rebuildAfterLanguageChange's loop over the values)
		reloaders.put(cadence$recipeKey, () -> {
			Level now = Minecraft.getInstance().level;
			if (now != null) updateRecipes(book, now);
		});
	}

	private static Object cadence$key() {
		try {
			var f = SessionSearchTrees.class.getDeclaredField("RECIPE_COLLECTIONS");
			f.setAccessible(true);
			return f.get(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
