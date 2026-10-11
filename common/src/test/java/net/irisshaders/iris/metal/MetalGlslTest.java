package net.irisshaders.iris.metal;

import dev.graft.metal.MetalDevice;
import dev.graft.metal.ObjC;
import dev.graft.metal.ShaderTranslator;

import java.util.Map;

/** Links Iris-style GLSL through MetalGlsl and compiles the MSL with Metal. Run: ./gradlew :common:linkerTest */
public final class MetalGlslTest {
	static final String VS = """
		#version 330 core
		in vec3 iris_Position;
		in vec2 iris_UV0;
		layout(std140) uniform iris_DynamicTransforms {
			mat4 ModelViewMat;
			vec4 ColorModulator;
		};
		uniform mat4 iris_ProjMat;
		uniform vec3 u_RegionOffset;
		uniform int u_CurrentTime;
		uniform uint u_RegionID;
		uniform isamplerBuffer u_SectionTimeInfo;
		out vec2 texcoord; // split across declarators below on purpose
		flat out float fade, unusedA;
		out vec3 arr[2];
		out mat3 tbn;
		void main() {
			arr[0] = vec3(0.0); arr[1] = vec3(1.0); tbn = mat3(1.0);
			unusedA = 1.0;
			int t = texelFetch(u_SectionTimeInfo, int(u_RegionID)).r;
			fade = float(u_CurrentTime - t);
			gl_Position = iris_ProjMat * ModelViewMat * vec4(iris_Position + u_RegionOffset, 1.0);
			texcoord = iris_UV0;
		}
		""";
	static final String FS = """
		#version 330 core
		uniform sampler2D gtexture;
		uniform sampler2DShadow shadowtex0;
		uniform float frameTimeCounter;
		uniform vec2 weights[3];
		layout(rgba16f) uniform image2D colorimg0;
		layout(std430, binding = 2) buffer PackData { float values[]; };
		in vec2 texcoord;
		flat in float fade, unusedA;
		in vec3 arr[2];
		in mat3 tbn;
		layout(location = 0) out vec4 outColor;
		void main() {
			bool new = texcoord.x > 0.5; // C++ keyword, legal GLSL (BSL)
			float thread = new ? 1.0 : 0.0;
			vec4 c = texture(gtexture, texcoord) * (weights[1].x + frameTimeCounter + fade + thread);
			c.a *= texture(shadowtex0, vec3(texcoord, 0.5));
			imageStore(colorimg0, ivec2(gl_FragCoord.xy), c);
			outColor = c + vec4(values[0]);
		}
		""";
	static final String CS = """
		#version 430 core
		layout(local_size_x = 8, local_size_y = 4) in;
		layout(r32ui) uniform uimage2D counter;
		uniform int frameCounter;
		layout(std430, binding = 0) writeonly buffer Out { uint data[]; };
		void main() {
			uint v = imageAtomicAdd(counter, ivec2(gl_GlobalInvocationID.xy), 1u);
			data[gl_GlobalInvocationID.x] = v + uint(frameCounter);
		}
		""";

	// Pack-style: helper + const + uniform declared in both stages (renaming/dedupe), RGBA8 color via raw fetch.
	static final String GVS = """
		#version 330 core
		in vec3 vaPosition;
		in vec4 vaColor;
		uniform float shift;
		const float SCALE = 1.0;
		out vec4 color;
		float helper(float x) { return x * SCALE; }
		void main() {
			gl_Position = vec4(vaPosition.x + shift, helper(vaPosition.y), vaPosition.z, 1.0);
			color = vaColor;
		}
		""";
	static final String GGS = """
		#version 330 core
		layout(triangles) in;
		layout(triangle_strip, max_vertices = 6) out;
		uniform float shift;
		const float SCALE = 1.0;
		in vec4 color[];
		out vec4 gColor;
		float helper(float x) { return x * SCALE + shift; }
		void main() {
			for (int i = 0; i < 3; i++) {
				gl_Position = gl_in[i].gl_Position;
				gColor = color[i];
				EmitVertex();
			}
			EndPrimitive();
			for (int i = 0; i < 3; i++) {
				gl_Position = vec4(gl_in[i].gl_Position.x, -helper(gl_in[i].gl_Position.y), gl_in[i].gl_Position.zw);
				gColor = vec4(0.0, 0.0, 1.0, 1.0);
				EmitVertex();
			}
		}
		""";
	static final String GFS = """
		#version 330 core
		uniform float shift;
		in vec4 gColor;
		out vec4 fragColor;
		void main() { fragColor = gColor + vec4(shift); }
		""";

	/** Draws one indexed triangle through the emulated geometry shader and checks both emitted triangles landed. */
	static void geometryRender(MetalDevice device) {
		MetalGlsl.Linked g = MetalGlsl.link("geom", GVS, GGS, GFS, new java.util.HashMap<>(Map.of("vaPosition", 0, "vaColor", 1)));
		MetalGeometry.Info info = g.geometry();
		assert info != null && info.inVerts() == 3 && info.slotsPerPrim() == 12 : info;
		long rgba8 = dev.graft.metal.Mtl.pixelFormat("RGBA8_UNORM");
		dev.graft.metal.MetalPipeline pipeline = new dev.graft.metal.MetalPipeline(
			device, "geom", g.vertexMsl(), g.fragmentMsl(), g.kinds(), java.util.List.of(), java.util.List.of(),
			new dev.graft.metal.MetalPipeline.ColorTarget[]{dev.graft.metal.MetalPipeline.ColorTarget.opaque(rgba8, dev.graft.metal.Mtl.writeMask(15))},
			new dev.graft.metal.MetalPipeline.Raster(info.outPrimitive(), false, false, false, -1, false, 0, 0)
		);
		int[] table = new int[info.attributeSlots() * 4];
		int[] pos = MetalGeometry.elementCode("RGB32_FLOAT");
		int[] col = MetalGeometry.elementCode("RGBA8_UNORM");
		System.arraycopy(new int[]{0, pos[0], pos[1], pos[2]}, 0, table, g.attributeLocations().get("vaPosition") * 4, 4);
		System.arraycopy(new int[]{12, col[0], col[1], col[2]}, 0, table, g.attributeLocations().get("vaColor") * 4, 4);
		pipeline.geometry = new dev.graft.metal.MetalPipeline.Geometry(info.inVerts(), info.slotsPerPrim(), g.blockBindings().get(MetalGeometry.PARAMS_BLOCK),
			g.storageBufferBindings().get(MetalGeometry.VERTEX_SSBO), g.storageBufferBindings().get(MetalGeometry.INDEX_SSBO), 16, table);

		long vb = device.newBuffer(48);
		java.lang.foreign.MemorySegment v = java.lang.foreign.MemorySegment.ofAddress(MetalDevice.contents(vb)).reinterpret(48);
		float[][] p = {{-1, -1, 0}, {3, -1, 0}, {-1, 0, 0}};
		for (int i = 0; i < 3; i++) {
			v.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, i * 16, p[i][0]);
			v.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, i * 16 + 4, p[i][1]);
			v.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, i * 16 + 8, p[i][2]);
			v.set(java.lang.foreign.ValueLayout.JAVA_INT, i * 16 + 12, 0xFF00FF00); // green
		}
		long ib = device.newBuffer(16);
		java.lang.foreign.MemorySegment ix = java.lang.foreign.MemorySegment.ofAddress(MetalDevice.contents(ib)).reinterpret(16);
		// One leading dummy index: firstIndex = 1 checks u16 addressing at an odd index.
		for (int i = 0; i < 4; i++) {
			ix.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, i, (short)Math.max(0, i - 1));
		}

		dev.graft.metal.MetalCommands commands = new dev.graft.metal.MetalCommands(device);
		long target = device.newTexture(rgba8, false, 4, 4, 1, 1);
		dev.graft.metal.MetalRenderPass pass = commands.beginRenderPass(
			new dev.graft.metal.MetalRenderPass.Attachment[]{new dev.graft.metal.MetalRenderPass.Attachment(target, rgba8, 0, new float[]{0, 0, 0, 0}, Double.NaN)},
			null, 0, 0, 4, 4, "geom"
		);
		pass.setPipeline(pipeline);
		pass.setBytes(g.blockBinding(), java.nio.ByteBuffer.allocate(g.blockSize())); // shift = 0
		pass.setVertexBuffer(0, vb, 0);
		pass.setIndexBuffer(ib, dev.graft.metal.MetalRenderPass.INDEX_U16);
		pass.drawIndexed(3, 1, 1, 0, 0);
		commands.endRenderPass();
		long readback = device.newBuffer(64);
		commands.copyTextureToBuffer(target, 0, 0, 0, 4, 4, readback, 0, 16);
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		java.lang.foreign.MemorySegment px = java.lang.foreign.MemorySegment.ofAddress(MetalDevice.contents(readback)).reinterpret(64);
		for (int row = 0; row < 4; row++) {
			StringBuilder sb = new StringBuilder("geom row " + row + ":");
			for (int c = 0; c < 4; c++) {
				sb.append(String.format(" %08x", px.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, row * 4 + c)));
			}
			System.out.println(sb);
		}
		assert px.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0) == 0xFF00FF00 : "first emitted triangle (green, bottom) missing";
		assert px.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 12) == 0xFFFF0000 : "second emitted triangle (blue, mirrored to top) missing";
		pipeline.close();
		commands.close();
	}

	static final String TVS = """
		#version 330 core
		in vec3 vaPosition;
		out vec3 pos;
		void main() { pos = vaPosition; gl_Position = vec4(vaPosition, 1.0); }
		""";
	static final String TCS = """
		#version 400 core
		layout(vertices = 3) out;
		uniform float tessLevel;
		in vec3 pos[];
		out vec3 cpos[];
		void main() {
			cpos[gl_InvocationID] = pos[gl_InvocationID];
			gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
			if (gl_InvocationID == 0) {
				gl_TessLevelOuter[0] = tessLevel;
				gl_TessLevelOuter[1] = tessLevel;
				gl_TessLevelOuter[2] = tessLevel;
				gl_TessLevelInner[0] = tessLevel;
			}
		}
		""";
	static final String TES = """
		#version 400 core
		layout(triangles, equal_spacing, ccw) in;
		in vec3 cpos[];
		out vec4 tColor;
		layout(std430, binding = 3) buffer Count { uint invocations; };
		void main() {
			gl_Position = vec4(gl_TessCoord.x * cpos[0] + gl_TessCoord.y * cpos[1] + gl_TessCoord.z * cpos[2], 1.0);
			atomicAdd(invocations, 1u);
			tColor = vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";
	static final String TFS = """
		#version 330 core
		in vec4 tColor;
		out vec4 fragColor;
		void main() { fragColor = tColor; }
		""";

	/** Tessellates one triangle (level from a uniform the pre-pass reads) with back-face culling on; checks pixels + vertex count. */
	static void tessellationRender(MetalDevice device) {
		MetalGlsl.Linked t = MetalGlsl.link("tess", TVS, TCS, TES, null, TFS, new java.util.HashMap<>(Map.of("vaPosition", 0)));
		MetalTessellation.Info info = t.tessellation();
		assert info != null && info.outVerts() == 3 && !info.quads() && info.prepassMsl() != null : info;
		long rgba8 = dev.graft.metal.Mtl.pixelFormat("RGBA8_UNORM");
		dev.graft.metal.MetalPipeline pipeline = new dev.graft.metal.MetalPipeline(
			device, "tess", t.vertexMsl(), t.fragmentMsl(), t.kinds(), java.util.List.of(), java.util.List.of(),
			new dev.graft.metal.MetalPipeline.ColorTarget[]{dev.graft.metal.MetalPipeline.ColorTarget.opaque(rgba8, dev.graft.metal.Mtl.writeMask(15))},
			new dev.graft.metal.MetalPipeline.Raster(dev.graft.metal.Mtl.PRIM_TRIANGLE, false, true, false, -1, false, 0, 0)
		);
		int[] table = new int[info.attributeSlots() * 4];
		int[] pos = MetalGeometry.elementCode("RGB32_FLOAT");
		System.arraycopy(new int[]{0, pos[0], pos[1], pos[2]}, 0, table, t.attributeLocations().get("vaPosition") * 4, 4);
		long prepass = device.newComputePipeline("tess.prepass", info.prepassMsl());
		pipeline.tessellation = new dev.graft.metal.MetalPipeline.Tessellation(prepass, MetalTessellation.THREADS, info.inVerts(), info.outVerts(), info.patchBytes(),
			info.quads(), info.partition(), info.winding(), t.blockBindings().get(MetalTessellation.PARAMS_BLOCK),
			t.storageBufferBindings().get(MetalGeometry.VERTEX_SSBO), t.storageBufferBindings().get(MetalGeometry.INDEX_SSBO),
			t.storageBufferBindings().get(MetalTessellation.CONTROL_POINT_SSBO), t.storageBufferBindings().get(MetalTessellation.FACTOR_SSBO), 16, table);

		long vb = device.newBuffer(48);
		java.lang.foreign.MemorySegment v = java.lang.foreign.MemorySegment.ofAddress(MetalDevice.contents(vb)).reinterpret(48);
		float[][] p = {{-1, -1, 0}, {3, -1, 0}, {-1, 0, 0}};
		for (int i = 0; i < 3; i++) {
			for (int c = 0; c < 3; c++) {
				v.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, i * 16 + c * 4, p[i][c]);
			}
		}
		java.nio.ByteBuffer uniforms = java.nio.ByteBuffer.allocate(t.blockSize()).order(java.nio.ByteOrder.nativeOrder());
		uniforms.putFloat(t.uniforms().get("tessLevel").offset(), 4.0F);
		long counter = device.newBuffer(16);
		java.lang.foreign.MemorySegment n = java.lang.foreign.MemorySegment.ofAddress(MetalDevice.contents(counter)).reinterpret(16);
		n.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);

		dev.graft.metal.MetalCommands commands = new dev.graft.metal.MetalCommands(device);
		long target = device.newTexture(rgba8, false, 4, 4, 1, 1);
		dev.graft.metal.MetalRenderPass pass = commands.beginRenderPass(
			new dev.graft.metal.MetalRenderPass.Attachment[]{new dev.graft.metal.MetalRenderPass.Attachment(target, rgba8, 0, new float[]{0, 0, 0, 0}, Double.NaN)},
			null, 0, 0, 4, 4, "tess"
		);
		pass.setPipeline(pipeline);
		pass.setBytes(t.blockBinding(), uniforms);
		pass.setBuffer(t.storageBufferBindings().get(3), counter, 0);
		pass.setVertexBuffer(0, vb, 0);
		pass.draw(3, 1, 0, 0);
		commands.endRenderPass();
		long readback = device.newBuffer(64);
		commands.copyTextureToBuffer(target, 0, 0, 0, 4, 4, readback, 0, 16);
		commands.submit();
		commands.awaitCompletion(commands.submitIndex() - 1, -1);
		java.lang.foreign.MemorySegment px = java.lang.foreign.MemorySegment.ofAddress(MetalDevice.contents(readback)).reinterpret(64);
		for (int row = 0; row < 4; row++) {
			StringBuilder sb = new StringBuilder("tess row " + row + ":");
			for (int c = 0; c < 4; c++) {
				sb.append(String.format(" %08x", px.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, row * 4 + c)));
			}
			System.out.println(sb);
		}
		int evaluated = n.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
		System.out.println("tess evaluation invocations: " + evaluated);
		assert px.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0) == 0xFF00FF00 : "tessellated triangle missing (or culled: winding wrong)";
		assert px.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 12) == 0 : "top row should stay clear";
		assert evaluated >= 15 : "level 4 should evaluate at least 15 vertices, got " + evaluated;
		pipeline.close();
		dev.graft.metal.ObjC.release(prepass);
		commands.close();
	}

	public static void main(String[] args) {
		MetalDevice device = new MetalDevice();
		Map<String, Integer> attributes = Map.of("iris_Position", 0, "iris_UV0", 1);
		MetalGlsl.Linked g = MetalGlsl.link("test", VS, FS, attributes);

		// Bliss: a uniform in one stage is a plain global of the same name in the other, and "sampler" is a parameter name.
		MetalGlsl.Linked bliss = MetalGlsl.link("bliss", """
			#version 330 core
			in vec3 iris_Position;
			uniform vec3 sunPosition;
			uniform sampler2D colortex4;
			vec3 sunVec = normalize(sunPosition);
			vec3 sky(vec3 pos, sampler2D sampler) { return texture(sampler, pos.xy).rgb + pos; }
			float bias() { return 0.5; }
			bvec3 disjunction(bvec3 a, bvec3 b) { return bvec3(a.x || b.x, a.y || b.y, a.z || b.z); }
			float length_squared(vec3 v) { return dot(v, v); }
			float tanh(float x) {
				return (exp(x) - exp(-x)) / (exp(x) + exp(-x));
			}
			flat out vec3 col;
			void main() { col = sky(sunVec, colortex4) * tanh(bias()) * length_squared(sunVec) * float(all(disjunction(bvec3(true), bvec3(false)))); gl_Position = vec4(iris_Position, 1.0); }
			""", """
			#version 330 core
			uniform vec3 sunVec;
			flat in vec3 col;
			out vec4 fragColor;
			void main() { fragColor = vec4(col + sunVec, 1.0); }
			""", Map.of("iris_Position", 0));
		// Photon: a struct varying takes one location per member; the next varyings must not overlap it.
		MetalGlsl.link("photon", """
			#version 330 core
			in vec3 iris_Position;
			struct Params {
				float a;
				vec2 b;
				mat2 c;
				float d[2];
			};
			flat out Params params;
			out vec2 uv;
			void main() { params.a = 1.0; params.b = vec2(2.0); params.c = mat2(1.0); params.d[0] = 0.0; params.d[1] = 1.0; uv = iris_Position.xy; gl_Position = vec4(iris_Position, 1.0); }
			""", """
			#version 330 core
			struct Params {
				float a;
				vec2 b;
				mat2 c;
				float d[2];
			};
			flat in Params params;
			in vec2 uv;
			out vec4 fragColor;
			void main() { fragColor = vec4(params.a + params.b.x + params.c[1].y + params.d[1], uv, 1.0); }
			""", Map.of("iris_Position", 0));

		// Photon: vec3 into an RGBA target; padded to 4 components with alpha 1 like GL (alpha 0 showed as black).
		String padded = MetalGlsl.link("pad", """
			#version 330 core
			in vec3 iris_Position;
			void main() { gl_Position = vec4(iris_Position, 1.0); }
			""", """
			#version 330 core
			layout(location = 0) out vec3 fragment_color;
			void main() { fragment_color = vec3(0.5); }
			""", Map.of("iris_Position", 0)).fragmentMsl();
		assert padded.contains("float4 fragment_color") && padded.contains("out.fragment_color.w = 1;") : padded;

		// Photon: "flat out vec4[16 / 4] histogram_pdf;" takes 4 locations, the varyings after it must not overlap.
		assert MetalGlsl.intExpression("16/4") == 4 && MetalGlsl.intExpression("2*(3+1)-1") == 7 && MetalGlsl.intExpression("N") == 1;
		MetalGlsl.link("hist", """
			#version 330 core
			in vec3 iris_Position;
			flat out vec4[16 / 4] histogram_pdf;
			flat out float exposure;
			void main() { for (int i = 0; i < 4; i++) histogram_pdf[i] = vec4(i); exposure = 2.0; gl_Position = vec4(iris_Position, 1.0); }
			""", """
			#version 330 core
			flat in vec4[16 / 4] histogram_pdf;
			flat in float exposure;
			out vec4 fragColor;
			void main() { fragColor = histogram_pdf[3] * exposure; }
			""", Map.of("iris_Position", 0));

		// Photon: RGB16F texel data into an RGBA16F texture gets alpha = half 1.0 (0x3C00), texels stay aligned.
		java.nio.ByteBuffer rgb = org.lwjgl.system.MemoryUtil.memAlloc(12);
		for (int i = 0; i < 6; i++) rgb.putShort((short)(0x1000 + i));
		rgb.flip();
		java.nio.ByteBuffer rgba = net.irisshaders.iris.gl.IrisRenderSystem.rgbToRgba(rgb, 0x1907, com.mojang.blaze3d.GpuFormat.RGBA16_FLOAT, 2);
		assert rgba != null && rgba.getShort(6) == 0x3C00 && rgba.getShort(8) == 0x1003 && rgba.getShort(14) == 0x3C00 : "rgb16f expansion";
		org.lwjgl.system.MemoryUtil.memFree(rgb);
		org.lwjgl.system.MemoryUtil.memFree(rgba);

		// Photon: shared vec3[256][9] fits GL's 32 KB with 12-byte vec3s, Metal's float3 is 16 bytes; rows get copied whole.
		MetalGlsl.Linked sh = MetalGlsl.linkCompute("sh", """
			#version 430 core
			layout (local_size_x = 256) in;
			layout (rgba16f) uniform writeonly image2D colorimg4;
			shared vec3 shared_memory[256][9];
			vec3 sum(vec3 c[9]) { vec3 s = vec3(0.0); for (int i = 0; i < 9; i++) s += c[i]; return s; }
			void main() {
				uint i = gl_LocalInvocationID.x;
				for (int b = 0; b < 9; b++) shared_memory[i][b] = vec3(float(b));
				barrier();
				if (i == 0u) imageStore(colorimg4, ivec2(0), vec4(sum(shared_memory[0]), 1.0));
			}
			""");
		assert sh.computeMsl().contains("packed_float3") : "shared vec3 arrays must be packed";
		device.newComputePipeline("sh", sh.computeMsl());

		assert bliss.uniforms().containsKey("sunVec") && bliss.uniforms().keySet().stream().noneMatch(n -> n.startsWith("iris_otherStage_")) : bliss.uniforms().keySet();

		// Interface bookkeeping Graft Shaders relies on at draw time.
		assert g.samplerBindings().keySet().equals(java.util.Set.of("gtexture", "shadowtex0")) : g.samplerBindings();
		assert g.texelBufferBindings().containsKey("u_SectionTimeInfo") : g.texelBufferBindings();
		assert g.imageBindings().containsKey("colorimg0") : g.imageBindings();
		assert g.storageBufferBindings().containsKey(2) : "pack SSBO index 2 must be remapped: " + g.storageBufferBindings();
		assert g.blockBindings().containsKey("iris_DynamicTransforms") : g.blockBindings();
		assert g.uniforms().containsKey("iris_ProjMat") && g.uniforms().containsKey("frameTimeCounter") : g.uniforms().keySet();
		assert !g.uniforms().containsKey("u_RegionOffset") : "Sodium per-region values must be push constants";
		MetalGlsl.Member weights = g.uniforms().get("weights");
		assert weights.arraySize() == 3 && weights.arrayStride() == 16 : "std140 vec2 array stride: " + weights;
		assert g.uniforms().get("iris_ProjMat").matrixStride() == 16 : g.uniforms().get("iris_ProjMat");
		assert g.vertexMsl().contains("[[buffer(" + ShaderTranslator.PUSH_CONSTANTS + ")]]") : "push constants slot missing";

		// The MSL must compile on the real device.
		assert !g.vertexMsl().matches("(?s).*user\\(locn(\\d+)\\).*user\\(locn\\1\\).*") : "duplicate varying locations:\n" + g.vertexMsl();
		ObjC.release(device.compileLibrary("test.vsh", g.vertexMsl()));
		ObjC.release(device.compileLibrary("test.fsh", g.fragmentMsl()));

		MetalGlsl.Linked c = MetalGlsl.linkCompute("testc", CS);
		assert c.localSize()[0] == 8 && c.localSize()[1] == 4 && c.localSize()[2] == 1 : java.util.Arrays.toString(c.localSize());
		assert c.imageBindings().containsKey("counter") && c.storageBufferBindings().containsKey(0) : c;
		ObjC.release(device.newComputePipeline("testc", c.computeMsl()));

		// More than 16 textures: samplers come from a shared array through an index table.
		StringBuilder many = new StringBuilder("#version 330 core\nin vec2 texcoord;\nin float fade;\nout vec4 outColor;\n");
		for (int i = 0; i < 20; i++) {
			many.append("uniform sampler2D tex").append(i).append(";\n");
		}
		many.append("uniform sampler2DShadow shadowtex0;\nvoid main() {\n\tvec4 c = vec4(texture(shadowtex0, vec3(texcoord, 0.5)));\n");
		for (int i = 0; i < 20; i++) {
			many.append("\tc += texture(tex").append(i).append(", texcoord);\n");
		}
		many.append("\toutColor = c;\n}\n");
		MetalGlsl.Linked m = MetalGlsl.link("many", VS, many.toString(), attributes);
		assert m.samplerTableSlot() >= 0 && m.fragmentMsl().contains("array<sampler, 16> iris_samplers") : "sampler table not applied";
		ObjC.release(device.compileLibrary("many.fsh", m.fragmentMsl()));
		ObjC.release(device.compileLibrary("many.vsh", m.vertexMsl()));

		geometryRender(device);
		tessellationRender(device);

		device.close();
		System.out.println("LINKER TEST OK");
	}
}
