# Cadence Shaders (shaders)

The shader-pack loader (`cadence_shaders`): reads OptiFine/Iris-format packs, translates their GLSL to SPIR-V and then to
Metal (shaderc and SPIRV-Cross from LWJGL), and runs their passes on Cadence's renderer. It also holds Cadence's own
features under `dev.cadence.shaders.cadence` (Video Settings, Auto resolution, Faster Shadows, Heat Control, entity
culling, dynamic lights, Render Max FPS's settings, the benchmark, the self-test). Based on the
Shader Loader of [minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) (MIT; notice in
`LICENSE-MIT-shader-loader`).
