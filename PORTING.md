# Graft Shaders porting notes

Graft Shaders is Iris 1.11.4 (Minecraft 26.2, branch `26.2` of IrisShaders/Iris, LGPL-3.0) ported from OpenGL to Apple Metal.
It renders through Graft (`../Graft`), which is a required mod. Work happens on branch `metal`; upstream is the `upstream` remote.

Goal: native port. Iris's OpenGL objects become Metal objects. No OpenGL emulation layer.

## Build / run

```
# Graft first (Graft Shaders compiles against its jar)
cd ../Graft && ./gradlew :mc26_2:build
cd ../GraftShaders && ./gradlew :fabric:runClient --args="--quickPlaySingleplayer test"
```
Java 25. Dev world: copy `../Graft/mc26_2/run/world` to `fabric/run/saves/test`.

## Status

**Phase 1 reached:** a post-processing pack (`fabric/run/shaderpacks/MetalTest`, final pass: left half grayscale) renders
correctly on Metal. Pipeline construction, render targets, clears, depth copies, center-depth pass, uniforms, samplers and
the final pass all run through Metal. Screenshot: `devtools/logs/t.png`.

How it works:
- `metal/MetalGlsl`: Iris GLSL -> one shared std140 block for loose uniforms, explicit sampler bindings and
  name-matched varying locations, `#version 450 core` -> SPIR-V (shaderc) -> reflect block offsets (spvc) -> MSL.
- `metal/MetalProgram`: linked program; uniform locations = block member index, sampler locations = 100000 + binding.
- `metal/MetalTextureIds`, `MetalSamplers`, `MetalFramebuffers`: Iris int ids are lookup keys for Metal objects.
- `gl/IrisRenderSystem`: rewritten on Metal (Graft + Mojang's encoder API); unported features throw.
- `metal/GraftShadersDraw` + Graft `DrawOverride`: replaces Iris's GlCommandEncoder hook; draws tagged passes with the
  Iris program and retargets the Metal encoder when Iris binds its own framebuffer inside a pass (GL semantics).
- Graft additions used: `DrawOverride`, `MetalRenderPass.retarget/setBytes`, `MetalCommands.generateMipmaps`.

**Phase 2 (gbuffers) implemented, compile-only — not run yet** (testing paused at the user's request):
- `ShaderCreator.link` links pack world programs through `MetalGlsl`; attribute slots match Iris's GL binding (element i).
- `ExtendedShader` / `FallbackShader` stay `GlProgram` subclasses (type compatibility) but make no GL calls; `close()` frees
  the Metal program; uniform-block lookups use `MetalGlsl.Linked.blockBindings`.
- `MetalGlsl` gives pack-declared uniform blocks (e.g. `iris_DynamicTransforms`) explicit bindings, recorded by name.
- `GraftShadersDraw` world path (Metal version of MixinShaderManager_Overrides + GlCommandEncoder.trySetup): vanilla pipeline ->
  `IrisPipelines.getPipeline` -> shader map program -> `iris$setupState` (program, uniforms, samplers, gbuffer framebuffer) ->
  retarget -> vanilla uniform blocks bound by name (strip `iris_`), units 0-2 take vanilla Sampler0-2.
- `MetalProgram.pipeline(vanilla, shadow)`: topology, depth, cull, blend from the vanilla pipeline, cached per pipeline.
- Composite passes now draw through Metal render passes (were raw glDrawElements). PBR texture size/fill ported.
- Graft API added: `MetalRenderPassBackend.uniforms()/textureView()/sampler()/textureNames()`.

**Phase 3 (Sodium terrain) implemented, compile-only — not run yet:**
- Sodium draws through Blaze3D render passes on Metal (Graft's Sodium compat), so `GraftShadersDraw`'s world path picks
  up `sodium:` pipelines; `IrisPipelines` maps them to `SODIUM_TERRAIN_*` programs (Iris's own mapping).
- `MetalGlsl`: Iris's Sodium uniforms `u_RegionOffset/u_CurrentTime/u_RegionID` become Sodium's push-constant block
  (same layout as Sodium's Vulkan shader), which Graft's Sodium compat already fills per region.
- `samplerBuffer` uniforms (Sodium's `u_SectionTimeInfo`) are texel buffers, bound from the pass by name with the
  format from the pipeline's bind group.
- Reserved units follow Iris's MixinUniform names: 0 = Sampler0/u_BlockTex, 1 = Sampler1, 2 = Sampler2/u_LightTex.
- Programs keep the vertex format they were linked with (Iris's extended Sodium format), since Iris makes Sodium's
  pipeline report COMPACT.
- Graft: `retarget()` now re-applies vertex buffers and push constants.

**Advanced features implemented, compile-only — not run in-game yet:**
- Pack blend overrides: `BlendModeStorage` records program and per-buffer overrides; part of the Metal pipeline key.
- Shadow maps: shadow programs enabled; hardware shadow samplers (compare function) for `sampler2DShadow`; depth compare
  flipped in the shadow pass like Iris's GL hook; viewport/cull follow the shadow framebuffer.
- 3D textures: raw Metal 3D textures (`MetalTextureIds.Entry.raw`); 1D textures are 2D (SPIRV-Cross 1D-as-2D).
- SSBOs: `MetalBuffers` (shared MTLBuffers); `buffer` blocks remapped from pack index to Metal slot.
- Image load/store: `bindImageTexture` -> image units; images keep format/memory qualifiers; color textures are
  shader-writable; MSL 3.1 on macOS 14+ for native texture atomics.
- Compute: `MetalGlsl.linkCompute`, `GraftShadersCompute` (direct + indirect dispatch), shared resource binder.
- Graft: render passes suspend for mid-pass blits/compute and resume (generation counter forces rebinds);
  explicit viewports; compute encoder; compare samplers; 3D textures; storage buffer/image kinds.
- Checks: `./gradlew :core:smokeTest` (Graft: render, flip, clears, suspend/resume, compute) and
  `./gradlew :common:linkerTest` (Graft Shaders: uniforms/blocks/Sodium/texel/shadow/image/SSBO/compute -> MSL on device).

Geometry shaders (emulated, `MetalGeometry`): Metal has no geometry stage and SPIRV-Cross can't translate one, so the
pack's vertex + geometry shaders are folded into one Metal vertex function. Each input primitive gets max_vertices worth
of output slots; a slot's invocation fetches the primitive's vertices from the raw vertex/index buffers (format table
pushed per draw), runs the pack vertex shader per vertex, runs the geometry shader and keeps the emitted vertex that
falls in its slot (strips unrolled to lists). Verified on the GPU by `:common:linkerTest`.
Limits: no adjacency inputs, streams or interface blocks; gl_Layer/gl_ViewportIndex ignored; GS side effects run once
per output vertex (atomics over-count); indirect draws read their counts on the CPU.

Tessellation (`MetalTessellation`): per draw, a compute pre-pass (one thread per patch) fetches vertices, runs the pack
vertex shader and the control shader (invocations sequentially), and writes control points/patch outputs/levels plus
Metal half-float tessellation factors to scratch memory; then drawPatches runs the pack evaluation shader as the
post-tessellation vertex function, loading its inputs by gl_PrimitiveID. The pre-pass shares the draw's uniforms and
bindings (MetalRenderPass records bindings and replays them on the compute encoder and on resume). Verified on the GPU
by `:common:linkerTest` (subdivision count, pixels, winding with culling on).
Limits: no isolines, no interface blocks, barrier() is a no-op, varying arrays need literal sizes, one pre-pass per
instance, the render pass is split around every tessellated draw (costly with many draws).

Not supported:

Temporary stand-ins (marked `ponytail:` in code):
- Image binds use mip level 0 only.
- >16 samplers per program rejected (Metal sampler limit; needs sampler dedupe).
- Distant Horizons compat still raw GL (not loaded unless DH installed).

Dev loop: `devtools/mirun.sh <tag>` (git-ignored) builds, launches into the test world and screenshots the window.
It opens a game window and takes focus, so only run it when the user is OK with that.

## Scale (measured)

- 136 source files / ~19.6k lines touch OpenGL. `gl/` package is 6.1k lines; `IrisRenderSystem` alone has 97 GL entry points.
- 167 distinct LWJGL GL functions + 92 uses of Mojang's GL backend internals (`GlStateManager`, `GlProgram`, `GlTexture` ids,
  `GlCommandEncoder`/`GlRenderPass` mixins).
- Features used: compute, image load/store, SSBOs, MRT, 3D textures, blits, copyImageSubData, mipmap generation.

## Plan

Phase 1: a post-processing-only pack (composite/final passes) renders on Metal.
1. Metal core additions in Graft: compute encoder, image load/store (texture write), storage buffers, 3D textures,
   blits/copies, mipmap generation (`generateMipmapsForTexture:`).
2. Shader path: Iris already rewrites packs with glsl-transformer. Add a pass that moves loose (non-opaque) uniforms into one
   std140 uniform block and assigns explicit bindings/locations; then GLSL -> SPIR-V (shaderc) -> MSL (spvc), same chain
   Graft uses.
3. Replace Iris GL objects with Metal-backed ones, keeping class names where it reduces churn:
   `GlTexture`/`GlImage` -> MTLTexture, `GlFramebuffer` -> attachment set for a Metal render pass,
   `Program`/`ComputeProgram` -> MetalPipeline / compute pipeline, uniform holders -> CPU-side uniform block written per draw,
   `GlSampler` -> MTLSamplerState, `ShaderStorageBuffer` -> MTLBuffer.
4. `IrisRenderSystem`: delete GL capability queries (Metal limits are fixed), route remaining calls to the objects above.
5. `CompositeRenderer` / `FinalPassRenderer` / `FullScreenQuadRenderer` on Metal render passes.

Phase 2: gbuffers. Replace the `MixinGlCommandEncoder` / `MixinGlProgram` / `MixinShaderManager_Overrides` hooks with
extension points in Graft's backend (pipeline override when a pass starts, framebuffer redirect into Iris targets,
shadow-pass viewport/depth overrides). Vanilla terrain, entities, sky, translucents, shadow map.

Phase 3: Sodium chunk shaders (Iris patches them) on Metal.

Phase 4: real packs (Complementary, BSL), fix per-pack issues. Some packs will not work.
