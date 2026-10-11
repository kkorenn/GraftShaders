package net.irisshaders.iris.metal;

import net.irisshaders.iris.gl.shader.ShaderCompileException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geometry shaders on Metal (which has no geometry stage), emulated inside one vertex function.
 *
 * Every input primitive gets a fixed number of output vertex slots (max_vertices worth of output primitives, as a
 * triangle/line/point list). The vertex invocation for a slot fetches the primitive's input vertices from the raw vertex
 * and index buffers, runs the pack's vertex shader on each, runs the pack's geometry shader, and keeps only the one
 * emitted vertex that lands in its slot (triangle strips are unrolled into lists, winding preserved). Slots the geometry
 * shader never filled are placed outside the clip volume, so they produce nothing.
 *
 * Cost: per output vertex, the vertex shader runs once per input vertex and the geometry shader runs once.
 * ponytail: side effects in the geometry shader (imageStore, SSBO writes) run once per output vertex instead of once per
 * primitive. Idempotent writes (voxelization) are fine; atomic counters over-count. Needs a compute pre-pass if a pack
 * depends on exact counts.
 */
public final class MetalGeometry {
	/** Pack SSBO indices reserved for the raw vertex and index buffers (outside Iris's 0-15 range). */
	static final int VERTEX_SSBO = 90;
	static final int INDEX_SSBO = 91;
	static final String PARAMS_BLOCK = "IrisGeometry";

	/**
	 * inVerts: vertices per input primitive; outPrimitive: MTLPrimitiveType of the draw; slotsPerPrim: vertices drawn per
	 * input primitive; attributeSlots: entries in the attribute table (indexed by attribute location).
	 */
	public record Info(int inVerts, long outPrimitive, int slotsPerPrim, int attributeSlots) {
	}

	record Combined(String source, Info info) {
	}

	record Var(String type, String name, String array) {
	}

	/** Pack vertex shader folded into another stage: inputs fetched by the caller, outputs left in iris_vs_ globals. */
	record VertexPart(String body, List<Var> inputs, Map<String, Var> outputs, Set<String> defines) {
	}

	static final Pattern VERSION = Pattern.compile("^\\s*#version\\b.*$");
	static final Pattern EXTENSION = Pattern.compile("^\\s*#extension\\b.*$");
	private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+(\\w+).*$");
	static final Pattern LAYOUT_IN = Pattern.compile("^\\s*layout\\s*\\(([^)]*)\\)\\s*in\\s*;\\s*$");
	static final Pattern LAYOUT_OUT = Pattern.compile("^\\s*layout\\s*\\(([^)]*)\\)\\s*out\\s*;\\s*$");
	private static final Pattern MAX_VERTICES = Pattern.compile("max_vertices\\s*=\\s*(\\d+)");
	private static final Pattern INVOCATIONS = Pattern.compile("invocations\\s*=\\s*(\\d+)");
	static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");
	static final String QUALIFIERS = "(?:(?:flat|noperspective|smooth|centroid|invariant|lowp|mediump|highp)\\s+)*";
	static final Pattern INOUT = Pattern.compile(
		"^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?" + QUALIFIERS + "(in|out|attribute|varying)\\s+" + QUALIFIERS + "(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;\\s*$"
	);
	static final Pattern INOUT_BLOCK = Pattern.compile("^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(in|out)\\s+\\w+\\s*\\{.*$");
	private static final Pattern FUNCTION = Pattern.compile(
		"^\\s*(?:(?:highp|mediump|lowp|precise)\\s+)*\\w+(?:\\s*\\[[^\\]]*\\])?\\s+(\\w+)\\s*\\([^=]*$"
	);
	private static final Pattern STRUCT = Pattern.compile("^\\s*struct\\s+(\\w+).*$");
	private static final Pattern GLOBAL = Pattern.compile(
		"^\\s*(?:(?:const|highp|mediump|lowp|invariant|precise)\\s+)*(\\w+)(?:\\s*\\[[^\\]]*\\])?\\s+([A-Za-z_].*)$"
	);
	private static final Set<String> NOT_TYPES = Set.of("return", "else", "uniform", "layout", "precision", "buffer", "struct", "in", "out", "if", "for", "while", "do", "switch", "case");

	private MetalGeometry() {
	}

	/** Builds the combined vertex shader. attributes receives the vertex shader's input locations (by name). */
	static Combined combine(String name, String vertex, String geometry, Map<String, Integer> attributes) {
		Set<String> extensions = new LinkedHashSet<>();

		// ---- geometry shader: layout, inputs, outputs
		String inLayout = "";
		String outLayout = "";
		List<Var> gsInputs = new ArrayList<>();
		List<Var> gsOutputs = new ArrayList<>();
		StringBuilder gs = new StringBuilder();
		int depth = 0;
		for (String line : geometry.split("\n", -1)) {
			if (depth == 0) {
				if (VERSION.matcher(line).matches()) {
					continue;
				}
				if (EXTENSION.matcher(line).matches()) {
					extensions.add(line.trim());
					continue;
				}
				Matcher m = LAYOUT_IN.matcher(line);
				if (m.matches()) {
					inLayout += m.group(1) + ",";
					continue;
				}
				m = LAYOUT_OUT.matcher(line);
				if (m.matches()) {
					outLayout += m.group(1) + ",";
					continue;
				}
				if (INOUT_BLOCK.matcher(line).matches()) {
					throw new ShaderCompileException(name, "Graft Shaders: geometry shader interface blocks are not supported on Metal");
				}
				m = INOUT.matcher(line);
				if (m.matches() && m.group(2).equals("in")) {
					gsInputs.add(new Var(m.group(3), m.group(4), ""));
					gs.append(m.group(3)).append(' ').append(m.group(4)).append("[IRIS_IN];\n");
					continue;
				}
				if (m.matches() && m.group(2).equals("out")) {
					gsOutputs.add(new Var(m.group(3), m.group(4), m.group(5) != null ? m.group(5) : ""));
				}
			}
			gs.append(line).append('\n');
			depth += count(line, '{') - count(line, '}');
		}
		if (geometry.contains("EmitStreamVertex")) {
			throw new ShaderCompileException(name, "Graft Shaders: geometry shader streams are not supported on Metal");
		}
		int inVerts;
		if (inLayout.contains("triangles_adjacency") || inLayout.contains("lines_adjacency")) {
			throw new ShaderCompileException(name, "Graft Shaders: adjacency primitives are not supported on Metal");
		} else if (inLayout.contains("triangles")) {
			inVerts = 3;
		} else if (inLayout.contains("lines")) {
			inVerts = 2;
		} else if (inLayout.contains("points")) {
			inVerts = 1;
		} else {
			throw new ShaderCompileException(name, "Graft Shaders: geometry shader has no input primitive layout");
		}
		Matcher maxV = MAX_VERTICES.matcher(outLayout);
		if (!maxV.find()) {
			throw new ShaderCompileException(name, "Graft Shaders: geometry shader has no max_vertices");
		}
		int maxVertices = Integer.parseInt(maxV.group(1));
		Matcher inv = INVOCATIONS.matcher(inLayout);
		int invocations = inv.find() ? Math.max(1, Integer.parseInt(inv.group(1))) : 1;
		int primVerts;
		long outPrimitive;
		if (outLayout.contains("triangle_strip")) {
			primVerts = 3;
			outPrimitive = dev.graft.metal.Mtl.PRIM_TRIANGLE;
		} else if (outLayout.contains("line_strip")) {
			primVerts = 2;
			outPrimitive = dev.graft.metal.Mtl.PRIM_LINE;
		} else {
			primVerts = 1;
			outPrimitive = dev.graft.metal.Mtl.PRIM_POINT;
		}
		// A strip of n vertices holds n - (primVerts - 1) primitives; at least one slot so side-effect-only shaders run.
		int primsPerInvocation = Math.max(1, maxVertices - (primVerts - 1));
		int slotsPerInvocation = primsPerInvocation * primVerts;

		String gsBody = gs.toString()
			.replaceAll("\\.\\s*gl_Position\\b", ".iris_Position")
			.replaceAll("\\.\\s*gl_PointSize\\b", ".iris_PointSize")
			.replaceAll("\\bgl_in\\b", "iris_gl_in")
			.replaceAll("\\bgl_PrimitiveIDIn\\b", "iris_PrimitiveIDIn")
			.replaceAll("\\bgl_InvocationID\\b", "iris_InvocationID")
			.replaceAll("\\bgl_PrimitiveID\\b", "iris_PrimitiveID")
			.replaceAll("\\bgl_Layer\\b", "iris_Layer")
			.replaceAll("\\bgl_ViewportIndex\\b", "iris_ViewportIndex")
			.replaceAll("\\bEmitVertex\\s*\\(\\s*\\)", "iris_emit()")
			.replaceAll("\\bEndPrimitive\\s*\\(\\s*\\)", "iris_end()")
			.replaceAll("\\bmain\\s*\\(", "iris_gs_main(");
		boolean pointSize = geometry.contains("gl_PointSize");

		VertexPart vp = vertexPart(vertex, attributes, extensions);
		String vsBody = vp.body();
		List<Var> vsInputs = vp.inputs();
		Map<String, Var> vsOutputs = vp.outputs();
		Set<String> defines = vp.defines();
		int attributeSlots = 1 + attributes.values().stream().mapToInt(Integer::intValue).max().orElse(0);

		// ---- assemble
		StringBuilder out = new StringBuilder("#version 450 core\n");
		extensions.forEach(e -> out.append(e).append('\n'));
		out.append("#define IRIS_IN ").append(inVerts).append('\n');
		out.append(fetchPrelude(PARAMS_BLOCK, "", attributeSlots));
		out.append("""
			vec4 iris_vs_Position;
			float iris_vs_PointSize;
			int iris_PrimitiveIDIn;
			int iris_InvocationID;
			int iris_PrimitiveID;
			int iris_Layer;
			int iris_ViewportIndex;
			int iris_target;
			int iris_corner;
			int iris_base;
			int iris_k;
			bool iris_found;
			bool iris_pending;
			void iris_emit();
			void iris_end();

			""");

		out.append("// ---- pack vertex shader\n").append(vsBody);
		defines.forEach(d -> out.append("#undef ").append(d).append('\n'));
		out.append("// ---- pack geometry shader\n").append(gsBody);

		// ---- capture + driver
		out.append("vec4 iris_cap_Position;\nfloat iris_cap_PointSize;\n");
		for (Var o : gsOutputs) {
			out.append(o.type()).append(" iris_cap_").append(o.name()).append(o.array()).append(";\n");
		}
		String corner = primVerts == 3 ? "((j & 1) == 1 && iris_corner < 2 ? 1 - iris_corner : iris_corner)" : "iris_corner";
		out.append("void iris_emit() {\n\tint j = iris_target - iris_base;\n\tif (!iris_found && j >= 0 && iris_k == j + ").append(corner).append(") {\n");
		out.append("\t\tiris_cap_Position = gl_Position;\n");
		if (pointSize) {
			out.append("\t\tiris_cap_PointSize = gl_PointSize;\n");
		}
		for (Var o : gsOutputs) {
			out.append("\t\tiris_cap_").append(o.name()).append(" = ").append(o.name()).append(";\n");
		}
		out.append("\t\tiris_pending = true;\n\t}\n\tiris_k++;\n}\n");
		out.append("void iris_end() {\n\tif (iris_pending && iris_k >= iris_target - iris_base + ").append(primVerts).append(") {\n\t\tiris_found = true;\n\t}\n")
			.append("\tiris_pending = false;\n\tiris_base += max(0, iris_k - ").append(primVerts - 1).append(");\n\tiris_k = 0;\n}\n");

		out.append("void main() {\n");
		out.append("\tuint slots = ").append(slotsPerInvocation).append("u;\n");
		out.append("\tuint vid = uint(gl_VertexIndex);\n");
		out.append("\tuint prim = vid / (slots * ").append(invocations).append("u);\n");
		out.append("\tuint r = vid % (slots * ").append(invocations).append("u);\n");
		out.append("\tiris_InvocationID = int(r / slots);\n\tr = r % slots;\n");
		out.append("\tiris_target = int(r / ").append(primVerts).append("u);\n\tiris_corner = int(r % ").append(primVerts).append("u);\n");
		out.append("\tiris_base = 0;\n\tiris_k = 0;\n\tiris_found = false;\n\tiris_pending = false;\n");
		out.append("\tfor (int i = 0; i < IRIS_IN; i++) {\n\t\tuint v = iris_vertex(prim * uint(IRIS_IN) + uint(i));\n\t\tiris_VertexID = int(v);\n");
		appendVertexRun(out, vp, attributes, gsInputs);
		out.append("\t}\n\tiris_PrimitiveIDIn = int(prim);\n\tiris_gs_main();\n\tiris_end();\n");
		for (Var o : gsOutputs) {
			out.append("\t").append(o.name()).append(" = iris_cap_").append(o.name()).append(";\n");
		}
		if (pointSize) {
			out.append("\tgl_PointSize = iris_cap_PointSize;\n");
		}
		// Unfilled slots go outside the clip volume.
		out.append("\tgl_Position = iris_found ? iris_cap_Position : vec4(2.0, 2.0, 2.0, 1.0);\n}\n");

		return new Combined(out.toString(), new Info(inVerts, outPrimitive, slotsPerInvocation * invocations, attributeSlots));
	}

	/** Vertex shader with inputs/outputs turned into globals and every global name prefixed iris_vs_ (no clashes). */
	static VertexPart vertexPart(String vertex, Map<String, Integer> attributes, Set<String> extensions) {
		List<Var> vsInputs = new ArrayList<>();
		Map<String, Var> vsOutputs = new LinkedHashMap<>();
		Set<String> renamed = new LinkedHashSet<>();
		Set<String> defines = new LinkedHashSet<>();
		renamed.add("main");
		int nextAttribute = 16;
		StringBuilder vs = new StringBuilder();
		int depth = 0;
		for (String line : vertex.split("\n", -1)) {
			Matcher def = DEFINE.matcher(line);
			if (def.matches()) {
				defines.add(def.group(1));
			}
			if (depth == 0) {
				if (VERSION.matcher(line).matches()) {
					continue;
				}
				if (EXTENSION.matcher(line).matches()) {
					extensions.add(line.trim());
					continue;
				}
				Matcher m = INOUT.matcher(line);
				if (m.matches()) {
					Var v = new Var(m.group(3), m.group(4), m.group(5) != null ? m.group(5) : "");
					renamed.add(v.name());
					if (m.group(2).equals("in") || m.group(2).equals("attribute")) {
						Matcher loc = m.group(1) != null ? LOCATION.matcher(m.group(1)) : null;
						int location;
						if (loc != null && loc.find()) {
							location = Integer.parseInt(loc.group(1));
						} else if (attributes.containsKey(v.name())) {
							location = attributes.get(v.name());
						} else {
							location = nextAttribute++;
						}
						attributes.put(v.name(), location);
						vsInputs.add(v);
					} else {
						vsOutputs.put(v.name(), v);
					}
					vs.append(v.type()).append(' ').append(v.name()).append(v.array()).append(";\n");
					continue;
				}
				collectGlobals(line, renamed);
			}
			vs.append(line).append('\n');
			depth += count(line, '{') - count(line, '}');
		}
		String vsBody = vs.toString()
			.replaceAll("\\bgl_Position\\b", "iris_vs_Position")
			.replaceAll("\\bgl_PointSize\\b", "iris_vs_PointSize")
			.replaceAll("\\bgl_Vertex(ID|Index)\\b", "iris_VertexID");
		renamed.removeIf(n -> n.startsWith("gl_") || defines.contains(n));
		if (!renamed.isEmpty()) {
			vsBody = Pattern.compile("(?<![.\\w])(" + String.join("|", renamed) + ")\\b").matcher(vsBody).replaceAll("iris_vs_$1");
		}
		return new VertexPart(vsBody, vsInputs, vsOutputs, defines);

	}

	/**
	 * Declarations shared by stages that fetch vertices themselves: the params block (header, extra fields, attribute
	 * table), raw vertex/index buffers, gl_in emulation and the fetch functions. Expects IRIS_IN to be defined.
	 */
	static String fetchPrelude(String block, String extraFields, int attributeSlots) {
		return """
			layout(std140) uniform %s {
				uint iris_indexMode;
				uint iris_first;
				int iris_baseVertex;
				uint iris_stride;
			%s	uvec4 iris_attr[%d];
			};
			layout(std430, binding = %d) readonly buffer IrisFetchVertices { uint iris_vwords[]; };
			layout(std430, binding = %d) readonly buffer IrisFetchIndices { uint iris_iwords[]; };
			struct IrisPerVertex { vec4 iris_Position; float iris_PointSize; };
			IrisPerVertex iris_gl_in[IRIS_IN];
			int iris_VertexID;
			""".formatted(block, extraFields, attributeSlots, VERTEX_SSBO, INDEX_SSBO) + """
			uint iris_size(uint type) {
				return type == 1u || type == 2u ? 1u : (type == 3u || type == 4u || type == 7u) ? 2u : 4u;
			}

			uint iris_raw(uint type, uint addr) {
				uint word = iris_vwords[addr >> 2u];
				uint size = iris_size(type);
				return size == 4u ? word : (word >> ((addr & 3u) * 8u)) & ((1u << (size * 8u)) - 1u);
			}

			int iris_signed(uint type, uint raw) {
				return type == 2u ? (int(raw << 24u) >> 24) : type == 4u ? (int(raw << 16u) >> 16) : int(raw);
			}

			float iris_float(uint type, uint raw, bool norm) {
				switch (type) {
					case 0u: return uintBitsToFloat(raw);
					case 7u: return unpackHalf2x16(raw).x;
					case 1u: return norm ? float(raw) / 255.0 : float(raw);
					case 3u: return norm ? float(raw) / 65535.0 : float(raw);
					case 2u: return norm ? max(float(iris_signed(type, raw)) / 127.0, -1.0) : float(iris_signed(type, raw));
					case 4u: return norm ? max(float(iris_signed(type, raw)) / 32767.0, -1.0) : float(iris_signed(type, raw));
					case 5u: return float(raw);
					default: return float(int(raw));
				}
			}

			vec4 iris_fetchF(uint v, int loc) {
				vec4 r = vec4(0.0, 0.0, 0.0, 1.0);
				uvec4 a = iris_attr[loc];
				for (uint c = 0u; c < a.z; c++) {
					r[c] = iris_float(a.y, iris_raw(a.y, v * iris_stride + a.x + c * iris_size(a.y)), a.w != 0u);
				}
				return r;
			}

			ivec4 iris_fetchI(uint v, int loc) {
				ivec4 r = ivec4(0, 0, 0, 1);
				uvec4 a = iris_attr[loc];
				for (uint c = 0u; c < a.z; c++) {
					r[c] = iris_signed(a.y, iris_raw(a.y, v * iris_stride + a.x + c * iris_size(a.y)));
				}
				return r;
			}

			uint iris_vertex(uint i) {
				uint j = iris_first + i;
				if (iris_indexMode == 0u) {
					return j;
				}
				uint index = iris_indexMode == 1u ? (iris_iwords[j >> 1u] >> ((j & 1u) * 16u)) & 0xFFFFu : iris_iwords[j];
				return uint(int(index) + iris_baseVertex);
			}
			""";
	}

	/** Inside a loop over i (input vertex index) with v = vertex index: fetch, run the vertex shader, store its outputs. */
	static void appendVertexRun(StringBuilder out, VertexPart vp, Map<String, Integer> attributes, List<Var> nextInputs) {
		for (Var in : vp.inputs()) {
			if (in.type().startsWith("mat") || !in.array().isEmpty()) {
				continue; // ponytail: matrix/array vertex attributes stay zero; Iris formats have none.
			}
			boolean integer = in.type().startsWith("i") || in.type().startsWith("u");
			out.append("\t\tiris_vs_").append(in.name()).append(" = ").append(in.type()).append(integer ? "(iris_fetchI(v, " : "(iris_fetchF(v, ")
				.append(attributes.get(in.name())).append("));\n");
		}
		out.append("\t\tiris_vs_PointSize = 1.0;\n\t\tiris_vs_main();\n");
		out.append("\t\tiris_gl_in[i].iris_Position = iris_vs_Position;\n\t\tiris_gl_in[i].iris_PointSize = iris_vs_PointSize;\n");
		for (Var in : nextInputs) {
			if (vp.outputs().containsKey(in.name())) {
				out.append("\t\t").append(in.name()).append("[i] = iris_vs_").append(in.name()).append(";\n");
			}
		}
	}

	/** Names a global-scope line of the vertex shader declares (functions, structs, variables, constants). */
	private static void collectGlobals(String line, Set<String> into) {
		String t = line.trim();
		if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || t.startsWith("}") || t.startsWith("{")) {
			return;
		}
		Matcher s = STRUCT.matcher(line);
		if (s.matches()) {
			into.add(s.group(1));
			return;
		}
		Matcher f = FUNCTION.matcher(line);
		if (f.matches() && !NOT_TYPES.contains(t.split("\\s+")[0])) {
			into.add(f.group(1));
			return;
		}
		String flat = line;
		for (String prev = null; !flat.equals(prev); ) {
			prev = flat;
			flat = flat.replaceAll("\\([^()]*\\)", "").replaceAll("\\{[^{}]*\\}", "");
		}
		Matcher g = GLOBAL.matcher(flat);
		if (!g.matches() || NOT_TYPES.contains(g.group(1))) {
			return;
		}
		for (String decl : g.group(2).split(",")) {
			Matcher d = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]*\\])?\\s*(?:=.*|;.*)?$").matcher(decl);
			if (d.matches()) {
				into.add(d.group(1));
			}
		}
	}

	static int count(String s, char c) {
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

	/** Attribute table entry for a vertex element: {type, components, normalized}; type codes match iris_float(). */
	static int[] elementCode(String gpuFormat) {
		Matcher m = Pattern.compile("^([RGBA]+)(\\d+)_(\\w+)$").matcher(gpuFormat);
		if (!m.matches()) {
			throw new IllegalArgumentException("Graft Shaders: vertex format " + gpuFormat + " cannot be fetched by geometry emulation");
		}
		int bits = Integer.parseInt(m.group(2));
		String kind = m.group(3);
		boolean signed = kind.startsWith("S");
		int type = switch (bits) {
			case 8 -> signed ? 2 : 1;
			case 16 -> kind.endsWith("FLOAT") ? 7 : signed ? 4 : 3;
			case 32 -> kind.endsWith("FLOAT") ? 0 : kind.equals("SINT") ? 6 : 5;
			default -> throw new IllegalArgumentException("Graft Shaders: vertex format " + gpuFormat);
		};
		return new int[]{type, m.group(1).length(), kind.endsWith("NORM") ? 1 : 0};
	}
}
