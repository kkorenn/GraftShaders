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

import static net.irisshaders.iris.metal.MetalGeometry.Var;

/**
 * Tessellation on Metal. Metal tessellates natively but has no vertex/control stages before the tessellator, so:
 *  1. a compute pre-pass (one thread per patch) fetches the patch's vertices, runs the pack vertex shader on each, runs
 *     the pack control shader once per output control point, and writes control points, patch outputs and tessellation
 *     levels to a buffer plus the tessellation factors (MTL*TessellationFactorsHalf) Metal's tessellator reads;
 *  2. drawPatches runs the pack evaluation shader as Metal's post-tessellation vertex function, which loads its inputs
 *     from that buffer by gl_PrimitiveID.
 * Every value is stored in vec4 slots: per patch, outVerts control points of (position + per-vertex outputs), then
 * patch outputs, then two slots of levels.
 *
 * ponytail: control shader invocations run one after another in one thread, so barrier() is a no-op. Reading another
 * invocation's outputs before it ran sees zeros. Use one thread per invocation if a pack relies on that.
 */
public final class MetalTessellation {
	static final int CONTROL_POINT_SSBO = 92;
	static final int FACTOR_SSBO = 93;
	static final String PARAMS_BLOCK = "IrisTessellation";
	static final int THREADS = 64;

	/** partition: MTLTessellationPartitionMode; winding: MTLWinding. prepassMsl/threads are filled in by the linker. */
	public record Info(int inVerts, int outVerts, int patchBytes, boolean quads, long partition, long winding, int attributeSlots, String prepassMsl) {
		Info withPrepass(String msl) {
			return new Info(inVerts, outVerts, patchBytes, quads, partition, winding, attributeSlots, msl);
		}
	}

	record Combined(String prepass, String evaluation, Info info) {
	}

	private static final Pattern PATCH_VAR = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?patch\\s+(in|out)\\s+" + MetalGeometry.QUALIFIERS + "(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;\\s*$"
	);
	private static final Pattern VERTICES = Pattern.compile("vertices\\s*=\\s*(\\d+)");

	private MetalTessellation() {
	}

	/** inVerts: GL_PATCH_VERTICES (Minecraft draws triangle lists, so 3). */
	static Combined combine(String name, String vertex, String control, String evaluation, int inVerts, Map<String, Integer> attributes) {
		Set<String> extensions = new LinkedHashSet<>();
		MetalGeometry.VertexPart vp = MetalGeometry.vertexPart(vertex, attributes, extensions);

		// ---- control shader
		int outVerts = -1;
		List<Var> tcsInputs = new ArrayList<>();
		List<Var> perVertex = new ArrayList<>();
		List<Var> perPatch = new ArrayList<>();
		StringBuilder tcs = new StringBuilder();
		int depth = 0;
		for (String line : control.split("\n", -1)) {
			if (depth == 0) {
				if (MetalGeometry.VERSION.matcher(line).matches()) {
					continue;
				}
				if (MetalGeometry.EXTENSION.matcher(line).matches()) {
					extensions.add(line.trim());
					continue;
				}
				Matcher m = MetalGeometry.LAYOUT_OUT.matcher(line);
				if (m.matches()) {
					Matcher v = VERTICES.matcher(m.group(1));
					if (v.find()) {
						outVerts = Integer.parseInt(v.group(1));
					}
					continue;
				}
				if (MetalGeometry.INOUT_BLOCK.matcher(line).matches()) {
					throw new ShaderCompileException(name, "Graft Shaders: tessellation interface blocks are not supported on Metal");
				}
				m = PATCH_VAR.matcher(line);
				if (m.matches()) {
					Var v = new Var(m.group(2), m.group(3), m.group(4) != null ? m.group(4) : "");
					perPatch.add(v);
					tcs.append(v.type()).append(' ').append(v.name()).append(v.array()).append(";\n");
					continue;
				}
				m = MetalGeometry.INOUT.matcher(line);
				if (m.matches()) {
					Var v = new Var(m.group(3), m.group(4), "");
					boolean in = m.group(2).equals("in");
					(in ? tcsInputs : perVertex).add(v);
					tcs.append(v.type()).append(' ').append(v.name()).append(in ? "[IRIS_IN];\n" : "[IRIS_OUT];\n");
					continue;
				}
			}
			tcs.append(line).append('\n');
			depth += MetalGeometry.count(line, '{') - MetalGeometry.count(line, '}');
		}
		if (outVerts <= 0) {
			throw new ShaderCompileException(name, "Graft Shaders: tessellation control shader has no layout(vertices = N) out");
		}
		String tcsBody = shared(tcs.toString())
			.replaceAll("\\bgl_out\\b", "iris_gl_out")
			.replaceAll("\\bgl_InvocationID\\b", "iris_InvocationID")
			.replaceAll("\\bgl_PatchVerticesIn\\b", "IRIS_IN")
			.replaceAll("\\bgl_PrimitiveID\\b", "iris_PrimitiveID")
			.replaceAll("\\bbarrier\\s*\\(\\s*\\)", "iris_barrier()")
			.replaceAll("\\bmain\\s*\\(", "iris_tcs_main(");

		// ---- layout of one patch, in vec4 slots
		Map<String, Integer> vertexOffsets = new LinkedHashMap<>();
		int cpStride = 1;
		for (Var v : perVertex) {
			vertexOffsets.put(v.name(), cpStride);
			cpStride += slots(name, v);
		}
		Map<String, Integer> patchOffsets = new LinkedHashMap<>();
		int next = outVerts * cpStride;
		for (Var v : perPatch) {
			patchOffsets.put(v.name(), next);
			next += slots(name, v);
		}
		int levels = next;
		int patchVec4s = levels + 2;

		// ---- evaluation shader
		String domainLayout = "";
		List<Var> tesInputs = new ArrayList<>();
		List<Var> tesPatch = new ArrayList<>();
		StringBuilder tes = new StringBuilder();
		depth = 0;
		for (String line : evaluation.split("\n", -1)) {
			if (depth == 0) {
				if (MetalGeometry.VERSION.matcher(line).matches()) {
					continue;
				}
				if (MetalGeometry.EXTENSION.matcher(line).matches()) {
					extensions.add(line.trim());
					continue;
				}
				Matcher m = MetalGeometry.LAYOUT_IN.matcher(line);
				if (m.matches()) {
					domainLayout += m.group(1) + ",";
				}
				if (MetalGeometry.INOUT_BLOCK.matcher(line).matches()) {
					throw new ShaderCompileException(name, "Graft Shaders: tessellation interface blocks are not supported on Metal");
				}
				m = PATCH_VAR.matcher(line);
				if (m.matches()) {
					Var v = new Var(m.group(2), m.group(3), m.group(4) != null ? m.group(4) : "");
					tesPatch.add(v);
					tes.append(v.type()).append(' ').append(v.name()).append(v.array()).append(";\n");
					continue;
				}
				m = MetalGeometry.INOUT.matcher(line);
				if (m.matches() && m.group(2).equals("in")) {
					Var v = new Var(m.group(3), m.group(4), "");
					tesInputs.add(v);
					tes.append(v.type()).append(' ').append(v.name()).append("[IRIS_OUT];\n");
					continue;
				}
			}
			tes.append(line).append('\n');
			depth += MetalGeometry.count(line, '{') - MetalGeometry.count(line, '}');
		}
		if (domainLayout.contains("isolines")) {
			throw new ShaderCompileException(name, "Graft Shaders: isoline tessellation is not supported on Metal");
		}
		boolean quads = domainLayout.contains("quads");
		long partition = domainLayout.contains("fractional_odd_spacing") ? 2 : domainLayout.contains("fractional_even_spacing") ? 3 : 1;
		long winding = domainLayout.matches(".*\\bcw\\b.*") ? 0 : 1;
		String tesBody = shared(tes.toString())
			.replaceAll("\\bgl_PatchVerticesIn\\b", "IRIS_OUT")
			.replaceAll("\\bmain\\s*\\(", "iris_tes_main(");

		int attributeSlots = 1 + attributes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		String header = "#version 450 core\n" + String.join("\n", extensions) + "\n#define IRIS_IN " + inVerts + "\n#define IRIS_OUT " + outVerts + "\n";

		// ---- pre-pass (compute)
		StringBuilder pre = new StringBuilder(header);
		pre.append("layout(local_size_x = ").append(THREADS).append(") in;\n");
		pre.append(MetalGeometry.fetchPrelude(PARAMS_BLOCK, "\tuvec4 iris_extra; // x = patch count, y = instance\n", attributeSlots));
		pre.append("layout(std430, binding = ").append(CONTROL_POINT_SSBO).append(") writeonly buffer IrisTessControlPoints { vec4 iris_cp[]; };\n");
		pre.append("layout(std430, binding = ").append(FACTOR_SSBO).append(") writeonly buffer IrisTessFactors { uint iris_tf[]; };\n");
		pre.append("""
			vec4 iris_vs_Position;
			float iris_vs_PointSize;
			IrisPerVertex iris_gl_out[IRIS_OUT];
			int iris_InvocationID;
			int iris_PrimitiveID;
			float iris_TessLevelOuter[4];
			float iris_TessLevelInner[2];
			void iris_barrier() {
			}
			""");
		pre.append("// ---- pack vertex shader\n").append(vp.body().replaceAll("\\bgl_Instance(ID|Index)\\b", "int(iris_extra.y)"));
		vp.defines().forEach(d -> pre.append("#undef ").append(d).append('\n'));
		pre.append("// ---- pack tessellation control shader\n").append(tcsBody);
		pre.append("void main() {\n\tuint prim = gl_GlobalInvocationID.x;\n\tif (prim >= iris_extra.x) {\n\t\treturn;\n\t}\n");
		pre.append("\tfor (int i = 0; i < IRIS_IN; i++) {\n\t\tuint v = iris_vertex(prim * uint(IRIS_IN) + uint(i));\n\t\tiris_VertexID = int(v);\n");
		MetalGeometry.appendVertexRun(pre, vp, attributes, tcsInputs);
		pre.append("\t}\n\tiris_PrimitiveID = int(prim);\n");
		pre.append("\tfor (int k = 0; k < IRIS_OUT; k++) {\n\t\tiris_InvocationID = k;\n\t\tiris_tcs_main();\n\t}\n");
		pre.append("\tuint base = prim * ").append(patchVec4s).append("u;\n");
		pre.append("\tfor (int k = 0; k < IRIS_OUT; k++) {\n\t\tuint cp = base + uint(k * ").append(cpStride).append(");\n");
		pre.append("\t\tiris_cp[cp] = iris_gl_out[k].iris_Position;\n");
		for (Var v : perVertex) {
			store(pre, "\t\t", "cp + " + vertexOffsets.get(v.name()) + "u", v.name() + "[k]", v.type(), "");
		}
		pre.append("\t}\n");
		for (Var v : perPatch) {
			store(pre, "\t", "base + " + patchOffsets.get(v.name()) + "u", v.name(), v.type(), v.array());
		}
		pre.append("\tiris_cp[base + ").append(levels).append("u] = vec4(iris_TessLevelOuter[0], iris_TessLevelOuter[1], iris_TessLevelOuter[2], iris_TessLevelOuter[3]);\n");
		pre.append("\tiris_cp[base + ").append(levels + 1).append("u] = vec4(iris_TessLevelInner[0], iris_TessLevelInner[1], 0.0, 0.0);\n");
		if (quads) { // MTLQuadTessellationFactorsHalf: edge[4], inside[2]
			pre.append("\tiris_tf[prim * 3u] = packHalf2x16(vec2(iris_TessLevelOuter[0], iris_TessLevelOuter[1]));\n");
			pre.append("\tiris_tf[prim * 3u + 1u] = packHalf2x16(vec2(iris_TessLevelOuter[2], iris_TessLevelOuter[3]));\n");
			pre.append("\tiris_tf[prim * 3u + 2u] = packHalf2x16(vec2(iris_TessLevelInner[0], iris_TessLevelInner[1]));\n");
		} else { // MTLTriangleTessellationFactorsHalf: edge[3], inside
			pre.append("\tiris_tf[prim * 2u] = packHalf2x16(vec2(iris_TessLevelOuter[0], iris_TessLevelOuter[1]));\n");
			pre.append("\tiris_tf[prim * 2u + 1u] = packHalf2x16(vec2(iris_TessLevelOuter[2], iris_TessLevelInner[0]));\n");
		}
		pre.append("}\n");

		// ---- evaluation (post-tessellation vertex function)
		StringBuilder ev = new StringBuilder(header);
		ev.append("layout(std430, binding = ").append(CONTROL_POINT_SSBO).append(") readonly buffer IrisTessControlPoints { vec4 iris_cp[]; };\n");
		ev.append("""
			struct IrisPerVertex { vec4 iris_Position; float iris_PointSize; };
			IrisPerVertex iris_gl_in[IRIS_OUT];
			float iris_TessLevelOuter[4];
			float iris_TessLevelInner[2];
			""");
		ev.append("// ---- pack tessellation evaluation shader\n").append(tesBody);
		ev.append("void main() {\n\tuint base = uint(gl_PrimitiveID) * ").append(patchVec4s).append("u;\n");
		ev.append("\tfor (int k = 0; k < IRIS_OUT; k++) {\n\t\tuint cp = base + uint(k * ").append(cpStride).append(");\n");
		ev.append("\t\tiris_gl_in[k].iris_Position = iris_cp[cp];\n\t\tiris_gl_in[k].iris_PointSize = 1.0;\n");
		for (Var in : tesInputs) {
			Integer off = vertexOffsets.get(in.name());
			if (off != null) {
				load(ev, "\t\t", "cp + " + off + "u", in.name() + "[k]", in.type(), "");
			}
		}
		ev.append("\t}\n");
		for (Var in : tesPatch) {
			Integer off = patchOffsets.get(in.name());
			if (off != null) {
				load(ev, "\t", "base + " + off + "u", in.name(), in.type(), in.array());
			}
		}
		ev.append("\tvec4 outer = iris_cp[base + ").append(levels).append("u];\n\tvec4 inner = iris_cp[base + ").append(levels + 1).append("u];\n");
		ev.append("\tiris_TessLevelOuter = float[4](outer.x, outer.y, outer.z, outer.w);\n\tiris_TessLevelInner = float[2](inner.x, inner.y);\n");
		ev.append("\tiris_tes_main();\n}\n");

		Info info = new Info(inVerts, outVerts, patchVec4s * 16, quads, partition, winding, attributeSlots, null);
		return new Combined(pre.toString(), ev.toString(), info);
	}

	/** gl_in / gl_TessLevel* emulation shared by control and evaluation shaders. */
	private static String shared(String body) {
		return body
			.replaceAll("\\.\\s*gl_Position\\b", ".iris_Position")
			.replaceAll("\\.\\s*gl_PointSize\\b", ".iris_PointSize")
			.replaceAll("\\bgl_in\\b", "iris_gl_in")
			.replaceAll("\\bgl_TessLevelOuter\\b", "iris_TessLevelOuter")
			.replaceAll("\\bgl_TessLevelInner\\b", "iris_TessLevelInner");
	}

	// ---- vec4 slot packing ------------------------------------------------------------------------------------------

	private static int arrayLength(String name, String array) {
		if (array.isEmpty()) {
			return 1;
		}
		String n = array.replaceAll("[\\[\\]\\s]", "");
		if (!n.matches("\\d+")) {
			throw new ShaderCompileException(name, "Graft Shaders: tessellation varying arrays need a literal size, got " + array);
		}
		return Integer.parseInt(n);
	}

	/** vec4 slots one value of this type takes (matrices: one per column). */
	private static int typeSlots(String type) {
		Matcher mat = Pattern.compile("d?mat(\\d)(?:x\\d)?").matcher(type);
		return mat.matches() ? Integer.parseInt(mat.group(1)) : 1;
	}

	private static int slots(String name, Var v) {
		return typeSlots(v.type()) * arrayLength(name, v.array());
	}

	private static int components(String type) {
		Matcher m = Pattern.compile("[biud]?vec(\\d)").matcher(type);
		return m.matches() ? Integer.parseInt(m.group(1)) : 1;
	}

	private static char kind(String type) {
		return type.startsWith("int") || type.startsWith("ivec") ? 'i' : type.startsWith("uint") || type.startsWith("uvec") ? 'u'
			: type.startsWith("bool") || type.startsWith("bvec") ? 'b' : 'f';
	}

	/** vec4 holding one scalar/vector value (ints bit-cast, so they round-trip exactly). */
	private static String pack(String expr, String type) {
		int n = components(type);
		String pad = ", 0".repeat(4 - n);
		return switch (kind(type)) {
			case 'i' -> "intBitsToFloat(ivec4(" + expr + pad + "))";
			case 'u' -> "uintBitsToFloat(uvec4(" + expr + pad.replace("0", "0u") + "))";
			case 'b' -> "vec4(" + (n == 1 ? "float(" + expr + ")" : "vec" + n + "(" + expr + ")") + pad.replace("0", "0.0") + ")";
			default -> "vec4(" + expr + pad.replace("0", "0.0") + ")";
		};
	}

	private static String unpack(String slot, String type) {
		int n = components(type);
		String swizzle = "." + "xyzw".substring(0, n);
		return switch (kind(type)) {
			case 'i' -> "floatBitsToInt(" + slot + ")" + swizzle;
			case 'u' -> "floatBitsToUint(" + slot + ")" + swizzle;
			case 'b' -> n == 1 ? "(" + slot + ".x != 0.0)" : "notEqual(" + slot + swizzle + ", vec" + n + "(0.0))";
			default -> slot + swizzle;
		};
	}

	private static void store(StringBuilder out, String indent, String index, String expr, String type, String array) {
		int len = array.isEmpty() ? 1 : Integer.parseInt(array.replaceAll("[\\[\\]\\s]", ""));
		int cols = typeSlots(type);
		for (int e = 0; e < len; e++) {
			String value = array.isEmpty() ? expr : expr + "[" + e + "]";
			for (int c = 0; c < cols; c++) {
				String column = cols > 1 ? value + "[" + c + "]" : value;
				String colType = cols > 1 ? "vec" + type.replaceAll("d?mat(\\d)(?:x(\\d))?", "$2").replaceAll("^$", String.valueOf(cols)) : type;
				out.append(indent).append("iris_cp[").append(index).append(" + ").append(e * cols + c).append("u] = ").append(pack(column, colType)).append(";\n");
			}
		}
	}

	private static void load(StringBuilder out, String indent, String index, String target, String type, String array) {
		int len = array.isEmpty() ? 1 : Integer.parseInt(array.replaceAll("[\\[\\]\\s]", ""));
		int cols = typeSlots(type);
		for (int e = 0; e < len; e++) {
			String value = array.isEmpty() ? target : target + "[" + e + "]";
			for (int c = 0; c < cols; c++) {
				String slot = "iris_cp[" + index + " + " + (e * cols + c) + "u]";
				String colType = cols > 1 ? "vec" + type.replaceAll("d?mat(\\d)(?:x(\\d))?", "$2").replaceAll("^$", String.valueOf(cols)) : type;
				out.append(indent).append(cols > 1 ? value + "[" + c + "]" : value).append(" = ").append(unpack(slot, colType)).append(";\n");
			}
		}
	}
}
