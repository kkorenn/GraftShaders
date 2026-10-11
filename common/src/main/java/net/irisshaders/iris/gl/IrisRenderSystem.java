package net.irisshaders.iris.gl;

import net.irisshaders.iris.metal.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.graft.metal.ObjC;
import dev.graft.v1_21.MetalGpuDevice;
import dev.graft.v1_21.MetalTexture;
import net.irisshaders.iris.metal.MetalFormats;
import net.irisshaders.iris.metal.MetalFramebuffers;
import net.irisshaders.iris.metal.GraftShadersState;
import net.irisshaders.iris.metal.MetalProgram;
import net.irisshaders.iris.metal.MetalSamplers;
import net.irisshaders.iris.metal.MetalTextureIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Iris's render-system entry points, implemented on Metal (through Graft). Signatures are kept so the rest of Iris
 * is unchanged; ids passed in are Graft Shaders keys (textures, framebuffers, programs, samplers), not GL objects.
 * Features not ported yet throw, so a pack that needs them fails loudly instead of rendering garbage.
 */
public class IrisRenderSystem {
	private static GpuBufferSlice backupProjection;
	private static PerspectiveProjectionMatrixBuffer perspectiveProjectionMatrixBuffer;
	private static ProjectionType backupProjectionType;
	private static int polygonMode = 0x1B02;
	private static int backupPolygonMode = 0x1B02;
	private static int boundTexture;
	private static boolean cullingState;

	private static UnsupportedOperationException unported(String what) {
		return new UnsupportedOperationException("Graft Shaders: " + what + " is not ported to Metal yet");
	}

	private static CommandEncoder encoder() {
		return RenderSystem.getDevice().createCommandEncoder();
	}

	public static void initRenderer() {
		perspectiveProjectionMatrixBuffer = new PerspectiveProjectionMatrixBuffer("Iris shadow map projection");
	}

	// ---- capabilities -------------------------------------------------------------------------------------------------

	public static boolean supportsSSBO() {
		return true;
	}

	public static boolean supportsImageLoadStore() {
		return true;
	}

	public static boolean supportsBufferBlending() {
		return true; // per-draw-buffer blend overrides are part of the Metal pipeline (BlendModeStorage)
	}

	public static boolean supportsCompute() {
		return true;
	}

	public static boolean supportsTesselation() {
		return true; // compute pre-pass + Metal tessellator (MetalTessellation)
	}

	public static int getMaxImageUnits() {
		return GraftShadersState.imageUnits.length;
	}

	public static long getVRAM() {
		return ObjC.send(MetalGpuDevice.current().metal().device, ObjC.sel("recommendedMaxWorkingSetSize"));
	}

	public static void getIntegerv(int pname, int[] params) {
		throw unported("glGetIntegerv(0x" + Integer.toHexString(pname) + ")");
	}

	public static void getFloatv(int pname, float[] params) {
		throw unported("glGetFloatv(0x" + Integer.toHexString(pname) + ")");
	}

	public static String getStringi(int glEnum, int index) {
		throw unported("glGetStringi");
	}

	// ---- textures -----------------------------------------------------------------------------------------------------

	public static int createTexture(int target) {
		return MetalTextureIds.allocate();
	}

	public static void bindTextureForSetup(int glType, int glId) {
		boundTexture = glId;
	}

	public static void restoreTexture() {
	}

	private static void defineTexture(int texture, int level, int internalformat, int width, int height, @Nullable ByteBuffer pixels, int format) {
		MetalTextureIds.Entry entry = MetalTextureIds.entry(texture);
		if (entry == null) {
			throw new IllegalStateException("Graft Shaders: unknown texture id " + texture);
		}
		GpuFormat gpuFormat = MetalFormats.fromGl(internalformat);
		if (level == 0) {
			if (entry.texture != null && entry.owned) {
				entry.texture.close();
			}
			entry.owned = true;
			// Metal needs the mip count up front (GL grows mips on demand), so color targets get a full chain.
			// 1.21's TextureFormat has 4 formats; Graft textures take any (Minecraft sees a nominal one).
			entry.texture = new MetalTexture(MetalGpuDevice.current().metal(),
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				"Iris texture " + texture, gpuFormat.hasColorAspect() ? com.mojang.blaze3d.textures.TextureFormat.RGBA8 : com.mojang.blaze3d.textures.TextureFormat.DEPTH32,
				gpuFormat.name(), gpuFormat.blockSize(), Math.max(1, width), Math.max(1, height), 1, gpuFormat.hasColorAspect() ? MetalFormats.mipCount(width, height) : 1
			);
			if (pixels == null && gpuFormat.hasColorAspect()) {
				// GL drivers zero new storage and packs rely on it; Metal leaves it undefined (NaN for float formats).
				encoder().clearColorTexture(entry.texture, 0);
			}
		}
		if (pixels != null) {
			ByteBuffer expanded = rgbToRgba(pixels, format, gpuFormat, (long)width * height);
			((dev.graft.v1_21.MetalCommandEncoder)encoder()).writeRaw(entry.texture, expanded != null ? expanded : pixels, level, 0, 0, 0, width, height);
			if (expanded != null) {
				MemoryUtil.memFree(expanded);
			}
		}
	}

	/**
	 * GL_RGB data for a texture stored as RGBA (Metal has no 3-channel formats): a new buffer with alpha = 1 in the
	 * format's encoding, or null when no expansion is needed. Photon's atmosphere table is RGB16F half floats; uploading
	 * it unexpanded misaligned every texel and the whole sky came out black.
	 */
	public static @Nullable ByteBuffer rgbToRgba(ByteBuffer pixels, int format, GpuFormat gpuFormat, long texels) {
		int texel = gpuFormat.blockSize();
		if (format != 0x1907 || !gpuFormat.name().startsWith("RGBA") || texel % 4 != 0) { // GL_RGB
			return null;
		}
		int comp = texel / 4;
		boolean isFloat = gpuFormat.name().endsWith("FLOAT");
		long one = comp == 1 ? 0xFFL : comp == 2 ? (isFloat ? 0x3C00L : 0xFFFFL) : (isFloat ? 0x3F800000L : 0xFFFFFFFFL);
		ByteBuffer out = MemoryUtil.memAlloc((int)(texels * texel));
		long src = MemoryUtil.memAddress(pixels);
		long dst = MemoryUtil.memAddress(out);
		long available = pixels.remaining() / (3L * comp);
		for (long i = 0; i < Math.min(texels, available); i++) {
			MemoryUtil.memCopy(src + i * 3 * comp, dst + i * texel, 3L * comp);
			for (int b = 0; b < comp; b++) {
				MemoryUtil.memPutByte(dst + i * texel + 3L * comp + b, (byte)(one >>> (8 * b)));
			}
		}
		return out;
	}

	public static void texImage1D(int texture, int target, int level, int internalformat, int width, int border, int format, int type, @Nullable ByteBuffer pixels) {
		defineTexture(texture, level, internalformat, width, 1, pixels, format);
	}

	public static void texImage2D(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
		defineTexture(texture, level, internalformat, width, height, pixels, format);
	}

	/** 3D textures (pack custom textures) are raw Metal 3D textures; Minecraft's GpuTexture has no 3D type. */
	public static void texImage3D(int texture, int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, @Nullable ByteBuffer pixels) {
		MetalTextureIds.Entry entry = MetalTextureIds.entry(texture);
		if (entry == null) {
			throw new IllegalStateException("Graft Shaders: unknown texture id " + texture);
		}
		if (level != 0) {
			throw unported("3D texture mip levels");
		}
		GpuFormat gpuFormat = MetalFormats.fromGl(internalformat);
		var metal = MetalGpuDevice.current().metal();
		if (entry.raw != 0L) {
			ObjC.release(entry.raw);
		}
		entry.owned = true;
		entry.raw = metal.newTexture3D(dev.graft.metal.Mtl.pixelFormat(gpuFormat.name()), width, height, depth);
		int texel = gpuFormat.blockSize();
		entry.rawTexel = texel;
		entry.rawKind = gpuFormat.name().endsWith("_UINT") ? 'u' : gpuFormat.name().endsWith("_SINT") ? 'i' : 'f';
		if (pixels == null) {
			// GL drivers hand out zeroed storage and packs rely on it (e.g. images accumulated across frames with clear
			// off); Metal leaves new textures undefined, which can be NaN for float formats.
			MetalGpuDevice.current().commands().clearTexture3D(entry.raw, width, height, depth, entry.rawKind);
			return;
		}
		long size = (long)width * height * depth * texel;
		var commands = MetalGpuDevice.current().commands();
		var scratch = commands.scratch(size);
		ByteBuffer expanded = rgbToRgba(pixels, format, gpuFormat, (long)width * height * depth);
		ByteBuffer source = expanded != null ? expanded : pixels;
		MemoryUtil.memCopy(MemoryUtil.memAddress(source), scratch.address(), Math.min(size, source.remaining()));
		if (expanded != null) {
			MemoryUtil.memFree(expanded);
		}
		commands.copyBufferToTexture3D(scratch.buffer(), scratch.offset(), (long)width * texel, width, height, depth, entry.raw);
	}

	public static void texParameteri(int texture, int target, int pname, int param) {
		MetalTextureIds.Entry e = MetalTextureIds.entry(texture);
		if (e == null) {
			return;
		}
		switch (pname) {
			case 0x2801 -> { // MIN_FILTER
				e.mipmapped = param >= 0x2700 && param <= 0x2703;
				e.linear = param == 0x2601 || param == 0x2701 || param == 0x2703;
			}
			case 0x2800 -> e.linear = param == 0x2601; // MAG_FILTER
			case 0x2802, 0x2803 -> e.clamp = param == 0x812F; // WRAP_S/T: CLAMP_TO_EDGE
			case 0x884C -> { // TEXTURE_COMPARE_MODE
				e.setCompare(param == 0x884E, -1);
			}
			case 0x884D -> e.setCompare(null, param); // TEXTURE_COMPARE_FUNC
			default -> {
			}
		}
	}

	public static void texParameterf(int texture, int target, int pname, float param) {
	}

	public static void texParameteriv(int texture, int target, int pname, int[] params) {
	}

	public static void texParameterivDirect(int target, int pname, int[] params) {
	}

	public static int getTexParameteri(int texture, int target, int pname) {
		return 0;
	}

	public static void generateMipmaps(int texture, int mipmapTarget) {
		GpuTexture t = MetalTextureIds.get(texture);
		if (t instanceof MetalTexture metal && t.getMipLevels() > 1) {
			MetalGpuDevice.current().commands().generateMipmaps(metal.mtl());
		}
	}

	public static void bindTextureToUnit(int target, int unit, int texture) {
		GraftShadersState.debugUnbind(unit, texture);
		GraftShadersState.unitTextures[unit] = texture;
	}

	public static void bindTextures(int startingTexture, int[] bindings) {
		for (int i = 0; i < bindings.length; i++) {
			GraftShadersState.unitTextures[startingTexture + i] = bindings[i];
		}
	}

	private static GpuTexture readSource() {
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(GraftShadersState.framebuffer);
		if (fb == null) {
			throw new IllegalStateException("Graft Shaders: copy with no framebuffer bound");
		}
		return MetalTextureIds.get(fb.colors[fb.readBuffer]);
	}

	public static void copyTexSubImage2D(int destTexture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
		encoder().copyTextureToTexture(readSource(), MetalTextureIds.get(destTexture), level, xoffset, yoffset, x, y, width, height);
	}

	/** GL: (re)defines the texture on the active unit from the read framebuffer; depth formats read the depth attachment. */
	public static void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
		int texture = GraftShadersState.unitTextures[GraftShadersState.activeUnit];
		MetalTextureIds.Entry entry = MetalTextureIds.entry(texture);
		if (entry == null) {
			throw new IllegalStateException("Graft Shaders: copyTexImage2D with no texture bound");
		}
		boolean depth = !MetalFormats.fromGl(internalFormat).hasColorAspect();
		GpuTexture existing = entry.texture;
		boolean fits = existing != null && existing.getWidth(level) == width && existing.getHeight(level) == height
			&& existing.getFormat().hasColorAspect() != depth;
		if (!fits) {
			if (!entry.owned && existing != null) {
				throw new IllegalStateException("Graft Shaders: copyTexImage2D would resize a Minecraft-owned texture");
			}
			defineTexture(texture, level, internalFormat, width, height, null, 0);
		}
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(GraftShadersState.framebuffer);
		GpuTexture source = depth ? MetalTextureIds.get(fb.depth) : MetalTextureIds.get(fb.colors[fb.readBuffer]);
		encoder().copyTextureToTexture(source, entry.texture, level, 0, 0, x, y, width, height);
	}

	public static void copyImageSubData(int sourceTexture, int target, int mip, int srcX, int srcY, int srcZ, int destTexture, int dstTarget, int dstMip, int dstX, int dstY, int dstZ, int width, int height, int depth) {
		encoder().copyTextureToTexture(MetalTextureIds.get(sourceTexture), MetalTextureIds.get(destTexture), mip, dstX, dstY, srcX, srcY, width, height);
	}

	public static void onProgramUse() {
	}

	public static void addUnswizzle(int shaderTexture) {
	}

	// ---- samplers -----------------------------------------------------------------------------------------------------

	public static int genSampler() {
		throw unported("raw sampler objects (use GlSampler)");
	}

	public static void destroySampler(int id) {
		MetalSamplers.destroy(id);
	}

	public static void bindSamplerToUnit(int unit, int sampler) {
		GraftShadersState.unitSamplers[unit] = sampler;
	}

	public static void unbindAllSamplers() {
		Arrays.fill(GraftShadersState.unitSamplers, 0);
	}

	public static void samplerParameteri(int sampler, int pname, int param) {
	}

	public static void samplerParameterf(int sampler, int pname, float param) {
	}

	public static void samplerParameteriv(int sampler, int pname, int[] params) {
	}

	// ---- framebuffers -------------------------------------------------------------------------------------------------

	public static int createFramebuffer() {
		return MetalFramebuffers.create();
	}

	public static void framebufferTexture2D(int fb, int fbtarget, int attachment, int target, int texture, int levels) {
		MetalFramebuffers.attach(fb, attachment, texture);
	}

	public static void drawBuffers(int framebuffer, int[] buffers) {
		MetalFramebuffers.drawBuffers(framebuffer, buffers);
	}

	public static void readBuffer(int framebuffer, int buffer) {
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(framebuffer);
		if (fb != null && buffer != 0) {
			fb.readBuffer = buffer - 0x8CE0;
		}
	}

	public static int checkFramebufferStatus(int glFramebuffer) {
		return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
	}

	public static void clearColor(float r, float g, float b, float a) {
		GraftShadersState.clearColor[0] = r;
		GraftShadersState.clearColor[1] = g;
		GraftShadersState.clearColor[2] = b;
		GraftShadersState.clearColor[3] = a;
	}

	/** Clears every draw-buffer attachment (and depth when requested) of a framebuffer. */
	/**
	 * GL framebuffer/image clears touch the attached level only (level 0). Blaze3D's clearColorTexture clears every
	 * mip level, one Metal encoder each; Iris color buffers have full mip chains, so per-frame clears cost ~12x.
	 */
	private static void clearLevel0(GpuTexture texture, Vector4f c) {
		// Deferred: becomes the load action of the next pass rendering into it (no separate full-texture write).
		MetalGpuDevice.current().commands().deferClear(((dev.graft.v1_21.MetalTexture)texture).mtl(), new float[]{c.x(), c.y(), c.z(), c.w()});
	}

	public static void clearFramebuffer(int framebuffer, boolean color, boolean depth, Vector4f clearColor) {
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(framebuffer);
		if (fb == null) {
			return;
		}
		if (color) {
			for (int index : fb.drawBuffers) {
				GpuTexture t = index >= 0 ? MetalTextureIds.get(fb.colors[index]) : null;
				if (t != null) {
					clearLevel0(t, clearColor);
				}
			}
		}
		GpuTexture d = depth ? MetalTextureIds.get(fb.depth) : null;
		if (d != null) {
			encoder().clearDepthTexture(d, 1.0);
		}
	}

	public static void clearBufferfv(int framebuffer, int buffer, int drawbuffer, float[] values) {
		MetalFramebuffers.Framebuffer fb = MetalFramebuffers.get(framebuffer);
		if (fb == null) {
			return;
		}
		if (buffer == 0x1801) { // GL_DEPTH
			GpuTexture d = MetalTextureIds.get(fb.depth);
			if (d != null) {
				encoder().clearDepthTexture(d, values[0]);
			}
			return;
		}
		int index = fb.drawBuffers[drawbuffer];
		GpuTexture t = index >= 0 ? MetalTextureIds.get(fb.colors[index]) : null;
		if (t != null) {
			clearLevel0(t, new Vector4f(values[0], values[1], values[2], values[3]));
		}
	}

	public static void clearBufferiv(int framebuffer, int buffer, int drawbuffer, int[] values) {
		clearBufferfv(framebuffer, buffer, drawbuffer, new float[]{values[0], values[1], values[2], values[3]});
	}

	public static void clearBufferuiv(int framebuffer, int buffer, int drawbuffer, int[] values) {
		clearBufferiv(framebuffer, buffer, drawbuffer, values);
	}

	public static void blitFramebuffer(int source, int dest, int offsetX, int offsetY, int width, int height, int offsetX2, int offsetY2, int width2, int height2, int bufferChoice, int filter) {
		MetalFramebuffers.Framebuffer src = MetalFramebuffers.get(source);
		MetalFramebuffers.Framebuffer dst = MetalFramebuffers.get(dest);
		if (src == null || dst == null || width - offsetX != width2 - offsetX2 || height - offsetY != height2 - offsetY2) {
			throw unported("scaled framebuffer blits");
		}
		boolean depth = (bufferChoice & 0x100) != 0; // GL_DEPTH_BUFFER_BIT
		GpuTexture from = MetalTextureIds.get(depth ? src.depth : src.colors[src.readBuffer]);
		GpuTexture to = MetalTextureIds.get(depth ? dst.depth : dst.colors[Math.max(0, dst.drawBuffers[0])]);
		encoder().copyTextureToTexture(from, to, 0, offsetX2, offsetY2, offsetX, offsetY, width - offsetX, height - offsetY);
	}

	public static void readPixels(int x, int y, int width, int height, int format, int type, float[] pixels) {
		throw unported("glReadPixels");
	}

	// ---- programs & uniforms ------------------------------------------------------------------------------------------

	private static MetalProgram program() {
		return GraftShadersState.program;
	}

	public static void uniform1f(int location, float v0) {
		if (program() != null) program().setFloats(location, 1, v0, 0, 0, 0);
	}

	public static void uniform2f(int location, float v0, float v1) {
		if (program() != null) program().setFloats(location, 2, v0, v1, 0, 0);
	}

	public static void uniform3f(int location, float v0, float v1, float v2) {
		if (program() != null) program().setFloats(location, 3, v0, v1, v2, 0);
	}

	public static void uniform4f(int location, float v0, float v1, float v2, float v3) {
		if (program() != null) program().setFloats(location, 4, v0, v1, v2, v3);
	}

	public static void uniform1i(int location, int v0) {
		if (program() != null) program().setInts(location, 1, v0, 0, 0, 0);
	}

	public static void uniform2i(int location, int v0, int v1) {
		if (program() != null) program().setInts(location, 2, v0, v1, 0, 0);
	}

	public static void uniform3i(int location, int v0, int v1, int v2) {
		if (program() != null) program().setInts(location, 3, v0, v1, v2, 0);
	}

	public static void uniform4i(int location, int v0, int v1, int v2, int v3) {
		if (program() != null) program().setInts(location, 4, v0, v1, v2, v3);
	}

	public static void uniformMatrix4fv(int location, boolean transpose, FloatBuffer matrix) {
		if (program() != null) program().setMatrix(location, 4, transpose, matrix);
	}

	public static void uniformMatrix4fv(int location, boolean transpose, float[] matrix) {
		uniformMatrix4fv(location, transpose, FloatBuffer.wrap(matrix));
	}

	public static void uniformMatrix3fv(int location, boolean transpose, FloatBuffer matrix) {
		if (program() != null) program().setMatrix(location, 3, transpose, matrix);
	}

	public static void uniformMatrix3fv(int location, boolean transpose, float[] matrix) {
		uniformMatrix3fv(location, transpose, FloatBuffer.wrap(matrix));
	}

	public static void bindAttributeLocation(int program, int index, CharSequence name) {
		// Attribute locations are fixed when the program is linked for Metal.
	}

	public static int getAttribLocation(int program, String name) {
		MetalProgram p = MetalProgram.get(program);
		return p != null ? p.linked.attributeLocations().getOrDefault(name, -1) : -1;
	}

	public static void detachShader(int program, int shader) {
	}

	public static String getProgramInfoLog(int program) {
		return "";
	}

	public static String getShaderInfoLog(int shader) {
		return "";
	}

	public static String getActiveUniform(int program, int index, int size, IntBuffer type, IntBuffer name) {
		throw unported("glGetActiveUniform (use MetalProgram.activeUniformNames)");
	}

	public static void getProgramiv(int program, int value, int[] storage) {
		throw unported("glGetProgramiv");
	}

	public static int getUniformBlockIndex(int program, String uniformBlockName) {
		return -1;
	}

	public static void uniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
	}

	public static void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {
		// Constant vertex attributes: not needed for composite/final passes.
	}

	// ---- not ported yet (compute, storage buffers, images, per-buffer blending) --------------------------------------

	public static void memoryBarrier(int barriers) {
		// Metal tracks render/blit hazards itself; compute barriers arrive with compute support.
	}

	public static void dispatchCompute(int workX, int workY, int workZ) {
		net.irisshaders.iris.metal.GraftShadersCompute.dispatch(workX, workY, workZ);
	}

	public static void dispatchCompute(Vector3i workGroups) {
		dispatchCompute(workGroups.x, workGroups.y, workGroups.z);
	}

	public static void dispatchComputeIndirect(long offset) {
		net.irisshaders.iris.metal.GraftShadersCompute.dispatchIndirect(offset);
	}

	/** ponytail: binds mip level 0 of the texture; Iris images are single-level. Add per-level views if a pack needs it. */
	public static void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
		if (unit < GraftShadersState.imageUnits.length) {
			GraftShadersState.imageUnits[unit] = texture;
		}
	}

	/** Zero-fills a texture (glClearTexImage with no data). */
	public static void clearTexture(int texture) {
		MetalTextureIds.Entry e = MetalTextureIds.entry(texture);
		if (e == null) {
			return;
		}
		if (e.texture != null) {
			clearLevel0(e.texture, new Vector4f(0, 0, 0, 0));
		} else if (e.raw != 0L) {
			long w = ObjC.send(e.raw, ObjC.sel("width"));
			long h = ObjC.send(e.raw, ObjC.sel("height"));
			long d = ObjC.send(e.raw, ObjC.sel("depth"));
			// Write-only compute clear: packs clear large voxel volumes every frame.
			MetalGpuDevice.current().commands().clearTexture3D(e.raw, (int)w, (int)h, (int)d, e.rawKind);
		}
	}

	public static void bufferData(int target, float[] data, int usage) {
		throw unported("GL buffers");
	}

	public static int bufferStorage(int target, float[] data, int usage) {
		throw unported("GL buffers");
	}

	public static void bufferStorage(int target, long size, int flags) {
		throw unported("GL buffers");
	}

	public static void bindBufferBase(int target, Integer index, int buffer) {
		if (target == 0x90D2 && index != null && index < GraftShadersState.storageBuffers.length) { // GL_SHADER_STORAGE_BUFFER
			GraftShadersState.storageBuffers[index] = buffer;
		}
	}

	public static void genBuffers(int[] buffers) {
		throw unported("GL buffers");
	}

	public static int createBuffers() {
		throw unported("GL buffers");
	}

	public static void bindBuffer(int target, int buffer) {
		if (target == 0x90EE) { // GL_DISPATCH_INDIRECT_BUFFER
			GraftShadersState.dispatchIndirectBuffer = buffer;
		}
	}

	public static void deleteBuffers(int id) {
	}

	public static void clearBufferSubData(int glShaderStorageBuffer, int glR8, long offset, long size, int glRed, int glByte, int[] ints) {
		throw unported("shader storage buffers");
	}

	public static void disableBufferBlend(int buffer) {
	}

	public static void enableBufferBlend(int buffer) {
	}

	public static void blendFuncSeparatei(int buffer, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
	}

	// ---- unchanged game-side state ------------------------------------------------------------------------------------

	public static void setShadowProjection(Matrix4f shadowProjection) {
		backupProjection = RenderSystem.getProjectionMatrixBuffer();
		backupProjectionType = RenderSystem.getProjectionType();
		RenderSystem.setProjectionMatrix(perspectiveProjectionMatrixBuffer.getBuffer(shadowProjection), ProjectionType.ORTHOGRAPHIC);
	}

	public static void restorePlayerProjection() {
		RenderSystem.setProjectionMatrix(backupProjection, backupProjectionType);
		backupProjection = null;
		backupProjectionType = null;
	}

	public static void setPolygonMode(int mode) {
		polygonMode = mode;
	}

	public static void overridePolygonMode() {
		backupPolygonMode = polygonMode;
		setPolygonMode(0x1B02);
	}

	public static void restorePolygonMode() {
		setPolygonMode(backupPolygonMode);
		backupPolygonMode = 0x1B02;
	}

	public static void backupAndDisableCullingState(boolean b) {
		cullingState = Minecraft.getInstance().smartCull;
		Minecraft.getInstance().smartCull = Minecraft.getInstance().smartCull && !b;
	}

	public static void restoreCullingState() {
		Minecraft.getInstance().smartCull = cullingState;
		cullingState = true;
	}
}
