# Changelog

Versions are `cadence` / `cadence_shaders`. The release jar contains both, plus Cadence HUD.

## 0.3.0-alpha.5 / shaders 0.2.2 (2026-10-15)

- **Pegasus works:** no more pink, red or blue halos where distant land meets water, and no yellow blocks on night
  water. Shader variables that a pack reads before setting are now 0, as on PC graphics drivers, instead of leftover GPU
  data. A uniform declared with the wrong type is now left at 0, as Iris does.
- **Faster shader packs that use parallax depth** (Pegasus, Bliss): the GPU can skip hidden terrain again. Pegasus is
  about 20% faster.
- The low-fps tip names the gentlest lighter preset first (for example "Medium is faster, Potato_Shadows fastest").

## 0.3.0-alpha.4 / shaders 0.2.1 (2026-10-14)

- Fixed black blobs along coastlines, speckled water and soft-looking frames: Auto resolution kept throwing away the
  packs' anti-aliasing history. It's now carried over, and Auto changes size less often.
- Full-screen shader passes are drawn the way Iris draws them (fixes Bliss's bloom).
- Bliss: fixed the olive haze at night and underwater and the milky daytime.
- Mipmaps are generated in one GPU command.

## 0.3.0-alpha.3 / shaders 0.2.0 (2026-10-14)

- All 12 tested shader packs run: compute passes, custom images, per-buffer blending, Iris macros and profiles.
- Faster shader math (relaxed precision where packs allow it).
- Heat control for fanless Macs; the release jar includes the shader loader.

Earlier history (from mcopt and the first Cadence alphas): [docs/CHANGES-FROM-MCOPT.md](docs/CHANGES-FROM-MCOPT.md).
