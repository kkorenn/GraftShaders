package net.irisshaders.iris.metal;

import com.mojang.blaze3d.pipeline.BlendFunction;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.graft.metal.MetalPipeline;
import dev.graft.metal.Mtl;
import dev.graft.v1_21_11.MetalGpuDevice;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A linked Iris program on Metal. Uniform "locations" index {@link #members}; sampler locations are SAMPLER_BASE + binding,
 * and setting them (glUniform1i in GL terms) picks the texture unit that binding samples from.
 * Uniform values live in a CPU copy of the std140 block, sent with every draw.
 */
public final class MetalProgram {
	public static final int SAMPLER_BASE = 100_000;
	private static final Int2ObjectOpenHashMap<MetalProgram> PROGRAMS = new Int2ObjectOpenHashMap<>();
	/** Above Graft's Sodium GL names, so a Sodium GlProgram can carry a Graft Shaders program id (GraftShadersSodium). */
	private static int next = 1 << 24;

	public final String name;
	public final MetalGlsl.Linked linked;
	private final List<String> memberNames = new ArrayList<>();
	private final List<MetalGlsl.Member> members = new ArrayList<>();
	/** binding -> texture unit */
	public final int[] samplerUnits;
	/** Bumped on every uniform write; lets draws skip re-uploading an unchanged block. */
	public long blockVersion;
	public final String[] samplerNames;
	public final ByteBuffer block;
	/** View of {@link #block} handed to binders (rewound per draw instead of duplicated). */
	public final ByteBuffer blockView;
	private final Map<Object, MetalPipeline> pipelines = new java.util.HashMap<>();
	/** Sodium terrain programs: uniform-block Metal slot -> GL uniform-buffer binding point (glUniformBlockBinding). */
	public final Map<Integer, Integer> sodiumBlockPoints = new java.util.HashMap<>();

	/** Sodium's vertex layout (its GL vertex array), by GL attribute location. */
	public record SodiumLayout(int stride, List<SodiumAttribute> attributes) {
	}

	public record SodiumAttribute(int location, int offset, String format) {
	}

	private MetalProgram(String name, MetalGlsl.Linked linked) {
		this.name = name;
		this.linked = linked;
		linked.uniforms().forEach((n, m) -> {
			memberNames.add(n);
			members.add(m);
		});
		this.samplerUnits = new int[linked.kinds().size()];
		this.samplerNames = new String[linked.kinds().size()];
		linked.samplerBindings().forEach((n, b) -> samplerNames[b] = n);
		// Metal's struct for the block is padded to a 16-byte multiple (std140 struct alignment); upload that much.
		this.block = linked.blockSize() > 0 ? MemoryUtil.memCalloc((linked.blockSize() + 15) & ~15) : null;
		this.blockView = block != null ? block.duplicate() : null;
		blockSlots = new int[linked.blockBindings().size()];
		blockSources = new String[blockSlots.length];
		int i = 0;
		for (Map.Entry<String, Integer> e : linked.blockBindings().entrySet()) {
			blockSources[i] = e.getKey().startsWith("iris_") ? e.getKey().substring(5) : e.getKey();
			blockSlots[i++] = e.getValue();
		}
		texelSlots = new int[linked.texelBufferBindings().size()];
		texelNames = new String[texelSlots.length];
		i = 0;
		for (Map.Entry<String, Integer> e : linked.texelBufferBindings().entrySet()) {
			texelNames[i] = e.getKey();
			texelSlots[i++] = e.getValue();
		}
		ssboIndices = new int[linked.storageBufferBindings().size()];
		ssboSlots = new int[ssboIndices.length];
		i = 0;
		for (Map.Entry<Integer, Integer> e : linked.storageBufferBindings().entrySet()) {
			ssboIndices[i] = e.getKey();
			ssboSlots[i++] = e.getValue();
		}
		imageSlots = linked.imageBindings().values().stream().mapToInt(Integer::intValue).toArray();
		samplerSlots = new int[linked.samplerBindings().size()];
		samplerRedirects = new int[samplerSlots.length];
		samplerTableIndices = new int[samplerSlots.length];
		i = 0;
		for (Map.Entry<String, Integer> e : linked.samplerBindings().entrySet()) {
			samplerSlots[i] = e.getValue();
			samplerRedirects[i] = redirectedUnit(linked.samplerBindings().keySet(), e.getKey());
			samplerTableIndices[i++] = dev.graft.metal.ShaderTranslator.samplerSlot(linked.kinds(), e.getValue());
		}
		samplerTable = linked.samplerTableSlot() >= 0 ? ByteBuffer.allocate(linked.kinds().size() * 4).order(java.nio.ByteOrder.nativeOrder()) : null;
		lastSamplerTable = samplerTable != null ? ByteBuffer.allocate(samplerTable.capacity()) : null;
	}

	// Per-draw binding lists, flattened from linked's maps (same iteration order) so draws don't walk maps.
	/** Uniform blocks: Metal slot, and the vanilla uniform name feeding it (iris_ prefix stripped). */
	public final int[] blockSlots;
	public final String[] blockSources;
	public final int[] texelSlots;
	public final String[] texelNames;
	/** SSBOs: Iris buffer index -> Metal slot. */
	public final int[] ssboIndices;
	public final int[] ssboSlots;
	public final int[] imageSlots;
	/** Samplers: binding, reserved unit it reads instead (-1: none), index in the shared sampler table. */
	public final int[] samplerSlots;
	public final int[] samplerRedirects;
	public final int[] samplerTableIndices;
	/** Sampler-table contents for this draw, and the last uploaded ones (version bumps when they differ). */
	public final ByteBuffer samplerTable;
	public final ByteBuffer lastSamplerTable;
	public long samplerTableVersion;

	/**
	 * MixinUniform on GL: a program without Sampler0/u_BlockTex gets the vanilla albedo bound to tex, else gtexture,
	 * else texture (unit 0); without Sampler1, iris_overlay gets unit 1; without Sampler2/u_LightTex, lightmap gets unit
	 * 2. That vanilla bind overrides the unit Iris assigned the sampler, so these names read the reserved textures.
	 */
	private static int redirectedUnit(java.util.Set<String> names, String name) {
		switch (name) {
			case "tex", "gtexture", "texture" -> {
				if (names.contains("Sampler0") || names.contains("u_BlockTex")) {
					return -1;
				}
				String first = names.contains("tex") ? "tex" : names.contains("gtexture") ? "gtexture" : "texture";
				return name.equals(first) ? 0 : -1;
			}
			case "iris_overlay" -> {
				return names.contains("Sampler1") ? -1 : 1;
			}
			case "lightmap" -> {
				return names.contains("Sampler2") || names.contains("u_LightTex") ? -1 : 2;
			}
			default -> {
				return -1;
			}
		}
	}

	public static int create(String name, MetalGlsl.Linked linked) {
		return create(name, linked, null);
	}

	/** vertexFormat: the layout the program was linked against (world programs); null = use the vanilla pipeline's. */
	public static int create(String name, MetalGlsl.Linked linked, VertexFormat vertexFormat) {
		int id = next++;
		MetalProgram program = new MetalProgram(name, linked);
		program.vertexFormat = vertexFormat;
		PROGRAMS.put(id, program);
		return id;
	}

	private VertexFormat vertexFormat;
	private long computePipeline;
	/** Tessellation pre-pass (vertex + control stages), shared by all pipeline variants of this program. */
	private long prepassState;

	/** MTLComputePipelineState for compute programs, built on first dispatch. */
	public long computePipeline() {
		if (computePipeline == 0L) {
			computePipeline = MetalGpuDevice.current().metal().newComputePipeline("iris:" + name, linked.computeMsl());
		}
		return computePipeline;
	}

	public static MetalProgram get(int id) {
		return PROGRAMS.get(id);
	}

	public static void delete(int id) {
		MetalProgram p = PROGRAMS.remove(id);
		if (p != null) {
			if (GraftShadersState.program == p) GraftShadersState.program = null;
			p.pipelines.values().forEach(MetalPipeline::close);
			dev.graft.metal.ObjC.release(p.computePipeline);
			dev.graft.metal.ObjC.release(p.prepassState);
			if (p.block != null) {
				MemoryUtil.memFree(p.block);
			}
		}
	}

	public int location(String uniform) {
		Integer binding = linked.samplerBindings().get(uniform);
		if (binding == null) {
			binding = linked.imageBindings().get(uniform); // image uniforms select an image unit, like samplers
		}
		if (binding != null) {
			return SAMPLER_BASE + binding;
		}
		int i = memberNames.indexOf(uniform);
		return i;
	}

	public List<String> activeUniformNames() {
		List<String> all = new ArrayList<>(memberNames);
		all.addAll(linked.samplerBindings().keySet());
		return all;
	}

	public MetalGlsl.Member member(int location) {
		return location >= 0 && location < members.size() ? members.get(location) : null;
	}

	// ---- uniform writes (std140) ------------------------------------------------------------------------------------

	public void setInts(int location, int... v) {
		if (location >= SAMPLER_BASE) {
			int binding = location - SAMPLER_BASE;
			if (binding < samplerUnits.length) {
				samplerUnits[binding] = v[0];
			}
			return;
		}
		MetalGlsl.Member m = member(location);
		if (m == null || block == null) {
			return;
		}
		boolean fp = m.baseType() == Spvc.SPVC_BASETYPE_FP32;
		boolean changed = false;
		for (int i = 0; i < Math.min(v.length, m.vecSize()); i++) {
			changed |= put(m.offset() + i * 4, fp ? Float.floatToRawIntBits(v[i]) : v[i]);
		}
		if (changed) {
			blockVersion++;
		}
	}

	/** setInts / setFloats without the varargs array (glUniform1i..4f run for every uniform of every draw). */
	public void setInts(int location, int count, int v0, int v1, int v2, int v3) {
		if (location >= SAMPLER_BASE) {
			int binding = location - SAMPLER_BASE;
			if (binding < samplerUnits.length) {
				samplerUnits[binding] = v0;
			}
			return;
		}
		MetalGlsl.Member m = member(location);
		if (m == null || block == null) {
			return;
		}
		boolean fp = m.baseType() == Spvc.SPVC_BASETYPE_FP32;
		boolean changed = false;
		for (int i = 0; i < Math.min(count, m.vecSize()); i++) {
			int v = i == 0 ? v0 : i == 1 ? v1 : i == 2 ? v2 : v3;
			changed |= put(m.offset() + i * 4, fp ? Float.floatToRawIntBits(v) : v);
		}
		if (changed) {
			blockVersion++;
		}
	}

	public void setFloats(int location, int count, float v0, float v1, float v2, float v3) {
		MetalGlsl.Member m = member(location);
		if (m == null || block == null) {
			return;
		}
		boolean fp = m.baseType() == Spvc.SPVC_BASETYPE_FP32;
		boolean changed = false;
		for (int i = 0; i < Math.min(count, m.vecSize()); i++) {
			float v = i == 0 ? v0 : i == 1 ? v1 : i == 2 ? v2 : v3;
			changed |= put(m.offset() + i * 4, fp ? Float.floatToRawIntBits(v) : (int)v);
		}
		if (changed) {
			blockVersion++;
		}
	}

	/** Writes one 32-bit value; false when it was already there, so unchanged blocks keep their version. */
	private boolean put(int offset, int bits) {
		if (block.getInt(offset) == bits) {
			return false;
		}
		block.putInt(offset, bits);
		return true;
	}

	public void setFloats(int location, float... v) {
		MetalGlsl.Member m = member(location);
		if (m == null || block == null) {
			return;
		}
		boolean fp = m.baseType() == Spvc.SPVC_BASETYPE_FP32;
		boolean changed = false;
		for (int i = 0; i < Math.min(v.length, m.vecSize()); i++) {
			changed |= put(m.offset() + i * 4, fp ? Float.floatToRawIntBits(v[i]) : (int)v[i]);
		}
		if (changed) {
			blockVersion++;
		}
	}

	/** Column-major matrix with `size` rows/cols; std140 pads each column to matrixStride. */
	public void setMatrix(int location, int size, boolean transpose, FloatBuffer data) {
		MetalGlsl.Member m = member(location);
		if (m == null || block == null) {
			return;
		}
		int base = data.position();
		int stride = m.matrixStride() > 0 ? m.matrixStride() : 16;
		boolean changed = false;
		for (int col = 0; col < size; col++) {
			for (int row = 0; row < size; row++) {
				float value = transpose ? data.get(base + row * size + col) : data.get(base + col * size + row);
				changed |= put(m.offset() + col * stride + row * 4, Float.floatToRawIntBits(value));
			}
		}
		if (changed) {
			blockVersion++;
		}
	}

	// ---- pipeline -----------------------------------------------------------------------------------------------------

	/**
	 * Metal pipeline for drawing with this program in place of a vanilla pipeline: vertex layout, topology, depth, cull and
	 * blend come from the vanilla pipeline (as Iris's GL path applies them). Cached per vanilla pipeline.
	 * ponytail: Iris BlendModeOverride / per-buffer blend directives are not applied yet (pack blend.<program> settings).
	 */
	public MetalPipeline pipeline(RenderPipeline vanilla, boolean shadowPass) {
		return pipeline(vanilla, shadowPass, null);
	}

	/** sodium: the vertex layout of Sodium's terrain meshes, or null to use the program's / vanilla pipeline's format. */
	public MetalPipeline pipeline(RenderPipeline vanilla, boolean shadowPass, SodiumLayout sodium) {
		BlendModeStorage.State blend = BlendModeStorage.key();
		if (lastPipeline != null && vanilla == lastVanilla && shadowPass == lastShadow && blend == lastBlend && sodium == lastSodium) {
			return lastPipeline; // same inputs by identity as the previous draw (the common case): skip the key + map lookup
		}
		MetalPipeline pipeline = pipelines.computeIfAbsent(new PipelineKey(vanilla, shadowPass, blend, sodium), k -> build(vanilla, shadowPass, blend, sodium));
		lastVanilla = vanilla;
		lastShadow = shadowPass;
		lastBlend = blend;
		lastSodium = sodium;
		return lastPipeline = pipeline;
	}

	private RenderPipeline lastVanilla;
	private boolean lastShadow;
	private BlendModeStorage.State lastBlend;
	private SodiumLayout lastSodium;
	private MetalPipeline lastPipeline;

	/** RenderPipeline compares by identity; blend snapshots and Sodium layouts by value. */
	private record PipelineKey(RenderPipeline vanilla, boolean shadow, BlendModeStorage.State blend, SodiumLayout sodium) {
	}

	/** The shadow pass undoes reverse-Z, so Iris flips depth comparisons there (MixinGlCommandEncoder.iris$pass). */
	private static String flipped(String compare) {
		return switch (compare) {
			case "LESS_THAN" -> "GREATER_THAN";
			case "LESS_THAN_OR_EQUAL" -> "GREATER_THAN_OR_EQUAL";
			case "GREATER_THAN_OR_EQUAL" -> "LESS_THAN_OR_EQUAL";
			case "GREATER_THAN" -> "LESS_THAN";
			default -> compare;
		};
	}

	private static long glBlendFactor(int gl) {
		return switch (gl) {
			case 0 -> 0;
			case 1 -> 1;
			case 0x300 -> 2;
			case 0x301 -> 3;
			case 0x302 -> 4;
			case 0x303 -> 5;
			case 0x304 -> 8;
			case 0x305 -> 9;
			case 0x306 -> 6;
			case 0x307 -> 7;
			case 0x308 -> 10;
			default -> throw new IllegalArgumentException("Graft Shaders: GL blend factor 0x" + Integer.toHexString(gl));
		};
	}

	private static MetalPipeline.ColorTarget target(BlendMode mode, long writeMask) {
		return mode == null ? MetalPipeline.ColorTarget.opaque(0, writeMask) : new MetalPipeline.ColorTarget(0, writeMask,
			glBlendFactor(mode.srcRgb()), glBlendFactor(mode.dstRgb()), 0, glBlendFactor(mode.srcAlpha()), glBlendFactor(mode.dstAlpha()), 0);
	}

	private MetalPipeline build(RenderPipeline vanilla, boolean shadowPass, BlendModeStorage.State blend, SodiumLayout sodium) {
		// Sodium + Iris: the pipeline reports COMPACT for lookup, but meshes use Iris's extended format (linked here).
		VertexFormat format = sodium != null ? null : vertexFormat != null ? vertexFormat : vanilla.getVertexFormat();
		if (format != null && format.getElements().isEmpty()) {
			format = null;
		}
		List<MetalPipeline.Attribute> attributes = new ArrayList<>();
		if (sodium != null) {
			if (linked.tessellation() != null || linked.geometry() != null) {
				// ponytail: geometry/tessellation emulation reads a Blaze3D VertexFormat; Sodium meshes would need a table from SodiumLayout.
				throw new IllegalStateException("Graft Shaders: geometry/tessellation terrain shaders (" + name + ") are not supported with Sodium on 1.21.x yet");
			}
			for (SodiumAttribute a : sodium.attributes()) {
				attributes.add(new MetalPipeline.Attribute(0, a.location(), a.offset(), Mtl.vertexFormat(matchSignedness(a.format(), a.location()))));
			}
		} else if (format != null) {
			List<VertexFormatElement> elements = format.getElements();
			List<String> names = format.getElementAttributeNames();
			for (int e = 0; e < elements.size(); e++) {
				VertexFormatElement element = elements.get(e);
				Integer location = linked.attributeLocations().get("iris_" + names.get(e));
				if (location == null) {
					location = linked.attributeLocations().get(names.get(e));
				}
				if (location != null && location >= 0) {
					attributes.add(new MetalPipeline.Attribute(0, location, format.getOffset(element),
						Mtl.vertexFormat(matchSignedness(MetalGpuDevice.vertexFormatName(element), location))));
				}
			}
		}
		boolean defaults = addDefaultAttributes(attributes);
		MetalPipeline.ColorTarget target = MetalGpuDevice.colorTarget(vanilla);
		MetalPipeline.ColorTarget[] targets = new MetalPipeline.ColorTarget[8];
		long writeMask = target.writeMask();
		for (int i = 0; i < targets.length; i++) {
			if (blend.perBuffer().containsKey(i)) {
				targets[i] = target(blend.perBuffer().get(i), writeMask);
			} else if (blend.overridden()) {
				targets[i] = target(blend.override(), writeMask);
			} else {
				targets[i] = target;
			}
		}
		// 1.21.x uses standard depth everywhere (no reverse-Z, so no shadow-pass compare flip as on 26.x).
		MetalPipeline.Raster base = MetalGpuDevice.raster(vanilla);
		MetalGeometry.Info geometry = linked.geometry();
		MetalPipeline.Raster raster = new MetalPipeline.Raster(
			geometry != null ? geometry.outPrimitive() : base.primitive(), geometry == null && base.triangleFan(), base.cull() && !shadowPass, base.wireframe(),
			base.depthCompare(), base.depthWrite(), base.depthBiasConstant(), base.depthBiasSlope()
		);
		MetalTessellation.Info tess = linked.tessellation();
		if (tess != null) {
			if (format == null) {
				throw new IllegalStateException("Graft Shaders: tessellation shader " + name + " used with a pipeline that has no vertex format");
			}
			MetalPipeline pipeline = new MetalPipeline(
				MetalGpuDevice.current().metal(), "iris:" + name + "/" + vanilla.getLocation(), linked.vertexMsl(), linked.fragmentMsl(), linked.kinds(),
				List.of(), List.of(), targets, raster
			);
			if (prepassState == 0L) {
				prepassState = MetalGpuDevice.current().metal().newComputePipeline("iris:" + name + "/prepass", tess.prepassMsl());
			}
			pipeline.tessellation = new MetalPipeline.Tessellation(
				prepassState, MetalTessellation.THREADS, tess.inVerts(), tess.outVerts(), tess.patchBytes(), tess.quads(), tess.partition(), tess.winding(),
				linked.blockBindings().get(MetalTessellation.PARAMS_BLOCK), linked.storageBufferBindings().get(MetalGeometry.VERTEX_SSBO),
				linked.storageBufferBindings().get(MetalGeometry.INDEX_SSBO), linked.storageBufferBindings().get(MetalTessellation.CONTROL_POINT_SSBO),
				linked.storageBufferBindings().get(MetalTessellation.FACTOR_SSBO), format.getVertexSize(), attributeTable(format, tess.attributeSlots())
			);
			return pipeline;
		}
		if (geometry == null) {
			String fsl = linked.fragmentMsl();
			boolean prepass = !shadowPass && usesDepthPrepass(fsl, raster, targets);
			MetalPipeline pipeline = new MetalPipeline(
				MetalGpuDevice.current().metal(), "iris:" + name + "/" + vanilla.getLocation(), linked.vertexMsl(),
				prepass ? fsl.replace("fragment main0_out main0(", "[[early_fragment_tests]] fragment main0_out main0(") : fsl, linked.kinds(),
				vertexBuffers(sodium != null ? sodium.stride() : format != null ? format.getVertexSize() : 0, defaults), attributes, targets,
				prepass ? new MetalPipeline.Raster(raster.primitive(), false, raster.cull(), raster.wireframe(), orEqual(raster.depthCompare()), false,
					raster.depthBiasConstant(), raster.depthBiasSlope()) : raster
			);
			if (prepass) {
				// Outputs dead: the compiler keeps only the texture reads and math that decide discards.
				pipeline.depthPrepass = pipeline.createDepthPrepass(fsl.replace("return out;", "return {};"), raster);
			}
			return pipeline;
		}
		// Geometry emulation: no vertex descriptor, the vertex function fetches from the raw buffers via this table.
		if (format == null) {
			throw new IllegalStateException("Graft Shaders: geometry shader " + name + " used with a pipeline that has no vertex format");
		}
		int[] table = attributeTable(format, geometry.attributeSlots());
		MetalPipeline pipeline = new MetalPipeline(
			MetalGpuDevice.current().metal(), "iris:" + name + "/" + vanilla.getLocation(), linked.vertexMsl(), linked.fragmentMsl(), linked.kinds(),
			List.of(), List.of(), targets, raster
		);
		pipeline.geometry = new MetalPipeline.Geometry(
			geometry.inVerts(), geometry.slotsPerPrim(), linked.blockBindings().get(MetalGeometry.PARAMS_BLOCK),
			linked.storageBufferBindings().get(MetalGeometry.VERTEX_SSBO), linked.storageBufferBindings().get(MetalGeometry.INDEX_SSBO), format.getVertexSize(), table
		);
		return pipeline;
	}

	private static final boolean DEPTH_PREPASS = !"false".equals(System.getProperty("graftshaders.depthPrepass"));

	/**
	 * Depth prepass for solid gbuffer terrain (cutout measured slower: the doubled vertex work outweighs its overdraw): pack terrain shaders are long and contain discard, which turns off the
	 * GPU's hidden-surface removal, so every overlapping fragment gets fully shaded. A cheap depth-only pass (same
	 * vertex function, outputs stripped) first, then the real shader with early depth test and no depth writes, shades
	 * each visible pixel once. Only for programs whose fragment stage has no side effects or depth output.
	 */
	private boolean usesDepthPrepass(String fsl, MetalPipeline.Raster raster, MetalPipeline.ColorTarget[] targets) {
		if (!DEPTH_PREPASS || !(name.equals("sodium_terrain_solid") || name.equals("terrain_solid"))) {
			return false;
		}
		if (raster.depthCompare() < 0 || !raster.depthWrite() || raster.triangleFan() || raster.wireframe()) {
			return false;
		}
		for (MetalPipeline.ColorTarget t : targets) {
			if (t != null && t.srcRgb() >= 0) {
				return false;
			}
		}
		return fsl.contains("fragment main0_out main0(") && !fsl.contains("[[depth(") && !fsl.contains("access::write")
			&& !fsl.contains("access::read_write") && !fsl.contains("atomic") && !fsl.matches("(?s).*[(,]\\s*device .*");
	}

	/** Metal compare function that also passes on equal depth (LESS -> LESS_EQUAL, GREATER -> GREATER_EQUAL). */
	private static long orEqual(long compare) {
		return compare == 1 ? 3 : compare == 4 ? 6 : compare;
	}

	/** Raw-fetch table for geometry/tessellation emulation: {offset, type, components, normalized} per attribute location. */
	private int[] attributeTable(VertexFormat format, int slots) {
		int[] table = new int[slots * 4];
		List<VertexFormatElement> elements = format.getElements();
		List<String> names = format.getElementAttributeNames();
		for (int e = 0; e < elements.size(); e++) {
			VertexFormatElement element = elements.get(e);
			Integer location = linked.attributeLocations().get("iris_" + names.get(e));
			if (location == null) {
				location = linked.attributeLocations().get(names.get(e));
			}
			if (location != null && location >= 0 && location < slots) {
				int[] code = MetalGeometry.elementCode(MetalGpuDevice.vertexFormatName(element));
				table[location * 4] = format.getOffset(element);
				table[location * 4 + 1] = code[0];
				table[location * 4 + 2] = code[1];
				table[location * 4 + 3] = code[2];
			}
		}
		return table;
	}

	/** Vertex buffer slot holding the constant (0,0,0,1) values for attributes the vertex format lacks. */
	public static final int DEFAULTS_SLOT = 1;
	private static long defaultsBuffer;
	private static final java.util.regex.Pattern MSL_ATTRIBUTE = java.util.regex.Pattern.compile("(\\w+)\\s+\\w+\\s*\\[\\[attribute\\((\\d+)\\)\\]\\]");

	/** float4 / int4 / uint4 (0,0,0,1), at offsets 0 / 16 / 32. Bound at DEFAULTS_SLOT for every Iris draw. */
	public static long defaultsBuffer() {
		if (defaultsBuffer == 0L) {
			defaultsBuffer = MetalGpuDevice.current().metal().newBuffer(48);
			java.lang.foreign.MemorySegment m = java.lang.foreign.MemorySegment.ofAddress(dev.graft.metal.MetalDevice.contents(defaultsBuffer)).reinterpret(48);
			m.fill((byte)0);
			m.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 12, 1.0F);
			m.set(java.lang.foreign.ValueLayout.JAVA_INT, 28, 1);
			m.set(java.lang.foreign.ValueLayout.JAVA_INT, 44, 1);
		}
		return defaultsBuffer;
	}

	/**
	 * GL feeds shader inputs the vertex format doesn't provide a constant (0,0,0,1); Metal refuses to build such a
	 * pipeline. Inputs come from the translated MSL ([[attribute(n)]]); missing ones read the constant defaults buffer.
	 */
	private boolean addDefaultAttributes(List<MetalPipeline.Attribute> attributes) {
		java.util.Set<Integer> provided = new java.util.HashSet<>();
		attributes.forEach(a -> provided.add(a.location()));
		boolean any = false;
		java.util.regex.Matcher m = MSL_ATTRIBUTE.matcher(linked.vertexMsl());
		while (m.find()) {
			int location = Integer.parseInt(m.group(2));
			if (provided.add(location)) {
				String type = m.group(1);
				boolean unsigned = type.startsWith("uint") || type.startsWith("ushort") || type.startsWith("uchar");
				boolean signed = !unsigned && (type.startsWith("int") || type.startsWith("short") || type.startsWith("char"));
				// MTLVertexFormatUInt4 = 39, Int4 = 35, Float4 = 31
				attributes.add(new MetalPipeline.Attribute(DEFAULTS_SLOT, location, unsigned ? 32 : signed ? 16 : 0, unsigned ? 39 : signed ? 35 : 31));
				any = true;
			}
		}
		return any;
	}

	private static List<MetalPipeline.VertexBuffer> vertexBuffers(int stride, boolean defaults) {
		List<MetalPipeline.VertexBuffer> buffers = new ArrayList<>();
		if (stride > 0) {
			buffers.add(new MetalPipeline.VertexBuffer(0, stride, 0));
		}
		if (defaults) {
			buffers.add(new MetalPipeline.VertexBuffer(DEFAULTS_SLOT, 16, -1)); // constant step: same values for every vertex
		}
		return buffers;
	}

	/**
	 * GL converts integer attributes between signed and unsigned (e.g. RGBA16_UINT data into an ivec3); Metal requires
	 * the format's signedness to match the shader input, so pick the same-sized variant the shader declares.
	 */
	private String matchSignedness(String format, int location) {
		if (!format.endsWith("_UINT") && !format.endsWith("_SINT")) {
			return format;
		}
		java.util.regex.Matcher m = MSL_ATTRIBUTE.matcher(linked.vertexMsl());
		while (m.find()) {
			if (Integer.parseInt(m.group(2)) == location) {
				String type = m.group(1);
				boolean unsigned = type.startsWith("uint") || type.startsWith("ushort") || type.startsWith("uchar");
				boolean signed = !unsigned && (type.startsWith("int") || type.startsWith("short") || type.startsWith("char"));
				String base = format.substring(0, format.length() - 5);
				return unsigned ? base + "_UINT" : signed ? base + "_SINT" : format;
			}
		}
		return format;
	}
}
