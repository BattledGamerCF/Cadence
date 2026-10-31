# Cadence API for addon mods

Cadence draws Minecraft with Apple's Metal instead of OpenGL. Mods that use Minecraft's own rendering classes (render
types, render pipelines, vertex consumers, `GuiGraphics`) work unchanged, because Cadence implements them on Metal. Mods
that call OpenGL directly (LWJGL's `GL11`…`GL46` classes, `GlStateManager`) have no OpenGL context to call into while
Cadence runs on Metal.

The API in `dev.cadence.api` (in the main Cadence jar) lets another mod fill that gap. An addon can:

- See where it is running: `Cadence.isMetal()`, `Cadence.shaderPackActive()`, `Cadence.shaderPack()`,
  `Cadence.renderingShadowPass()`, `Cadence.sunPathRotation()`, `Cadence.farTerrainReach()`.
- Draw at fixed points of every frame (`Cadence.onRender`). It can use Minecraft's backend-neutral render passes, or Metal
  directly. `RenderContext.metal()` gives the device, command queue, command buffer, open render encoder and main
  textures.
- Ask Cadence to leave parts of the game on their vanilla code path for a mod that hooks the same code (`stepAside`,
  below).

The API costs nothing while no mod uses it. An unused stage is one array-length check per frame, and Cadence loads no
addon class unless a mod declares one.

## Setting up

Compile against the Cadence jar (as `compileOnly` / `modCompileOnly`). If Cadence is optional for your mod, check
`FabricLoader.getInstance().isModLoaded("cadence")` before touching `dev.cadence.api`. Alternatively, use the entry point
below, which Cadence calls only when it is installed.

```json
"entrypoints": { "cadence": ["com.example.myaddon.MyCadenceAddon"] }
```

The entry point runs once at client start, with the other client mods' initializers. This is before the game's window and
GPU device exist, so register hooks there and make GPU objects from inside the hooks. Until the device exists,
`Cadence.isMetal()` answers from the startup choice (Metal unless `-Dcadence.metal=false`). From the first frame on, it
answers exactly.

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

`RenderContext` also gives the camera position, its rotation and projection, the partial tick, and `metal()`. The
rotation and projection are Minecraft's own: reversed depth, with 1 at near and 0 at far. All of these values are valid
only during the call. Each call gets a new context, so don't keep it.

At `AFTER_HUD` the world's view is gone. `modelView()` and `projection()` are identity matrices, and `camera()` is the
camera entity's eye position. Draw in screen space there.

### Replacing OpenGL drawing

There are two routes, listed in the recommended order.

1. **Minecraft's render pipelines.** Describe the draw as a `RenderPipeline`, with shaders in GLSL, which Cadence
   translates to Metal. Draw into `ctx.pass()`, or into a pass you open on `ctx.mainTarget()`. This works on Metal and on
   OpenGL. It also works with shader packs, because the pack's programs take over pipelines like the game's own.
2. **Metal directly.** `ctx.metal()` holds `id<MTLDevice>`, `id<MTLCommandQueue>`, the frame's `id<MTLCommandBuffer>`, the
   open `id<MTLRenderCommandEncoder>` (0 when none is open) and the main colour and depth `id<MTLTexture>`s, as raw
   pointers. Use them from your own native library (JNI) or Java's Foreign Function API. They are unretained, so retain
   anything you keep. Inside the open encoder, restore what you change that pipelines don't set themselves: viewport,
   scissor, depth-stencil state and cull mode.

A hook that throws is logged and removed, so a broken addon can't take the game down.

## Asking Cadence to step aside

Cadence replaces some of Minecraft's code with faster versions. At launch it reads every mod's mixins, finds hooks on the
same methods, and puts that area back on the vanilla code path for the mod. Some conflicts it can't see, such as
reflection, ASM transformers, or behaviour that depends on vanilla timing. For those, a mod can ask in its
`fabric.mod.json`:

```json
"custom": { "cadence": { "stepAside": ["terrain", "mixin:BrainMixin"] } }
```

| Area | What runs vanilla |
|---|---|
| `terrain` | Cadence's own terrain renderer. Terrain then goes through the game's section pipeline, still on Metal. |
| `entityModels` | Cadence's entity model writers |
| `cpu` | all of Cadence's render-CPU replacements |
| `logic` | all of Cadence's game-logic replacements |
| `farTerrain` | the far terrain (off for the session) |
| `mixin:Name` | one of Cadence's mixins, by simple or full class name. A name Cadence doesn't have is reported in the log. |

`Cadence.steppedAside()` lists what is in effect and for which mods. Cadence's settings screen shows the same.

## The Iris API

Cadence's jar nests **Cadence Iris API** (mod id `cadence_iris_api`). It declares Iris's public API,
`net.irisshaders.iris.api.v0`, as Iris's 26.1 branch has it: `IrisApi` with the v0.3 and v0.4 additions, `IrisApiConfig`,
`IrisProgram`, `IrisShadowProgram`, `IrisShadowRenderCallback` and `IrisTextVertexSink`. Cadence answers the API.

| `IrisApi` | Cadence's answer |
|---|---|
| `isShaderPackInUse()` | `Cadence.shaderPackActive()` |
| `isRenderingShadowPass()` | `Cadence.renderingShadowPass()` |
| `getSunPathRotation()` | the active pack's `sunPathRotation` in degrees, 0 without a pack |
| `openMainIrisScreenObj(parent)` | Cadence's Shader Packs screen (returned, not opened, as Iris does) |
| `getMainScreenLanguageKey()` | the text "Shader Packs...". A translatable component of it shows as written, with or without Fabric API. |
| `getConfig().areShadersEnabled()` | whether a pack is drawing now |
| `getConfig().setShadersEnabledAndApply(b)` | off and back on as the K key does, on the render thread. The pack then loads in the background, and `isShaderPackInUse` turns true when it draws. |
| `createTextVertexSink(n, provider)` | quads in the game's `POSITION_COLOR_TEX_LIGHTMAP` format |
| `assignPipeline`, `assignPipelineShadow` | accepted, no effect: Cadence's packs pick the program for every pipeline themselves |
| `registerShadowRenderCallback` | accepted and kept. Cadence's shadow pass doesn't call it. |
| `isReverseZDuringShaders()` | false |

Cadence Iris API does **not** claim the mod id `iris`. Mods that find Iris by its classes (Xaero's Minimap) or call the API
directly use it. Mods that first check `FabricLoader.isModLoaded("iris")` see no Iris. This is deliberate. With an `iris`
mod present, other mods apply mixins into Iris's internals (ImmediatelyFast) or take paths for Iris's own renderer
(Distant Horizons). Both fail or misbehave without the real Iris.

For a mod that needs to see Iris installed, the release zip has an optional **`cadence-iris-provider`** jar.

- Its mod id is `iris` and its version is `1.11.4+cadence`, above the versions mods treat as an old Iris.
- It depends on Cadence Iris API.
- It declares that it breaks ImmediatelyFast and Distant Horizons, so Fabric names the conflict instead of starting into
  a crash.
- It was tested with the 17-mod compatibility set. Essential takes its Iris path and starts normally.
- A real Iris needs Sodium, which Cadence replaces. Cadence tells the provider from a real Iris by the `+cadence`
  version.

## Version

`Cadence.API_VERSION` is 1. Additions keep the number. A change or removal of anything raises it. `renderingShadowPass()`
and `sunPathRotation()` were added in 0.4.0-beta.20.
