package net.irisshaders.iris.metal;

import dev.graft.metal.ShaderTranslator;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an Iris-transformed vertex+fragment GLSL pair into Metal shaders.
 *
 * Iris output is core-profile GLSL with loose uniforms and implicit locations, which Vulkan-style SPIR-V does not allow.
 * Both stages get the same treatment so their interfaces line up:
 *  - every loose (non-opaque) uniform from either stage goes into one std140 block, declared identically in both stages;
 *  - samplers get binding = their index in the union of sampler names;
 *  - varyings get location = their index in the sorted union of varying names; vertex inputs use Iris's attribute slots.
 * Then GLSL -> SPIR-V (shaderc) -> reflect block layout (spvc) -> MSL (Graft's ShaderTranslator).
 */
public final class MetalGlsl {
	/** One member of the uniform block. baseType is a SPVC_BASETYPE_* value. */
	public record Member(int offset, int baseType, int vecSize, int columns, int arraySize, int arrayStride, int matrixStride) {
	}

	public record Linked(
		String vertexMsl,
		String fragmentMsl,
		List<ShaderTranslator.Kind> kinds,
		Map<String, Integer> samplerBindings,
		Map<String, Member> uniforms,
		int blockBinding,
		int blockSize,
		Map<String, Integer> attributeLocations,
		/** Uniform blocks the shader declares itself (e.g. Iris's iris_DynamicTransforms), by block name. */
		Map<String, Integer> blockBindings,
		/** samplerBuffer uniforms (e.g. Sodium's u_SectionTimeInfo), by name. */
		Map<String, Integer> texelBufferBindings,
		/** Pack SSBO index (layout binding in the pack source) -> Metal buffer slot. */
		Map<Integer, Integer> storageBufferBindings,
		/** image uniforms (image load/store), by name -> Metal texture slot. */
		Map<String, Integer> imageBindings,
		/** Compute programs only: MSL and local_size (vertexMsl/fragmentMsl are null). */
		String computeMsl,
		int[] localSize,
		/** Set when a geometry shader is emulated in the vertex function (MetalGeometry). */
		MetalGeometry.Info geometry,
		/** Set for tessellation programs: vertexMsl is then the post-tessellation function (MetalTessellation). */
		MetalTessellation.Info tessellation,
		/** >= 0 when the program samples more than 16 textures: buffer slot of the sampler index table (see shareSamplers). */
		int samplerTableSlot
	) {
	}

	private static final Pattern VERSION = Pattern.compile("^\\s*#version\\s+(\\d+)(.*)$");
	// groups: 1 layout args, 2 qualifiers before "uniform", 3 qualifiers after, 4 type, 5 declarators
	private static final Pattern UNIFORM = Pattern.compile(
		"^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:readonly|writeonly|coherent|restrict|volatile)\\s+)*)uniform\\s+((?:(?:readonly|writeonly|coherent|restrict|volatile|lowp|mediump|highp)\\s+)*)(\\w+)\\s+([^;{]+);\\s*$"
	);
	private static final Pattern INOUT = Pattern.compile(
		"^\\s*(layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|noperspective|smooth|centroid|invariant)\\s+)*)(in|out)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;\\s*$"
	);
	private static final Pattern DECLARATOR = Pattern.compile("^\\s*(\\w+)\\s*(\\[\\s*([^\\]]+)\\s*\\])?\\s*(=.*)?$");
	private static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");
	// ponytail: block declarations must open with "{" on the same line (true for Iris output).
	private static final Pattern BLOCK = Pattern.compile("^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(\\w+)\\s*\\{(.*)$");

	private MetalGlsl() {
	}

	/**
	 * Sodium's per-region values. On GL Iris feeds them with glUniform; on Metal Sodium sends them as push constants
	 * (Graft's Sodium compat), so they get Sodium's own Vulkan push-constant layout.
	 */
	private static final List<String> SODIUM_PUSH_CONSTANTS = List.of("u_RegionOffset", "u_CurrentTime", "u_RegionID");
	private static final String SODIUM_PUSH_BLOCK = "layout(push_constant) uniform SodiumRegion {\n\tvec3 u_RegionOffset;\n\tint u_CurrentTime;\n\tuint u_RegionID;\n};\n";

	// ponytail: buffer blocks must open with "{" on the same line.
	private static final Pattern SSBO = Pattern.compile(
		"^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:readonly|writeonly|coherent|restrict|volatile)\\s+)*)buffer\\s+(\\w+)\\s*\\{(.*)$"
	);
	private static final Pattern BINDING = Pattern.compile("binding\\s*=\\s*(\\d+)");

	private static int packBinding(String layout) {
		Matcher m = layout != null ? BINDING.matcher(layout) : null;
		return m != null && m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static void collectImages(String source, TreeSet<String> into) {
		int depth = 0;
		for (String line : source.split("\n")) {
			Matcher u = depth == 0 ? UNIFORM.matcher(line) : null;
			if (u != null && u.matches() && image(u.group(4))) {
				for (String decl : u.group(5).split(",")) {
					Matcher d = DECLARATOR.matcher(decl);
					if (d.matches()) {
						into.add(d.group(1));
					}
				}
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	private static void collectStorageBuffers(String source, Map<String, Integer> into) {
		int depth = 0;
		for (String line : source.split("\n")) {
			Matcher m = depth == 0 ? SSBO.matcher(line) : null;
			if (m != null && m.matches()) {
				into.putIfAbsent(m.group(3), packBinding(m.group(1)));
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	private static boolean image(String type) {
		return type.matches("[iu]?image\\w*");
	}

	private static boolean texelBuffer(String type) {
		return type.matches("[iu]?samplerBuffer");
	}

	private static boolean opaque(String type) {
		return type.matches("[iu]?(sampler|image|texture)\\w*");
	}

	private static final Pattern BLOCK_MEMBER = Pattern.compile("^(\\t\\w+ )(\\w+)(.*;)$");

	/**
	 * The shared block, with members this stage does not declare renamed out of the way: in GL each stage only sees
	 * its own uniforms, and packs reuse a name as a plain global in the other stage (Bliss: uniform sunVec in one,
	 * "vec3 sunVec = ..." in the other). Names don't affect std140 layout, so both stages still agree.
	 */
	private static String stageBlock(String source, String block) {
		if (block.isEmpty()) {
			return block;
		}
		LinkedHashMap<String, String> own = new LinkedHashMap<>();
		scan(source, own, new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), false);
		StringBuilder out = new StringBuilder();
		for (String line : block.split("\n", -1)) {
			Matcher m = BLOCK_MEMBER.matcher(line);
			out.append(m.matches() && !own.containsKey(m.group(2)) ? m.group(1) + "iris_otherStage_" + m.group(2) + m.group(3) : line);
			out.append('\n');
		}
		return out.substring(0, out.length() - 1);
	}

	/** Loose uniform declarations (in first-seen order) and sampler names found at global scope. */
	private static void scan(String source, LinkedHashMap<String, String> loose, TreeSet<String> samplers, TreeSet<String> varyings, TreeSet<String> blocks, boolean vertex) {
		int depth = 0;
		for (String line : source.split("\n")) {
			if (depth == 0) {
				Matcher blk = BLOCK.matcher(line);
				if (blk.matches()) {
					blocks.add(blk.group(1));
				}
				Matcher u = UNIFORM.matcher(line);
				if (u.matches()) {
					String type = u.group(4);
					for (String decl : u.group(5).split(",")) {
						Matcher d = DECLARATOR.matcher(decl);
						if (!d.matches()) {
							continue;
						}
						if (opaque(type)) {
							samplers.add(d.group(1));
						} else {
							loose.putIfAbsent(d.group(1), type + " " + d.group(1) + (d.group(2) != null ? "[" + d.group(3) + "]" : "") + ";");
						}
					}
				}
				Matcher io = INOUT.matcher(line);
				if (io.matches() && (vertex ? io.group(4).equals("out") : io.group(4).equals("in"))) {
					varyings.add(io.group(6));
				}
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	private static void collectTexelBuffers(String source, TreeSet<String> into) {
		int depth = 0;
		for (String line : source.split("\n")) {
			Matcher u = depth == 0 ? UNIFORM.matcher(line) : null;
			if (u != null && u.matches() && texelBuffer(u.group(4))) {
				for (String decl : u.group(5).split(",")) {
					Matcher d = DECLARATOR.matcher(decl);
					if (d.matches()) {
						into.add(d.group(1));
					}
				}
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	private static int count(String s, char c) {
		int n = 0;
		int comment = s.indexOf("//");
		int end = comment >= 0 ? comment : s.length();
		for (int i = 0; i < end; i++) {
			if (s.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}

	private static Map<String, Integer> storageSlots;
	private static Map<String, Integer> varyingLocations;

	/** Locations each varying occupies: matrix columns x array length (GLSL interface location rules). */
	private static void varyingSlots(String source, boolean vertex, Map<String, Integer> into) {
		Map<String, Integer> structs = structSlots(source);
		int depth = 0;
		for (String line : source.split("\n")) {
			Matcher io = depth == 0 ? INOUT.matcher(line) : null;
			if (io != null && io.matches() && io.group(4).equals(vertex ? "out" : "in")) {
				int columns = typeSlots(io.group(5), structs);
				int length = 1;
				if (io.group(7) != null) {
					length = Math.max(1, intExpression(io.group(7).replaceAll("[\\[\\]\\s]", "")));
				}
				into.merge(io.group(6), columns * length, Math::max);
			}
			depth += count(line, '{') - count(line, '}');
		}
	}

	private static final Pattern THREADGROUP_FLOAT3 = Pattern.compile("(threadgroup\\s+(?:spvUnsafeArray<\\s*)*)(float3|int3|uint3)\\b");

	/**
	 * Shared vec3 arrays as 12-byte packed vectors: Metal's float3 is 16 bytes, so arrays sized for GL's limit overflow
	 * Metal's 32 KB of threadgroup memory (Photon: shared vec3[256][9] = 27 KB in GL, 36 KB as float3). Packed vectors
	 * convert implicitly, so loads, stores and arithmetic keep working.
	 */
	private static String packThreadgroupVec3(String msl) {
		String packed = THREADGROUP_FLOAT3.matcher(msl).replaceAll("$1packed_$2");
		if (packed.equals(msl)) {
			return msl;
		}
		// Whole-row copies out of a packed array (packed and unpacked arrays don't convert): copy element by element.
		return ROW_COPY.matcher(packed).replaceAll("$1spvUnsafeArray<$2, $3> $4; for (int iris_k = 0; iris_k < $3; iris_k++) $4[iris_k] = $5[iris_k];");
	}

	private static final Pattern ROW_COPY = Pattern.compile(
		"(?m)^(\\s*)spvUnsafeArray<((?:float|int|uint)3), (\\d+)> (\\w+) = (\\w+(?:\\[[^\\]]*\\])+);"
	);

	/** Value of a constant integer expression like "16/4" or "2*3+1" (array sizes after preprocessing); 1 if not one. */
	static int intExpression(String e) {
		if (!e.matches("[0-9+\\-*/()]+")) {
			return 1;
		}
		try {
			int[] pos = {0};
			int v = sum(e, pos);
			return pos[0] == e.length() ? v : 1;
		} catch (RuntimeException ex) {
			return 1;
		}
	}

	private static int sum(String e, int[] p) {
		int v = product(e, p);
		while (p[0] < e.length() && (e.charAt(p[0]) == '+' || e.charAt(p[0]) == '-')) {
			v = e.charAt(p[0]++) == '+' ? v + product(e, p) : v - product(e, p);
		}
		return v;
	}

	private static int product(String e, int[] p) {
		int v = atom(e, p);
		while (p[0] < e.length() && (e.charAt(p[0]) == '*' || e.charAt(p[0]) == '/')) {
			v = e.charAt(p[0]++) == '*' ? v * atom(e, p) : v / atom(e, p);
		}
		return v;
	}

	private static int atom(String e, int[] p) {
		if (e.charAt(p[0]) == '(') {
			p[0]++;
			int v = sum(e, p);
			p[0]++;
			return v;
		}
		int start = p[0];
		while (p[0] < e.length() && Character.isDigit(e.charAt(p[0]))) {
			p[0]++;
		}
		return Integer.parseInt(e.substring(start, p[0]));
	}

	private static final Pattern STRUCT_START = Pattern.compile("^\\s*struct\\s+(\\w+)\\s*\\{\\s*$");
	private static final Pattern STRUCT_MEMBER = Pattern.compile("^\\s*(?:(?:lowp|mediump|highp|flat|smooth|noperspective)\\s+)*(\\w+)\\s+([^;]+);\\s*$");

	/** Locations a value of this type takes: matrix columns, struct members summed (Photon passes a struct varying). */
	private static int typeSlots(String type, Map<String, Integer> structs) {
		Matcher mat = Pattern.compile("d?mat(\\d)(?:x\\d)?").matcher(type);
		return mat.matches() ? Integer.parseInt(mat.group(1)) : structs.getOrDefault(type, 1);
	}

	/** Location counts of the structs defined at global scope (one member per line, as Iris's output prints them). */
	private static Map<String, Integer> structSlots(String source) {
		Map<String, Integer> structs = new HashMap<>();
		String current = null;
		int slots = 0;
		for (String line : source.split("\n")) {
			Matcher start = current == null ? STRUCT_START.matcher(line) : null;
			if (start != null && start.matches()) {
				current = start.group(1);
				slots = 0;
			} else if (current != null && line.trim().startsWith("}")) {
				structs.put(current, slots);
				current = null;
			} else if (current != null) {
				Matcher m = STRUCT_MEMBER.matcher(line);
				if (m.matches()) {
					for (String decl : m.group(2).split(",")) {
						Matcher arr = Pattern.compile("\\[([^\\]]+)\\]").matcher(decl);
						slots += typeSlots(m.group(1), structs) * (arr.find() ? Math.max(1, intExpression(arr.group(1).replaceAll("\\s", ""))) : 1);
					}
				}
			}
		}
		return structs;
	}

	private static String patch(String source, boolean vertex, String block, Map<String, Integer> samplerBindings, Map<String, Integer> blockBindings, List<String> varyings, Map<String, Integer> attributes) {
		block = stageBlock(source, block);
		StringBuilder out = new StringBuilder();
		boolean blockEmitted = block.isEmpty();
		boolean pushEmitted = false;
		int depth = 0;
		int nextOutput = 0;
		int nextAttribute = 16;
		java.util.Set<String> declared = new java.util.HashSet<>();
		int skip = 0;
		for (String line : source.split("\n", -1)) {
			if (skip > 0) {
				skip += count(line, '{') - count(line, '}');
				continue;
			}
			if (depth == 0) {
				Matcher v = VERSION.matcher(line);
				if (v.matches()) {
					// Explicit bindings/locations need GLSL 4.20+; Iris output is already core-profile, so 450 core is safe.
					out.append("#version ").append(Math.max(450, Integer.parseInt(v.group(1)))).append(" core\n");
					out.append("#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n");
					continue;
				}
				Matcher ssbo = SSBO.matcher(line);
				if (ssbo.matches() && storageSlots != null && !declared.add("buffer " + ssbo.group(3))) {
					skip = count(line, '{') - count(line, '}');
					continue;
				}
				if (ssbo.matches() && storageSlots != null) {
					String layout = ssbo.group(1) != null && ssbo.group(1).contains("std140") ? "std140" : "std430";
					out.append("layout(").append(layout).append(", binding = ").append(storageSlots.get(ssbo.group(3))).append(") ")
						.append(ssbo.group(2)).append("buffer ").append(ssbo.group(3)).append(" {").append(ssbo.group(4)).append('\n');
					depth += count(line, '{') - count(line, '}');
					continue;
				}
				Matcher blk = BLOCK.matcher(line);
				if (blk.matches() && !declared.add("uniform " + blk.group(1))) {
					skip = count(line, '{') - count(line, '}');
					continue;
				}
				if (blk.matches()) {
					out.append("layout(std140, binding = ").append(blockBindings.get(blk.group(1))).append(") uniform ").append(blk.group(1)).append(" {")
						.append(blk.group(2)).append('\n');
					depth += count(line, '{') - count(line, '}');
					continue;
				}
				Matcher u = UNIFORM.matcher(line);
				if (u.matches()) {
					String type = u.group(4);
					for (String decl : u.group(5).split(",")) {
						Matcher d = DECLARATOR.matcher(decl);
						if (!d.matches()) {
							continue;
						}
						if (opaque(type) && !declared.add(d.group(1))) {
							continue;
						}
						if (opaque(type)) {
							Integer binding = samplerBindings.get(d.group(1));
							String args = u.group(1) == null ? "" : BINDING.matcher(u.group(1)).replaceAll("").replaceAll("^\\s*,|,\\s*$", "").replaceAll(",\\s*,", ",").trim();
							out.append("layout(").append(args.isEmpty() ? "" : args + ", ").append("binding = ").append(binding).append(") ").append(u.group(2)).append(u.group(3)).append("uniform ").append(type).append(' ').append(d.group(1))
								.append(d.group(2) != null ? d.group(2) : "").append(";\n");
						} else if (SODIUM_PUSH_CONSTANTS.contains(d.group(1))) {
							if (!pushEmitted) {
								out.append(SODIUM_PUSH_BLOCK);
								pushEmitted = true;
							}
						} else if (!blockEmitted) {
							out.append(block);
							blockEmitted = true;
						}
					}
					depth += count(line, '{') - count(line, '}');
					continue;
				}
				Matcher io = INOUT.matcher(line);
				if (io.matches()) {
					String layout = io.group(2);
					boolean in = io.group(4).equals("in");
					String name = io.group(6);
					int location;
					Matcher existing = layout != null ? LOCATION.matcher(layout) : null;
					boolean varying = vertex != in;
					if (varying) {
						// Varyings are matched by name across stages; mixing explicit and assigned locations would collide.
						location = varyingLocations != null && varyingLocations.containsKey(name) ? varyingLocations.get(name) : varyings.indexOf(name);
					} else if (existing != null && existing.find()) {
						location = Integer.parseInt(existing.group(1));
					} else if (vertex && in) {
						location = attributes.computeIfAbsent(name, n -> -1);
						if (location < 0) {
							location = nextAttribute++;
							attributes.put(name, location);
						}
					} else if (!vertex && !in) {
						location = nextOutput++;
					} else {
						location = varyingLocations != null && varyingLocations.containsKey(name) ? varyingLocations.get(name) : varyings.indexOf(name);
					}
					out.append("layout(location = ").append(location).append(") ").append(io.group(3)).append(io.group(4)).append(' ').append(io.group(5)).append(' ')
						.append(name).append(io.group(7) != null ? io.group(7) : "").append(";\n");
					continue;
				}
			}
			out.append(line).append('\n');
			depth += count(line, '{') - count(line, '}');
		}
		return out.toString();
	}

	/** Compute program: same uniform/sampler/image/SSBO treatment as graphics programs. */
	public static Linked linkCompute(String name, String computeSource) {
		return link(name, null, computeSource, Map.of(), true, null);
	}

	public static Linked link(String name, String vertexSource, String fragmentSource, Map<String, Integer> attributeLocations) {
		return link(name, vertexSource, null, null, null, fragmentSource, attributeLocations);
	}

	public static Linked link(String name, String vertexSource, String geometrySource, String fragmentSource, Map<String, Integer> attributeLocations) {
		return link(name, vertexSource, null, null, geometrySource, fragmentSource, attributeLocations);
	}

	/** geometrySource may be null; a geometry shader is folded into the vertex function (see MetalGeometry). */
	/** Optional stages may be null. Geometry is folded into the vertex function; tessellation gets a compute pre-pass. */
	public static Linked link(String name, String vertexSource, String controlSource, String evaluationSource, String geometrySource, String fragmentSource, Map<String, Integer> attributeLocations) {
		if (evaluationSource != null) {
			if (controlSource == null || geometrySource != null) {
				throw new ShaderCompileException(name, "Graft Shaders: tessellation needs a control shader and no geometry shader on Metal");
			}
			Map<String, Integer> attributes = new HashMap<>(attributeLocations);
			// Minecraft draws triangle lists, so patches are 3 vertices (GL default GL_PATCH_VERTICES).
			MetalTessellation.Combined t = MetalTessellation.combine(name, normalize(vertexSource), normalize(controlSource), normalize(evaluationSource), 3, attributes);
			return link(name, t.evaluation(), fragmentSource, attributes, false, null, t.prepass(), t.info());
		}
		if (geometrySource == null) {
			return link(name, vertexSource, fragmentSource, attributeLocations, false, null);
		}
		Map<String, Integer> attributes = new HashMap<>(attributeLocations);
		MetalGeometry.Combined combined = MetalGeometry.combine(name, normalize(vertexSource), normalize(geometrySource), attributes);
		return link(name, combined.source(), fragmentSource, attributes, false, combined.info());
	}

	private static final Pattern CXX_KEYWORDS = Pattern.compile(
		"\\b(new|delete|this|operator|template|typename|namespace|private|public|protected|virtual|friend|explicit|try|catch|throw|"
			+ "typeid|decltype|nullptr|constexpr|alignas|alignof|auto|register|mutable|thread_local|static_assert|"
			+ "device|thread|threadgroup|constant|kernel|vertex|fragment|uchar|ushort|ulong|size_t|ptrdiff_t|"
			// Vulkan GLSL (what shaderc compiles) reserves these type names; GL packs use them as variables (Bliss: "sampler2D sampler").
			+ "sampler|samplerShadow|[iu]?texture(?:1D|2D|3D|Cube|2DRect|1DArray|2DArray|CubeArray|Buffer|2DMS|2DMSArray)|[iu]?subpassInput(?:MS)?)\\b(?!\\s*\\()"
	);

	/** Metal standard library names (types/functions under "using namespace metal") that GLSL code may use, calls included (Bliss: "float bias()"). */
	private static final Pattern METAL_LIBRARY_NAMES = Pattern.compile(
		"\\b(bias|level|gradient2d|gradient3d|gradientcube|min_lod_clamp|filter|address|coord|access|mip_filter|mag_filter|min_filter|compare_func|border_color|"
			// Metal stdlib functions that are not GLSL built-ins (Photon: "float length_squared(vec3)"); in GLSL these
			// names can only be the pack's own, so renaming never changes meaning.
			+ "saturate|length_squared|distance_squared|rsqrt|max3|min3|median3|fmax|fmin|fmax3|fmin3|fmedian3|fdim|fmod|rint|powr|"
			+ "cospi|sinpi|tanpi|acospi|asinpi|atanpi|atan2pi|divide|select|popcount|clz|ctz|rotate|absdiff|abs_diff|mad_sat|mul_hi|"
			+ "hadd|rhadd|addsat|subsat|extract_bits|insert_bits|reverse_bits|copysign|ilogb|nextafter|fast_normalize|precise_normalize|"
			+ "dfdx|dfdy|as_type|discard_fragment|sincos|exp10|log10)\\b"
	);

	private static final Pattern FUNCTION_DEFINITION = Pattern.compile(
		"(?m)^[ \\t]*(?:(?:highp|mediump|lowp|precise|const)\\s+)*(\\w+)\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:\\{|$)"
	);
	private static final java.util.Set<String> NOT_A_TYPE = java.util.Set.of(
		"return", "else", "case", "if", "for", "while", "switch", "do", "layout", "uniform", "in", "out", "inout", "struct", "define", "elif", "ifdef", "ifndef", "undef"
	);

	private static final Pattern BUILTIN_REDEFINITION = Pattern.compile(
		"(?m)^\\s*(?:float|vec[234]|int|bool|mat[234])\\s+(tanh|sinh|cosh|asinh|acosh|atanh|fma|round|roundEven|trunc|isnan|isinf|inverse|transpose|determinant)\\s*\\([^;{]*\\)\\s*\\{?\\s*$"
	);

	private static final Pattern TYPE_ARRAY_INOUT = Pattern.compile(
		"(?m)^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|noperspective|smooth|centroid|invariant|patch)\\s+)*(?:in|out|varying)\\s+(?:(?:lowp|mediump|highp)\\s+)?)(\\w+)\\s*(\\[[^\\]]*\\])\\s*(\\w+)\\s*;"
	);

	private static final Pattern MULTI_INOUT = Pattern.compile(
		"(?m)^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|noperspective|smooth|centroid|invariant|patch)\\s+)*(?:in|out|varying|attribute)\\s+(?:(?:lowp|mediump|highp)\\s+)?\\w+)\\s+(\\w+(?:\\s*\\[[^\\]]*\\])?(?:\\s*,\\s*\\w+(?:\\s*\\[[^\\]]*\\])?)+)\\s*;[ \\t]*$"
	);

	private static String normalize(String source) {
		// The parser is line based; Iris output sometimes continues after a block's "};" on the same line.
		// GLSL allows identifiers that are C++/MSL keywords (BSL has "bool new"); SPIRV-Cross keeps some of them as is.
		source = CXX_KEYWORDS.matcher(source).replaceAll("iris_kw_$1");
		source = METAL_LIBRARY_NAMES.matcher(source).replaceAll("iris_kw_$1");
		// Every function the pack defines gets a prefix: MSL puts all of Metal's library in scope ("using namespace metal"),
		// so any pack function sharing a name with one (bias, length_squared, disjunction, ...) becomes ambiguous.
		java.util.Set<String> functions = new java.util.TreeSet<>();
		Matcher fn = FUNCTION_DEFINITION.matcher(source);
		while (fn.find()) {
			String type = fn.group(1);
			String n = fn.group(2);
			if (!NOT_A_TYPE.contains(type) && !n.equals("main") && !n.startsWith("iris_") && !NOT_A_TYPE.contains(n)) {
				functions.add(n);
			}
		}
		if (!functions.isEmpty()) {
			source = Pattern.compile("\\b(" + String.join("|", functions) + ")(?=\\s*\\()").matcher(source).replaceAll("iris_fn_$1");
		}
		// GL drivers let packs define their own copy of a built-in (Bliss: "float tanh(float x)"); shaderc rejects the
		// redefinition. Rename the pack's function and its calls, which matches GL, where the pack's version wins.
		Matcher redefined = BUILTIN_REDEFINITION.matcher(source);
		while (redefined.find()) {
			source = source.replaceAll("\\b" + redefined.group(1) + "(?=\\s*\\()", "iris_pack_" + redefined.group(1));
			redefined = BUILTIN_REDEFINITION.matcher(source);
		}
		// Line comments go first: declaration matching is anchored at end of line.
		String text = source.replace("\r\n", "\n").replace('\r', '\n').replaceAll("//[^\n]*", "").replaceAll("\\};[ \\t]*(?=\\S)", "};\n");
		// "flat out vec4[N] name;" -> "flat out vec4 name[N];" (Photon), the form the line parser understands.
		text = TYPE_ARRAY_INOUT.matcher(text).replaceAll("$1$2 $4$3;");
		// "flat out vec3 a, b;" -> one declaration per line (the parser reads one name per in/out line).
		Matcher m = MULTI_INOUT.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			StringBuilder split = new StringBuilder();
			for (String name : m.group(2).split(",")) {
				split.append(m.group(1)).append(' ').append(name.trim()).append(";\n");
			}
			m.appendReplacement(out, Matcher.quoteReplacement(split.toString()));
		}
		return m.appendTail(out).toString();
	}

	private static Linked link(String name, String vertexSource, String fragmentSource, Map<String, Integer> attributeLocations, boolean compute, MetalGeometry.Info geometry) {
		return link(name, vertexSource, fragmentSource, attributeLocations, compute, geometry, null, null);
	}

	/** prepass: tessellation pre-pass compute source, linked with the same uniforms/bindings as the draw. */
	private static Linked link(String name, String vertexSource, String fragmentSource, Map<String, Integer> attributeLocations, boolean compute,
							   MetalGeometry.Info geometry, String prepass, MetalTessellation.Info tessellation) {
		if (vertexSource == null) {
			vertexSource = "";
		}
		vertexSource = normalize(vertexSource);
		fragmentSource = normalize(fragmentSource);
		if (prepass != null) {
			prepass = normalize(prepass);
		}
		LinkedHashMap<String, String> loose = new LinkedHashMap<>();
		TreeSet<String> samplers = new TreeSet<>();
		TreeSet<String> vertexOut = new TreeSet<>();
		TreeSet<String> fragmentIn = new TreeSet<>();
		TreeSet<String> blocks = new TreeSet<>();
		scan(vertexSource, loose, samplers, vertexOut, blocks, true);
		scan(fragmentSource, loose, samplers, fragmentIn, blocks, false);
		if (prepass != null) {
			scan(prepass, loose, samplers, new TreeSet<>(), blocks, false);
		}
		TreeSet<String> allVaryings = new TreeSet<>(vertexOut);
		allVaryings.addAll(fragmentIn);
		List<String> varyings = new ArrayList<>(allVaryings);
		// Arrays and matrices take several consecutive locations; number varyings by their slot count.
		Map<String, Integer> varyingSizes = new HashMap<>();
		varyingSlots(vertexSource, true, varyingSizes);
		varyingSlots(fragmentSource, false, varyingSizes);
		Map<String, Integer> locations = new HashMap<>();
		int nextLocation = 0;
		for (String v : varyings) {
			locations.put(v, nextLocation);
			nextLocation += varyingSizes.getOrDefault(v, 1);
		}
		varyingLocations = locations;

		TreeSet<String> texelBufferNames = new TreeSet<>();
		collectTexelBuffers(vertexSource, texelBufferNames);
		collectTexelBuffers(fragmentSource, texelBufferNames);
		if (prepass != null) {
			collectTexelBuffers(prepass, texelBufferNames);
		}
		TreeSet<String> imageNames = new TreeSet<>();
		collectImages(vertexSource, imageNames);
		collectImages(fragmentSource, imageNames);
		if (prepass != null) {
			collectImages(prepass, imageNames);
		}
		texelBufferNames.removeAll(imageNames);
		SODIUM_PUSH_CONSTANTS.forEach(loose::remove);
		Map<String, Integer> samplerBindings = new HashMap<>();
		Map<String, Integer> texelBufferBindings = new HashMap<>();
		Map<String, Integer> allOpaque = new HashMap<>();
		List<ShaderTranslator.Kind> kinds = new ArrayList<>();
		Map<String, Integer> imageBindings = new HashMap<>();
		for (String sampler : samplers) {
			boolean texel = texelBufferNames.contains(sampler);
			boolean img = imageNames.contains(sampler);
			(img ? imageBindings : texel ? texelBufferBindings : samplerBindings).put(sampler, kinds.size());
			allOpaque.put(sampler, kinds.size());
			kinds.add(img ? ShaderTranslator.Kind.STORAGE_IMAGE : texel ? ShaderTranslator.Kind.TEXEL_BUFFER : ShaderTranslator.Kind.SAMPLED_IMAGE);
		}
		Map<String, Integer> blockBindings = new HashMap<>();
		for (String declared : blocks) {
			blockBindings.put(declared, kinds.size());
			kinds.add(ShaderTranslator.Kind.UNIFORM_BUFFER);
		}
		Map<String, Integer> ssboNames = new java.util.TreeMap<>();
		collectStorageBuffers(vertexSource, ssboNames);
		collectStorageBuffers(fragmentSource, ssboNames);
		if (prepass != null) {
			collectStorageBuffers(prepass, ssboNames);
		}
		Map<String, Integer> slots = new HashMap<>();
		Map<Integer, Integer> storageBufferBindings = new HashMap<>();
		for (Map.Entry<String, Integer> ssbo : ssboNames.entrySet()) {
			slots.put(ssbo.getKey(), kinds.size());
			storageBufferBindings.put(ssbo.getValue(), kinds.size());
			kinds.add(ShaderTranslator.Kind.STORAGE_BUFFER);
		}
		storageSlots = slots;
		int blockBinding = -1;
		String block = "";
		if (!loose.isEmpty()) {
			blockBinding = kinds.size();
			kinds.add(ShaderTranslator.Kind.UNIFORM_BUFFER);
			StringBuilder b = new StringBuilder("layout(std140, binding = ").append(blockBinding).append(") uniform GraftShadersUniforms {\n");
			loose.values().forEach(decl -> b.append('\t').append(decl).append('\n'));
			block = b.append("};\n").toString();
		}

		// Metal has 16 sampler slots per stage but 128 texture slots: past 16, textures pick samplers from a shared array.
		int samplerTable = -1;
		if (samplerBindings.values().stream().anyMatch(i -> ShaderTranslator.samplerSlot(kinds, i) >= 16)) {
			samplerTable = kinds.size();
			kinds.add(ShaderTranslator.Kind.UNIFORM_BUFFER);
		}
		final int table = samplerTable;
		java.util.function.UnaryOperator<String> share = msl -> table >= 0 ? shareSamplers(msl, ShaderTranslator.bufferSlot(kinds, table)) : msl;
		Map<String, Integer> attributes = new HashMap<>(attributeLocations);
		if (compute) {
			String cs = patch(fragmentSource, false, block, allOpaque, blockBindings, varyings, attributes);
			ByteBuffer csSpirv = compile(name + ".csh", cs, Shaderc.shaderc_glsl_compute_shader);
			try {
				Map<String, Member> uniforms = new HashMap<>();
				int blockSize = blockBinding >= 0 ? reflectBlock(name, csSpirv, uniforms) : 0;
				ShaderTranslator.Compute translated = ShaderTranslator.toMslCompute(name + ".csh", csSpirv, kinds);
				return new Linked(
					null, null, kinds, samplerBindings, uniforms, blockBinding, blockSize, attributes, blockBindings, texelBufferBindings,
					storageBufferBindings, imageBindings, share.apply(packThreadgroupVec3(translated.msl())), new int[]{translated.localX(), translated.localY(), translated.localZ()}, null, null, samplerTable
				);
			} finally {
				MemoryUtil.memFree(csSpirv);
			}
		}
		String vs = patch(vertexSource, true, block, allOpaque, blockBindings, varyings, attributes);
		String fs = patch(fragmentSource, false, block, allOpaque, blockBindings, varyings, attributes);
		// Debug: touch /tmp/graftshaders-dump to write every linked program's patched GLSL to /tmp/graftshaders/.
		if (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/graftshaders-dump"))) {
			try {
				java.nio.file.Path dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of("/tmp/graftshaders"));
				java.nio.file.Files.writeString(dir.resolve(name + ".vsh"), vs + "\n// attributes: " + attributes);
				java.nio.file.Files.writeString(dir.resolve(name + ".fsh"), fs);
			} catch (java.io.IOException ignored) {
			}
		}
		ByteBuffer vsSpirv = compile(name + (tessellation != null ? ".tes" : ".vsh"), vs,
			tessellation != null ? Shaderc.shaderc_glsl_tess_evaluation_shader : Shaderc.shaderc_glsl_vertex_shader);
		ByteBuffer fsSpirv = compile(name + ".fsh", fs, Shaderc.shaderc_glsl_fragment_shader);
		try {
			Map<String, Member> uniforms = new HashMap<>();
			int blockSize = 0;
			if (blockBinding >= 0) {
				blockSize = Math.max(reflectBlock(name, vsSpirv, uniforms), reflectBlock(name, fsSpirv, uniforms));
			}
			if (prepass != null) {
				ByteBuffer pre = compile(name + ".tcs", patch(prepass, false, block, allOpaque, blockBindings, varyings, attributes), Shaderc.shaderc_glsl_compute_shader);
				try {
					if (blockBinding >= 0) {
						blockSize = Math.max(blockSize, reflectBlock(name, pre, uniforms));
					}
					tessellation = tessellation.withPrepass(share.apply(ShaderTranslator.toMslCompute(name + ".tcs", pre, kinds).msl()));
				} finally {
					MemoryUtil.memFree(pre);
				}
			}
			return new Linked(
				share.apply(tessellation != null ? ShaderTranslator.toMslTessEval(name + ".tes", vsSpirv, kinds) : ShaderTranslator.toMsl(name + ".vsh", vsSpirv, true, kinds)),
				share.apply(ShaderTranslator.toMsl(name + ".fsh", fsSpirv, false, kinds)),
				kinds, samplerBindings, uniforms, blockBinding, blockSize, attributes, blockBindings, texelBufferBindings, storageBufferBindings, imageBindings,
				null, null, geometry, tessellation, samplerTable
			);
		} finally {
			MemoryUtil.memFree(vsSpirv);
			MemoryUtil.memFree(fsSpirv);
		}
	}

	private static final Pattern MSL_SAMPLER_PARAM = Pattern.compile(",\\s*sampler\\s+(\\w+)\\s*\\[\\[sampler\\((\\d+)\\)\\]\\]");

	/**
	 * Rewrites an MSL entry point so its per-texture sampler parameters come from one array of 16 samplers, indexed
	 * through a table (sampler binding -> array slot) the draw fills with deduplicated sampler states.
	 */
	static String shareSamplers(String msl, int tableSlot) {
		int entry = msl.indexOf(" main0(");
		int open = msl.indexOf("\n{\n", entry);
		if (entry < 0 || open < 0) {
			return msl;
		}
		Matcher m = MSL_SAMPLER_PARAM.matcher(msl.substring(entry, open));
		StringBuilder locals = new StringBuilder();
		while (m.find()) {
			locals.append("    sampler ").append(m.group(1)).append(" = iris_samplers[iris_samplerSlots[").append(m.group(2)).append("]];\n");
		}
		if (locals.isEmpty()) {
			return msl;
		}
		String signature = m.replaceAll("");
		int close = signature.lastIndexOf(')');
		signature = signature.substring(0, close) + ", array<sampler, 16> iris_samplers [[sampler(0)]], constant uint* iris_samplerSlots [[buffer(" + tableSlot + ")]]"
			+ signature.substring(close);
		return msl.substring(0, entry) + signature + "\n{\n" + locals + msl.substring(open + 3);
	}

	private static ByteBuffer compile(String name, String source, int kind) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			// Catch-alls for declarations the patcher did not rewrite (e.g. uniform blocks the pack declared itself).
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			// Native heap copies: pack sources can exceed LWJGL's 64 KB MemoryStack.
			ByteBuffer text = MemoryUtil.memUTF8(source, false);
			ByteBuffer file = MemoryUtil.memUTF8(name);
			ByteBuffer entry = MemoryUtil.memUTF8("main");
			long result;
			try {
				result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, file, entry, options);
			} finally {
				MemoryUtil.memFree(text);
				MemoryUtil.memFree(file);
				MemoryUtil.memFree(entry);
			}
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
					throw new ShaderCompileException(name, Shaderc.shaderc_result_get_error_message(result) + "\n--- patched source ---\n" + source);
				}
				ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
				copy.put(spirv).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	/** Adds GraftShadersUniforms members found in this stage; returns the declared block size (0 if absent). */
	private static int reflectBlock(String name, ByteBuffer spirv, Map<String, Member> into) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer ptr = stack.callocPointer(1);
			PointerBuffer count = stack.callocPointer(1);
			IntBuffer value = stack.callocInt(1);
			Spvc.spvc_context_create(ptr);
			long context = ptr.get(0);
			try {
				Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, ptr);
				long ir = ptr.get(0);
				Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr);
				long compiler = ptr.get(0);
				Spvc.spvc_compiler_create_shader_resources(compiler, ptr);
				long resources = ptr.get(0);
				Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, ptr, count);
				SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(ptr.get(0), (int)count.get(0));
				for (SpvcReflectedResource r : list) {
					long blockType = Spvc.spvc_compiler_get_type_handle(compiler, r.base_type_id());
					String typeName = Spvc.spvc_compiler_get_name(compiler, r.base_type_id());
					if (!"GraftShadersUniforms".equals(typeName)) {
						continue;
					}
					int members = Spvc.spvc_type_get_num_member_types(blockType);
					for (int i = 0; i < members; i++) {
						String member = Spvc.spvc_compiler_get_member_name(compiler, r.base_type_id(), i);
						long memberType = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_member_type(blockType, i));
						Spvc.spvc_compiler_type_struct_member_offset(compiler, blockType, i, value);
						int offset = value.get(0);
						int arraySize = Spvc.spvc_type_get_num_array_dimensions(memberType) > 0 ? Spvc.spvc_type_get_array_dimension(memberType, 0) : 0;
						int arrayStride = 0;
						if (arraySize > 0) {
							Spvc.spvc_compiler_type_struct_member_array_stride(compiler, blockType, i, value);
							arrayStride = value.get(0);
						}
						int columns = Spvc.spvc_type_get_columns(memberType);
						int matrixStride = 0;
						if (columns > 1) {
							Spvc.spvc_compiler_type_struct_member_matrix_stride(compiler, blockType, i, value);
							matrixStride = value.get(0);
						}
						if (!member.startsWith("iris_otherStage_")) into.put(member, new Member(offset, Spvc.spvc_type_get_basetype(memberType), Spvc.spvc_type_get_vector_size(memberType), columns, arraySize, arrayStride, matrixStride));
					}
					PointerBuffer size = stack.callocPointer(1);
					Spvc.spvc_compiler_get_declared_struct_size(compiler, blockType, size);
					return (int)size.get(0);
				}
				return 0;
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
	}
}
