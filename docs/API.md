# Cadence API for addon mods

Cadence draws Minecraft with Apple's Metal instead of OpenGL. Mods that use Minecraft's own rendering classes (render types,
render pipelines, vertex consumers, `GuiGraphics`) work unchanged, because Cadence implements them on Metal. Mods that call
OpenGL directly (LWJGL's `GL11`…`GL46` classes, `GlStateManager`) have no OpenGL context to call into while Cadence runs on Metal.

The API in `dev.cadence.api` (in the main Cadence jar) is how another mod fills that gap. An addon can:

- see where it is running: `Cadence.isMetal()`, `Cadence.shaderPackActive()`, `Cadence.farTerrainReach()`;
- draw at fixed points of every frame (`Cadence.onRender`), either with Minecraft's backend-neutral render passes or with Metal
  directly (`RenderContext.metal()` gives the device, command queue, command buffer, open render encoder and main textures);
- ask Cadence to leave parts of the game on their vanilla code path for a mod that hooks the same code (`stepAside`, below).

Nothing in the API costs anything while no mod uses it: an unused stage is one array-length check per frame, and Cadence loads
no addon class unless a mod declares one.

## Setting up

Compile against the Cadence jar (as `compileOnly` / `modCompileOnly`). If Cadence is optional for your mod, check
`FabricLoader.getInstance().isModLoaded("cadence")` before touching `dev.cadence.api`, or use the entry point below, which Cadence
calls only when it is installed.

```json
"entrypoints": { "cadence": ["com.example.myaddon.MyCadenceAddon"] }
```

```java
public final class MyCadenceAddon implements CadenceAddon {
	@Override
	public void onCadence() {
		Cadence.onRender(RenderStage.AFTER_TRANSLUCENT, ctx -> {
			// ctx.pass() is the game's open main pass: set a render pipeline, bind, draw.
		});
	}
}
```

## Render stages

| Stage | When | `pass()` |
|---|---|---|
| `AFTER_OPAQUE` | terrain, entities and block entities drawn | the game's open main pass (colour + depth) |
| `AFTER_TRANSLUCENT` | water, glass and translucent entities drawn | the open main pass |
| `AFTER_LEVEL` | the whole level drawn, before the hand and GUI | `null`: open your own on `mainTarget()` |
| `AFTER_HUD` | the frame is complete | `null` |

`RenderContext` also gives the camera position, its rotation and projection (Minecraft's: reversed depth, 1 near and 0 far),
the partial tick, and `metal()`. All of these are valid only during the call.

### Replacing OpenGL drawing

There are two routes, in the order we recommend them:

1. **Minecraft's render pipelines.** Describe the draw as a `RenderPipeline` (shaders in GLSL, which Cadence translates to Metal)
   and draw into `ctx.pass()` or into a pass you open on `ctx.mainTarget()`. This works on Metal and on OpenGL, and with
   shader packs, because the pack's programs take over pipelines like the game's own.
2. **Metal directly.** `ctx.metal()` holds `id<MTLDevice>`, `id<MTLCommandQueue>`, the frame's `id<MTLCommandBuffer>`, the
   open `id<MTLRenderCommandEncoder>` (0 when none is open) and the main colour and depth `id<MTLTexture>`s, as raw pointers.
   Use them from your own native library (JNI) or Java's Foreign Function API. They are unretained: retain anything you keep.
   Inside the open encoder, restore what you change that pipelines don't set themselves (viewport, scissor, depth-stencil
   state, cull mode).

A hook that throws is logged and removed, so a broken addon can't take the game down.

## Asking Cadence to step aside

Cadence replaces some of Minecraft's code with faster versions. At launch it reads every mod's mixins, finds hooks on the same
methods, and puts that area back on the vanilla code path for the mod. For conflicts it can't see (reflection, ASM transformers,
behaviour that depends on vanilla timing), a mod can ask in its `fabric.mod.json`:

```json
"custom": { "cadence": { "stepAside": ["terrain", "mixin:ModelPartCompileMixin"] } }
```

| Area | What runs vanilla |
|---|---|
| `terrain` | Cadence's own terrain renderer (terrain then goes through the game's section pipeline, still on Metal) |
| `entityModels` | Cadence's entity model writers |
| `cpu` | all of Cadence's render-CPU replacements |
| `logic` | all of Cadence's game-logic replacements |
| `farTerrain` | the far terrain (off for the session) |
| `mixin:Name` | one of Cadence's mixins, by simple or full class name |

`Cadence.steppedAside()` lists what is in effect and for which mods. Cadence's settings screen shows the same.

## Version

`Cadence.API_VERSION` is 1. Additions keep the number; a change or removal of anything raises it.
