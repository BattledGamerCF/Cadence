package dev.cadence.metal.mixin.cpu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cadence (with Cpu.textBidi): each glyph the GUI draws made a new identity Matrix4f, multiplied by its pose, only to be read
 * while that glyph's four vertices are written (the biggest single allocation in text-heavy frames). On the render thread one
 * matrix is reset to identity and reused: the same values, the same JOML calls after it. Any other thread gets a new one.
 */
@Mixin(GlyphRenderState.class)
abstract class TextGlyphMatrixMixin {
	@Unique private static final Matrix4f CADENCE_SCRATCH = new Matrix4f();

	@Redirect(method = "buildVertices", at = @At(value = "NEW", target = "()Lorg/joml/Matrix4f;"))
	private Matrix4f cadence$reuse() {
		return RenderSystem.isOnRenderThread() ? CADENCE_SCRATCH.identity() : new Matrix4f();
	}
}
