package net.irisshaders.iris.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlRenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.graft.metal.MetalRenderPass;
import dev.graft.v26_3.DrawOverride;
import dev.graft.v26_3.MetalRenderPassBackend;
import dev.graft.v26_3.MetalSampler;
import dev.graft.v26_3.MetalTexture;
import dev.graft.v26_3.MtlBuffer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixinterface.RenderPassInterface;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.IrisProgram;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.HashMap;
import java.util.Map;

/**
 * Metal counterpart of Iris's GlCommandEncoder / GlDevice hooks. Before each draw:
 *  - passes Iris tagged with a custom pass (composite, final, center depth...) draw with the active Iris program;
 *  - while a pack overrides world shaders, vanilla pipelines are swapped for the pack's gbuffers/shadow program.
 * In both cases render state comes from the vanilla pipeline, and a framebuffer Iris binds inside the pass redirects the
 * draw there (GL semantics), which on Metal means retargeting the render encoder.
 */
public final class GraftShadersDraw implements DrawOverride {
	private GraftShadersDraw() {
	}

	public static void install() {
		DrawOverride.install(new GraftShadersDraw());
	}

	/** Backend (compiled) pipeline -> the vanilla RenderPipeline it was compiled from (see MixinShaderManager_Overrides). */
	private static final java.util.Map<Object, RenderPipeline> VANILLA = new java.util.IdentityHashMap<>();

	public static void track(Object backendPipeline, RenderPipeline vanilla) {
		VANILLA.put(backendPipeline, vanilla);
	}

	@Override
	public boolean beforeDraw(MetalRenderPassBackend backend) {
		RenderPipeline vanilla = backend.backendPipeline() != null ? VANILLA.get(backend.backendPipeline()) : null;
		if (vanilla == null) {
			return false;
		}
		var customPass = ((RenderPassInterface)backend).iris$getCustomPass();
		if (customPass != null) {
			customPass.setupState();
			if (DEBUG && LOGGED.add("pass " + customPass + " " + (GraftShadersState.program != null ? GraftShadersState.program.name : "NO PROGRAM"))) {
				net.irisshaders.iris.Iris.logger.info("Graft Shaders debug: custom pass {} -> {}", customPass, GraftShadersState.program != null ? GraftShadersState.program.name : "NO PROGRAM (draw skipped)");
			}
			return GraftShadersState.program != null && draw(backend, vanilla, false, true);
		}
		return overrideWorldPipeline(backend, vanilla);
	}

	/** Mirrors MixinShaderManager_Overrides + GlCommandEncoder.trySetup for world geometry. */
	private static boolean overrideWorldPipeline(MetalRenderPassBackend backend, RenderPipeline vanilla) {
		if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline iris) || !iris.shouldOverrideShaders() || ImmediateState.bypass) {
			return false;
		}
		if (vanilla == CompositeRenderer.COMPOSITE_PIPELINE || vanilla == RenderPipelines.ANIMATE_SPRITE_BLIT || vanilla == RenderPipelines.ANIMATE_SPRITE_INTERPOLATE) {
			return false;
		}
		ShaderKey key = IrisPipelines.getPipeline(iris, vanilla);
		GlProgram shader = key != null ? iris.getShaderMap().getShader(key) : null;
		if (!(shader instanceof IrisProgram program)) {
			return false;
		}
		GpuTextureView albedo = backend.textureView("Sampler0");
		if (albedo == null) {
			albedo = backend.textureView("u_BlockTex");
		}
		if (albedo != null) {
			iris.onSetAlbedoTex(albedo);
		}
		// Selects the Metal program, uploads Iris uniforms, binds Iris samplers and the gbuffer framebuffer.
		program.iris$setupState(java.util.List.of()); // vanilla samplers are bound in draw()
		return GraftShadersState.program != null && draw(backend, vanilla, ShadowRenderingState.areShadowsCurrentlyBeingRendered(), false);
	}

	private static boolean draw(MetalRenderPassBackend backend, RenderPipeline vanilla, boolean shadowPass, boolean fullScreenPass) {
		MetalProgram program = GraftShadersState.program;
		MetalRenderPass pass = backend.metal();
		retargetIfRebound(backend, pass, fullScreenPass ? overwritten(program, vanilla) : null);
		int[] vp = fullScreenPass ? GraftShadersState.passViewport : null;
		if (vp != null) {
			pass.setViewport(vp[0], vp[1], vp[2], vp[3]);
			pass.setScissor(vp[0], vp[1], vp[2], vp[3]);
		}
		if (DEBUG && (program.name.startsWith("composite") || program.name.startsWith("deferred") || program.name.equals("final")) && LOGGED.add("fb " + program.name)) {
			MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(GraftShadersState.framebuffer);
			StringBuilder att = new StringBuilder();
			if (fb != null) {
				for (int i = 0; i < fb.drawBuffers.length; i++) {
					att.append(" out").append(i).append("->attach").append(fb.drawBuffers[i]).append("=tex").append(fb.drawBuffers[i] >= 0 ? fb.colors[fb.drawBuffers[i]] : -1);
				}
			}
			Integer c0 = program.linked.samplerBindings().get("colortex0");
			net.irisshaders.iris.Iris.logger.info("Graft Shaders debug: {} fb {} target {}{} | colortex0 unit {} reads tex {}", program.name, GraftShadersState.framebuffer,
				((MetalPassTarget)backend).graftshaders$target(), att, c0 != null ? program.samplerUnits[c0] : -1, c0 != null ? GraftShadersState.unitTextures[program.samplerUnits[c0]] : -1);
		}
		pass.setPipeline(program.pipeline(vanilla, shadowPass));
		pass.setVertexBuffer(MetalProgram.DEFAULTS_SLOT, MetalProgram.defaultsBuffer(), 0);

		Map<String, GpuBufferSlice> vanillaUniforms = program.blockSlots.length + program.texelSlots.length > 0 ? backend.uniforms() : null;
		for (int i = 0; i < program.blockSlots.length; i++) {
			GpuBufferSlice slice = vanillaUniforms.get(program.blockSources[i]);
			if (slice != null) {
				pass.setBuffer(program.blockSlots[i], ((MtlBuffer)slice.buffer()).mtl(), slice.offset());
			}
		}
		for (int i = 0; i < program.texelSlots.length; i++) {
			String texelName = program.texelNames[i];
			GpuBufferSlice slice = vanillaUniforms.get(texelName);
			Map<String, com.mojang.renderpearl.api.GpuFormat> formats = TEXEL_FORMATS.get(vanilla);
			if (formats == null) {
				TEXEL_FORMATS.put(vanilla, formats = new HashMap<>());
			}
			com.mojang.renderpearl.api.GpuFormat format = formats.get(texelName);
			if (format == null && !formats.containsKey(texelName)) {
				String name = texelName;
				format = com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(vanilla.getBindGroupLayouts()).stream()
					.filter(u -> u.name().equals(name)).map(u -> u.gpuFormat()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
				formats.put(texelName, format);
			}
			if (slice == null || format == null) {
				continue;
			}
			long texture = dev.graft.v26_3.MetalGpuDevice.current().commands().texelBufferTexture(
				((MtlBuffer)slice.buffer()).mtl(), slice.offset(), slice.length(), dev.graft.metal.Mtl.pixelFormat(format.name()), format.blockSize()
			);
			pass.setTexture(program.texelSlots[i], texture, 0L);
		}
		bindIrisResources(program, passBinder(pass, program), backend);
		return true;
	}

	/** Binds into a render pass (both stages); the program's uniform block and sampler table are skipped when unchanged. */
	public static Binder passBinder(MetalRenderPass pass, MetalProgram program) {
		PASS_BINDER.pass = pass;
		PASS_BINDER.program = program;
		return PASS_BINDER;
	}

	private static final PassBinder PASS_BINDER = new PassBinder();

	private static final class PassBinder implements Binder {
		MetalRenderPass pass;
		MetalProgram program;

		@Override
		public void buffer(int slot, long buffer, long offset) {
			pass.setBuffer(slot, buffer, offset);
		}

		@Override
		public void texture(int slot, long texture, long sampler) {
			pass.setTexture(slot, texture, sampler);
		}

		@Override
		public void bytes(int slot, java.nio.ByteBuffer data) {
			if (slot == program.linked.blockBinding()) {
				pass.setBytesCached(slot, data, program, program.blockVersion); // skip unchanged uniform blocks
			} else if (slot == program.linked.samplerTableSlot()) {
				pass.setBytesCached(slot, data, program.samplerTable, program.samplerTableVersion);
			} else {
				pass.setBytes(slot, data);
			}
		}

		@Override
		public void sampler(int index, long sampler) {
			pass.setSampler(index, sampler);
		}
	}

	/** Where Iris resources get bound: a render pass (both stages) or the compute encoder. */
	public interface Binder {
		void buffer(int slot, long buffer, long offset);

		void texture(int slot, long texture, long sampler);

		void bytes(int slot, java.nio.ByteBuffer data);

		void sampler(int index, long sampler);
	}

	/**
	 * Binds what Iris itself manages for a program: SSBOs, images, the loose-uniform block and sampler units.
	 * reserved: the pass whose vanilla textures override texture units 0-2, or null.
	 */
	public static void bindIrisResources(MetalProgram program, Binder binder, MetalRenderPassBackend reserved) {
		for (int i = 0; i < program.ssboSlots.length; i++) {
			int index = program.ssboIndices[i];
			MetalBuffers.Buffer buffer = index < GraftShadersState.storageBuffers.length ? MetalBuffers.get(GraftShadersState.storageBuffers[index]) : null;
			if (buffer != null) {
				binder.buffer(program.ssboSlots[i], buffer.mtl(), 0);
			}
		}
		for (int binding : program.imageSlots) {
			int unit = program.samplerUnits[binding];
			long texture = unit < GraftShadersState.imageUnits.length ? MetalTextureIds.mtl(GraftShadersState.imageUnits[unit]) : 0L;
			if (texture != 0L) {
				binder.texture(binding, texture, 0L);
			}
		}
		if (program.linked.blockBinding() >= 0) {
			binder.bytes(program.linked.blockBinding(), program.blockView.clear());
		}
		// With a sampler table, textures bind alone and their sampler states are deduplicated into the shared array.
		java.nio.ByteBuffer table = program.samplerTable;
		if (table != null) {
			java.util.Arrays.fill(table.array(), (byte)0);
		}
		int states = 0;
		reservedResolved = 0;
		reservedFound = 0;
		for (int i = 0; i < program.samplerSlots.length; i++) {
			int binding = program.samplerSlots[i];
			int unit = program.samplerUnits[binding];
			int redirected = reserved != null ? program.samplerRedirects[i] : -1;
			boolean override = reserved != null && reserved(reserved, redirected >= 0 ? redirected : unit);
			if (DEBUG && program.name.contains("terrain") && LOGGED.add(program.name + "/" + program.samplerNames[binding])) {
				long t = override ? RESERVED[(redirected >= 0 ? redirected : unit) * 2] : MetalTextureIds.mtl(GraftShadersState.unitTextures[unit]);
				unit = redirected >= 0 ? redirected : unit;
				net.irisshaders.iris.Iris.logger.info("Graft Shaders debug: {} {} unit {} {} tex {}x{} mips {} type {}", program.name, program.samplerNames[binding], unit,
					override ? "reserved" : "iris id " + GraftShadersState.unitTextures[unit], t == 0 ? 0 : dev.graft.metal.ObjC.send(t, dev.graft.metal.ObjC.sel("width")),
					t == 0 ? 0 : dev.graft.metal.ObjC.send(t, dev.graft.metal.ObjC.sel("height")), t == 0 ? 0 : dev.graft.metal.ObjC.send(t, dev.graft.metal.ObjC.sel("mipmapLevelCount")),
					t == 0 ? 0 : dev.graft.metal.ObjC.send(t, dev.graft.metal.ObjC.sel("textureType")));
			}
			long texture;
			long sampler;
			if (override) {
				int r = (redirected >= 0 ? redirected : unit) * 2;
				texture = RESERVED[r];
				sampler = RESERVED[r + 1];
			} else {
				MetalTextureIds.Entry entry = MetalTextureIds.entry(GraftShadersState.unitTextures[unit]);
				texture = MetalTextureIds.mtl(entry);
				if (entry == null || texture == 0L) {
					continue;
				}
				int samplerId = GraftShadersState.unitSamplers[unit];
				sampler = samplerId != 0 ? MetalSamplers.get(samplerId) : MetalSamplers.forTexture(entry);
			}
			if (table == null) {
				binder.texture(binding, texture, sampler);
				continue;
			}
			binder.texture(binding, texture, 0L);
			int index = 0;
			while (index < states && index < STATES.length && STATES[index] != sampler) {
				index++;
			}
			if (index == states) {
				if (states < STATES.length) {
					STATES[states] = sampler;
				}
				states++;
			}
			// The shader indexes the table by its [[sampler(n)]] number (ShaderTranslator.samplerSlot).
			table.putInt(program.samplerTableIndices[i] * 4, index);
		}
		if (table != null) {
			if (states > 16) {
				throw new IllegalStateException("Graft Shaders: " + program.name + " needs " + states + " distinct sampler states, Metal allows 16");
			}
			// Every array entry must be bound; unused ones repeat the first state.
			for (int i = 0; i < 16 && states > 0; i++) {
				binder.sampler(i, STATES[Math.min(i, states - 1) == i ? i : 0]);
			}
			if (!table.clear().equals(program.lastSamplerTable.clear())) {
				program.lastSamplerTable.put(table).clear();
				table.clear();
				program.samplerTableVersion++;
			}
			binder.bytes(program.linked.samplerTableSlot(), table);
		}
	}

	/** Distinct sampler states of the draw being bound (sampler-table programs). */
	private static final long[] STATES = new long[16];

	/** Units 0-2 are reserved for the textures vanilla/Sodium bind (IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS), under the names MixinUniform maps on GL. Resolved once per draw. */
	private static final long[] RESERVED = new long[6];
	private static int reservedResolved;
	private static int reservedFound;

	private static boolean reserved(MetalRenderPassBackend backend, int unit) {
		if (unit >= RESERVED_NAMES.length) {
			return false;
		}
		if ((reservedResolved & 1 << unit) == 0) {
			reservedResolved |= 1 << unit;
			String vanillaName = firstBound(backend, RESERVED_NAMES[unit]);
			if (vanillaName != null && backend.textureView(vanillaName) instanceof MetalTexture.View view && backend.sampler(vanillaName) instanceof MetalSampler vs) {
				RESERVED[unit * 2] = view.mtl();
				RESERVED[unit * 2 + 1] = vs.mtl();
				reservedFound |= 1 << unit;
			}
		}
		return (reservedFound & 1 << unit) != 0;
	}

	/** Texel-buffer uniform formats per vanilla pipeline (constant; looked up once instead of per draw). */
	private static final java.util.IdentityHashMap<RenderPipeline, Map<String, com.mojang.renderpearl.api.GpuFormat>> TEXEL_FORMATS = new java.util.IdentityHashMap<>();

	private static final boolean DEBUG = java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/graftshaders-dump"));
	private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

	private static final String[][] RESERVED_NAMES = {{"Sampler0", "u_BlockTex"}, {"Sampler1"}, {"Sampler2", "u_LightTex"}};

	private static String firstBound(MetalRenderPassBackend backend, String[] names) {
		for (String name : names) {
			if (backend.textureView(name) != null) {
				return name;
			}
		}
		return null;
	}

	/** A framebuffer Iris bound after the pass started receives the draws (GL semantics). */
	/**
	 * For a full-screen pass: which output locations it overwrites everywhere, so their old contents need not be
	 * loaded (MTLLoadActionDontCare). Conservative: a pass at full size, without blending, whose shader can't discard,
	 * and only outputs the shader declares. Anything else keeps GL's "untouched pixels keep their value".
	 */
	// Off by default: some pack pass judged "fully overwritten" isn't in practice (world vanished behind leaves in
	// Complementary). -Dgraftshaders.loadSkip=true re-enables it for testing.
	private static final boolean NO_LOAD_SKIP = !Boolean.getBoolean("graftshaders.loadSkip");

	private static boolean[] overwritten(MetalProgram program, RenderPipeline vanilla) {
		if (NO_LOAD_SKIP || GraftShadersState.passViewport != null || program.linked.fragmentMsl() == null || program.linked.fragmentMsl().contains("discard_fragment")) {
			return null;
		}
		if (!vanilla.getColorTargetStates().isEmpty() && vanilla.getColorTargetStates().get(0) != null && vanilla.getColorTargetStates().get(0).blendFunction().isPresent()) {
			return null;
		}
		net.irisshaders.iris.gl.blending.BlendModeStorage.State blend = net.irisshaders.iris.gl.blending.BlendModeStorage.key();
		if (blend.overridden() && blend.override() != null) {
			return null;
		}
		boolean[] out = new boolean[8];
		for (int i = 0; i < out.length; i++) {
			boolean blended = blend.perBuffer().containsKey(i) && blend.perBuffer().get(i) != null;
			out[i] = !blended && program.linked.fragmentMsl().contains("[[color(" + i + ")]]");
		}
		return out;
	}

	private static void retargetIfRebound(MetalRenderPassBackend backend, MetalRenderPass pass, boolean[] overwritten) {
		MetalPassTarget target = (MetalPassTarget)backend;
		int bound = GraftShadersState.framebuffer;
		if (bound == 0 || bound == target.graftshaders$target()) {
			return;
		}
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(bound);
		if (fb == null) {
			return;
		}
		MetalRenderPass.Attachment[] colors = new MetalRenderPass.Attachment[fb.drawBuffers.length];
		for (int i = 0; i < fb.drawBuffers.length; i++) {
			int index = fb.drawBuffers[i];
			if (index >= 0 && MetalTextureIds.get(fb.colors[index]) instanceof MetalTexture t) {
				boolean skipLoad = overwritten != null && i < overwritten.length && overwritten[i];
				colors[i] = new MetalRenderPass.Attachment(t.mtl(), t.pixelFormat(), 0, skipLoad ? MetalRenderPass.DONT_CARE : null, Double.NaN);
			}
		}
		MetalRenderPass.Attachment depth = null;
		if (MetalTextureIds.get(fb.depth) instanceof MetalTexture d) {
			depth = new MetalRenderPass.Attachment(d.mtl(), d.pixelFormat(), 0, null, Double.NaN);
		}
		pass.retarget(colors, depth, GraftShadersState.program != null ? "iris:" + GraftShadersState.program.name : null);
		target.graftshaders$setTarget(bound);
	}
}
