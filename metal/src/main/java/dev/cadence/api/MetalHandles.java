package dev.cadence.api;

/**
 * Native Metal objects of the current frame, as Objective-C object pointers (an {@code id<MTLDevice>} and so on, unretained:
 * valid during the hook call; retain what you keep), for addons that draw with Metal directly (through the Foreign Function
 * API, JNI or their own native library). 0 where there is none at this point of the frame.
 *
 * <p>Draw only into {@link #renderEncoder} while it is open (AFTER_OPAQUE, AFTER_TRANSLUCENT), and set back what you change
 * that Minecraft's pipelines don't set themselves (the viewport and scissor rect, the depth-stencil state, the cull mode):
 * the game's next draw expects its own. At the other stages, encode your own passes into {@link #commandBuffer}.
 *
 * @param device the {@code id<MTLDevice>}
 * @param commandQueue the {@code id<MTLCommandQueue>} Cadence submits to
 * @param commandBuffer this frame's {@code id<MTLCommandBuffer>}, still encoding
 * @param renderEncoder the open {@code id<MTLRenderCommandEncoder>} of the game's pass, or 0 when none is open
 * @param mainColor the main target's colour {@code id<MTLTexture>}
 * @param mainDepth the main target's depth {@code id<MTLTexture>} (Depth32Float, reversed: 1 near, 0 far)
 */
public record MetalHandles(long device, long commandQueue, long commandBuffer, long renderEncoder, long mainColor, long mainDepth) {
}
