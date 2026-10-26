# Cadence API for addon mods

Cadence draws Minecraft with Apple's Metal instead of OpenGL. Mods that use Minecraft's own rendering classes (render types,
render pipelines, vertex consumers, `GuiGraphics`) work unchanged, because Cadence implements them on Metal. Mods that call
OpenGL directly (LWJGL's `GL11`…`GL46` classes, `GlStateManager`) have no OpenGL context to call into while Cadence runs on Metal.

The API in `dev.cadence.api` (in the main Cadence jar) is how another mod fills that gap. An addon can:

- see where it is running: `Cadence.isMetal()`, `Cadence.shaderPackActive()`, `Cadence.shaderPack()`,
  `Cadence.renderingShadowPass()`, `Cadence.sunPathRotation()`, `Cadence.farTerrainReach()`;
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

The entry point runs once at client start, with the other client mods' initializers, before the game's window and GPU device
exist: register hooks there and make GPU objects from inside them. `Cadence.isMetal()` answers from the startup choice until
the device exists (Metal unless `-Dcadence.metal=false`), exactly from the first frame on.

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
the partial tick, and `metal()`. All of these are valid only during the call (each call gets a new context: don't keep it).
At `AFTER_HUD` the world's view is gone: `modelView()` and `projection()` are identity matrices and `camera()` is the camera
entity's eye position, so draw in screen space there.

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
"custom": { "cadence": { "stepAside": ["terrain", "mixin:BrainMixin"] } }
```

| Area | What runs vanilla |
|---|---|
| `terrain` | Cadence's own terrain renderer (terrain then goes through the game's section pipeline, still on Metal) |
| `entityModels` | Cadence's entity model writers |
| `cpu` | all of Cadence's render-CPU replacements |
| `logic` | all of Cadence's game-logic replacements |
| `farTerrain` | the far terrain (off for the session) |
| `mixin:Name` | one of Cadence's mixins, by simple or full class name (a name Cadence doesn't have is reported in the log) |

`Cadence.steppedAside()` lists what is in effect and for which mods. Cadence's settings screen shows the same.

## The Iris API

Cadence's jar nests **Cadence Iris API** (mod id `cadence_iris_api`), which declares Iris's public API,
`net.irisshaders.iris.api.v0`, as Iris's 26.1 branch has it (`IrisApi` with the v0.3 and v0.4 additions, `IrisApiConfig`,
`IrisProgram`, `IrisShadowProgram`, `IrisShadowRenderCallback`, `IrisTextVertexSink`), and answers it from Cadence:

| `IrisApi` | Cadence's answer |
|---|---|
| `isShaderPackInUse()` | `Cadence.shaderPackActive()` |
| `isRenderingShadowPass()` | `Cadence.renderingShadowPass()` |
| `getSunPathRotation()` | the active pack's `sunPathRotation` in degrees, 0 without a pack |
| `openMainIrisScreenObj(parent)` | Cadence's Shader Packs screen (returned, not opened, as Iris does) |
| `getMainScreenLanguageKey()` | the text "Shader Packs..." (a translatable component of it shows as written, with or without Fabric API) |
| `getConfig().areShadersEnabled()` | whether a pack is drawing now |
| `getConfig().setShadersEnabledAndApply(b)` | off and back on as the K key does, on the render thread; the pack then loads in the background (`isShaderPackInUse` turns true when it draws) |
| `createTextVertexSink(n, provider)` | quads in the game's `POSITION_COLOR_TEX_LIGHTMAP` format |
| `assignPipeline`, `assignPipelineShadow` | accepted, no effect: Cadence's packs pick the program for every pipeline themselves |
| `registerShadowRenderCallback` | accepted and kept; Cadence's shadow pass doesn't call it |
| `isReverseZDuringShaders()` | false |

It does **not** claim the mod id `iris`: mods that find Iris by its classes (Xaero's Minimap) or call the API directly use it;
mods that first check `FabricLoader.isModLoaded("iris")` see no Iris. That is deliberate. With an `iris` mod present, other
mods apply mixins into Iris's internals (ImmediatelyFast) or take paths for Iris's own renderer (Distant Horizons), which
fail or misbehave without it.

For a mod that needs to see Iris installed, the release zip has an optional **`cadence-iris-provider`** jar: mod id `iris`,
version `1.11.4+cadence` (above the versions mods treat as an old Iris), depending on Cadence Iris API, and declaring that it
breaks ImmediatelyFast and Distant Horizons (Fabric then names the conflict instead of starting into a crash). Tested with
the 17-mod compatibility set (Essential takes its Iris path and starts normally). A real Iris needs Sodium, which Cadence
replaces; Cadence tells the provider from a real Iris by the `+cadence` version.

## Version

`Cadence.API_VERSION` is 1. Additions keep the number; a change or removal of anything raises it. (`renderingShadowPass()`
and `sunPathRotation()` were added in 0.4.0-beta.20.)
