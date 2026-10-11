package net.irisshaders.iris.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.graft.metal.MetalRenderPass;
import dev.graft.v1_21_11.compat.SodiumMetal;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Sodium 0.8 terrain with a shader pack (1.21.x). Graft runs Sodium's GL object model on Metal (SodiumMetal); Iris's
 * Sodium programs are Graft Shaders programs whose ids Sodium's GlProgram carries, so SodiumMetal hands their uniforms and
 * multi-draws here. Mirrors MixinShaderChunkRenderer on GL: Iris's program and gbuffer/shadow framebuffer per pass.
 */
public final class GraftShadersSodium implements SodiumMetal.External {
	private MetalRenderPass targetedPass;
	private int targetedGeneration = -1;
	private int targetedFramebuffer;

	private GraftShadersSodium() {
	}

	public static void install() {
		SodiumMetal.external = new GraftShadersSodium();
		SodiumMetal.terrainProgram = pass -> {
			BlendModeOverride.restore();
			if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline iris)) {
				return null;
			}
			var program = iris.getSodiumPrograms().getProgram(pass);
			if (program != null) {
				iris.getSodiumPrograms().getFramebuffer(pass).bind();
			}
			return program;
		};
	}

	@Override
	public boolean owns(int program) {
		return MetalProgram.get(program) != null;
	}

	@Override
	public void use(int program) {
		GraftShadersState.program = program == 0 ? null : MetalProgram.get(program);
	}

	@Override
	public void delete(int program) {
		MetalProgram.delete(program);
	}

	@Override
	public int uniformLocation(int program, String name) {
		return MetalProgram.get(program).location(name);
	}

	@Override
	public int uniformBlockIndex(int program, String name) {
		Integer slot = MetalProgram.get(program).linked.blockBindings().get(name);
		return slot != null ? slot : -1;
	}

	@Override
	public void uniformBlockBinding(int program, int index, int bindingPoint) {
		MetalProgram.get(program).sodiumBlockPoints.put(index, bindingPoint);
	}

	@Override
	public void uniformInt(int location, int value) {
		if (GraftShadersState.program != null && location >= 0) {
			GraftShadersState.program.setInts(location, 1, value, 0, 0, 0);
		}
	}

	@Override
	public void uniformFloats(int location, float[] values) {
		if (GraftShadersState.program != null && location >= 0) {
			GraftShadersState.program.setFloats(location, values);
		}
	}

	@Override
	public void uniformMatrix(int location, FloatBuffer columnMajor, int size) {
		if (GraftShadersState.program != null && location >= 0) {
			GraftShadersState.program.setMatrix(location, size, false, columnMajor);
		}
	}

	@Override
	public void draw(MetalRenderPass pass, RenderPipeline vanilla, List<SodiumMetal.Attr> attrs, long vertexBuffer, long indexBuffer, boolean u16, int[][] draws) {
		if (bind(pass, vanilla, attrs, vertexBuffer, indexBuffer, u16)) {
			for (int[] d : draws) {
				pass.drawIndexed(d[0], 1, d[1], d[2], 0);
			}
		}
	}

	/** Sodium's multi-draw arrays, read in place: int counts, long index-buffer byte offsets, int base vertices. */
	@Override
	public void draw(MetalRenderPass pass, RenderPipeline vanilla, List<SodiumMetal.Attr> attrs, long vertexBuffer, long indexBuffer, boolean u16,
					 int drawCount, long pCount, long pOffset, long pBaseVertex) {
		if (bind(pass, vanilla, attrs, vertexBuffer, indexBuffer, u16)) {
			int indexSize = u16 ? 2 : 4;
			for (int i = 0; i < drawCount; i++) {
				pass.drawIndexed(MemoryUtil.memGetInt(pCount + i * 4L), 1, (int)(MemoryUtil.memGetLong(pOffset + i * 8L) / indexSize), MemoryUtil.memGetInt(pBaseVertex + i * 4L), 0);
			}
		}
	}

	private boolean bind(MetalRenderPass pass, RenderPipeline vanilla, List<SodiumMetal.Attr> attrs, long vertexBuffer, long indexBuffer, boolean u16) {
		MetalProgram program = GraftShadersState.program;
		if (program == null) {
			return false;
		}
		retarget(pass, program);
		if (layout == null || attrs != layoutAttrs && !sameLayout(attrs, layoutAttrs)) {
			List<MetalProgram.SodiumAttribute> list = new ArrayList<>(attrs.size());
			for (SodiumMetal.Attr a : attrs) {
				list.add(new MetalProgram.SodiumAttribute(a.index(), a.offset(), SodiumMetal.vertexFormat(a)));
			}
			layout = new MetalProgram.SodiumLayout(attrs.get(0).stride(), list);
			layoutAttrs = attrs;
		}
		pass.setPipeline(program.pipeline(vanilla, ShadowRenderingState.areShadowsCurrentlyBeingRendered(), layout));
		pass.setVertexBuffer(0, vertexBuffer, 0L);
		pass.setVertexBuffer(MetalProgram.DEFAULTS_SLOT, MetalProgram.defaultsBuffer(), 0L);
		pass.setIndexBuffer(indexBuffer, u16 ? MetalRenderPass.INDEX_U16 : MetalRenderPass.INDEX_U32);
		program.sodiumBlockPoints.forEach((slot, point) -> {
			long buffer = SodiumMetal.buffer(SodiumMetal.uniformBuffer(point));
			if (buffer != 0L) {
				pass.setBuffer(slot, buffer, 0L);
			}
		});
		GraftShadersDraw.bindIrisResources(program, GraftShadersDraw.passBinder(pass, program), null);
		return true;
	}

	/** The last multi-draw's layout, reused while Sodium's attributes describe the same one (they differ only in buffer). */
	private List<SodiumMetal.Attr> layoutAttrs;
	private MetalProgram.SodiumLayout layout;

	private static boolean sameLayout(List<SodiumMetal.Attr> a, List<SodiumMetal.Attr> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			SodiumMetal.Attr x = a.get(i);
			SodiumMetal.Attr y = b.get(i);
			if (x.index() != y.index() || x.count() != y.count() || x.glType() != y.glType() || x.normalized() != y.normalized()
				|| x.intType() != y.intType() || x.stride() != y.stride() || x.offset() != y.offset()) {
				return false;
			}
		}
		return true;
	}

	/** SodiumMetal opens the pass on Sodium's target; Iris draws into the framebuffer it bound (gbuffers or shadow map). */
	private void retarget(MetalRenderPass pass, MetalProgram program) {
		int bound = GraftShadersState.framebuffer;
		if (pass == targetedPass && pass.generation() == targetedGeneration && bound == targetedFramebuffer) {
			return;
		}
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(bound);
		if (fb == null) {
			return;
		}
		MetalRenderPass.Attachment[] colors = new MetalRenderPass.Attachment[fb.drawBuffers.length];
		for (int i = 0; i < fb.drawBuffers.length; i++) {
			int index = fb.drawBuffers[i];
			if (index >= 0 && MetalTextureIds.get(fb.colors[index]) instanceof dev.graft.v1_21_11.MetalTexture t) {
				colors[i] = new MetalRenderPass.Attachment(t.mtl(), t.pixelFormat(), 0, null, Double.NaN);
			}
		}
		MetalRenderPass.Attachment depth = MetalTextureIds.get(fb.depth) instanceof dev.graft.v1_21_11.MetalTexture d
			? new MetalRenderPass.Attachment(d.mtl(), d.pixelFormat(), 0, null, Double.NaN) : null;
		pass.retarget(colors, depth, "iris:" + program.name);
		targetedPass = pass;
		targetedGeneration = pass.generation();
		targetedFramebuffer = bound;
	}
}
