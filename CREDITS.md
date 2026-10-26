# Credits and licenses

**Cadence** is a performance mod for Minecraft 26.3 (Fabric, Java 25) on Apple Silicon Macs: a native Apple Metal
renderer with a fast terrain path, shader-pack support on that renderer, and CPU, memory and startup work around it.

Cadence is a **fork**. It is built from two open-source projects, and it would not exist without them.

## Where Cadence comes from

| Part | Upstream | Author | License | Upstream commit used |
|---|---|---|---|---|
| Native Metal rendering backend, own terrain path, far terrain, recorder, startup and CPU work (`metal/`, `fpshud/`) | [mcopt](https://github.com/noahdunnagan/mcopt) | Noah Dunnagan | Apache-2.0 | `7b1d0fb` |
| Shader-pack loader and scene layer (`shaders/`: Java packages `dev.cadence.shaders`, `dev.cadence.scene`, `dev.cadence.sceneoptimizer`), plus `MetalPassHints`, `MetalPipelineMath`, `SpirvMathPolicy` in `metal/` | Shader Loader of [minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) ("Minecraft Shader Loader") | Kausik Velaga and the Minecraft Metal Renderer contributors | MIT | `c609745` |
| Everything else: the Cadence additions listed in `docs/CHANGES-FROM-MCOPT.md` (mostly `shaders/.../cadence/`, the `Cadence*` classes, `Legacy`, `JvmDefaults`, GPU culling, tools) | Cadence | the Cadence authors | Apache-2.0 | n/a |

The idea behind the present-queue change (`cadence.metal.presentQueue`) came from ByteV0rtex (noahdunnagan/mcopt#2).

The Iris API module (`irisapi/`) declares the interfaces of [Iris](https://github.com/IrisShaders/Iris)'s public API
(`net.irisshaders.iris.api.v0`: `IrisApi`, `IrisApiConfig`, `IrisTextVertexSink`) with their published names and
signatures, so that mods written against that API work with Cadence; the names and the API's design are the Iris
project's (IrisShaders, LGPL-3.0). No Iris source code is copied: the declarations were written from the API's signatures,
and every implementation (`dev.cadence.irisapi`) is Cadence's own, Apache-2.0.

## Licenses

* **Cadence as a whole is distributed under the Apache License, Version 2.0** (`LICENSE`). Apache-2.0 was chosen because
  mcopt, the larger half, is Apache-2.0 and the work derived from it has to stay under it; the MIT-licensed code is
  compatible with Apache-2.0 and may be included in an Apache-2.0 work as long as its notice is kept.
* **mcopt files** (Apache-2.0): they keep the Apache license and carry the line
  `// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.`, as Apache-2.0 section 4(b) asks. The mcopt
  `NOTICE` text is reproduced in `NOTICE` as section 4(d) asks.
* **Minecraft Shader Loader files** (MIT): the copyright and permission notice is kept in
  `shaders/LICENSE-MIT-shader-loader` and shipped inside the Cadence jars. Those files carry the line
  `// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.`
  MIT-licensed files stay available under MIT; the combined Cadence jars are Apache-2.0 plus that MIT notice.
* **Cadence's own files** are Apache-2.0, Copyright 2026 the Cadence authors.

## Not included

* Minecraft, Mojang's libraries, Fabric Loader, Fabric API and Sodium are not part of Cadence and are not in its jars. Cadence
  compiles against them at build time only (Fabric API pieces `compileOnly`).
* No shader pack is bundled (BSL and others are only named in docs and tests as packs the player supplies themselves). No
  fonts, images, sounds or other third-party assets are bundled; `git ls-files` shows only source, JSON, `.metal` shader
  kernels (from mcopt, and Cadence's own), and one generated class-preload list.
* The native library `libmcmetal.dylib` is built from the sources in `metal/src/main/native` and links only Apple system
  frameworks (AppKit, AVFAudio, CoreGraphics, CoreVideo, Foundation, Metal, MetalFX, QuartzCore). Shader translation (shaderc,
  SPIRV-Cross) is provided by the LWJGL libraries that Minecraft itself ships.

## Compatibility with the old names

Cadence used to be called mcopt (backend) plus "Minecraft Shader Loader" (shader module). Old `-Dmcopt.*`,
`-DminecraftShaders.*` and `-DminecraftScene.*` switches, and the old config files `config/mcopt.properties` and
`config/minecraft-shader-loader.properties`, still work (`dev.cadence.metal.Legacy`).
