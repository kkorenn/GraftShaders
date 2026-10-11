package net.irisshaders.iris.metal;

import com.mojang.renderpearl.api.GpuFormat;

/** GL internal-format enums (as used throughout Iris's pack directives) to Minecraft GpuFormats, which Graft maps to Metal. */
public final class MetalFormats {
	private MetalFormats() {
	}

	/** Metal has no 3-component color formats; RGB internal formats get an alpha channel. */
	public static GpuFormat fromGl(int internalFormat) {
		return switch (internalFormat) {
			case 0x1903, 0x8229 -> GpuFormat.R8_UNORM;                 // RED, R8
			case 0x8227, 0x822B -> GpuFormat.RG8_UNORM;                // RG, RG8
			case 0x1907, 0x8051, 0x1908, 0x8058, 0x8C41, 0x8C43 -> GpuFormat.RGBA8_UNORM; // RGB, RGB8, RGBA, RGBA8, SRGB8, SRGB8_A8
			case 0x8F94 -> GpuFormat.R8_SNORM;
			case 0x8F95 -> GpuFormat.RG8_SNORM;
			case 0x8F96, 0x8F97 -> GpuFormat.RGBA8_SNORM;
			case 0x822A -> GpuFormat.R16_UNORM;
			case 0x822C -> GpuFormat.RG16_UNORM;
			case 0x8054, 0x805B -> GpuFormat.RGBA16_UNORM;             // RGB16, RGBA16
			case 0x8F98 -> GpuFormat.R16_SNORM;
			case 0x8F99 -> GpuFormat.RG16_SNORM;
			case 0x8F9A, 0x8F9B -> GpuFormat.RGBA16_SNORM;
			case 0x822D -> GpuFormat.R16_FLOAT;
			case 0x822F -> GpuFormat.RG16_FLOAT;
			case 0x881B, 0x881A -> GpuFormat.RGBA16_FLOAT;             // RGB16F, RGBA16F
			case 0x822E -> GpuFormat.R32_FLOAT;
			case 0x8230 -> GpuFormat.RG32_FLOAT;
			case 0x8815, 0x8814 -> GpuFormat.RGBA32_FLOAT;             // RGB32F, RGBA32F
			case 0x8231 -> GpuFormat.R8_SINT;
			case 0x8232 -> GpuFormat.R8_UINT;
			case 0x8237 -> GpuFormat.RG8_SINT;
			case 0x8238 -> GpuFormat.RG8_UINT;
			case 0x8D8F, 0x8D8E -> GpuFormat.RGBA8_SINT;               // RGB8I, RGBA8I
			case 0x8D7D, 0x8D7C -> GpuFormat.RGBA8_UINT;
			case 0x8233 -> GpuFormat.R16_SINT;
			case 0x8234 -> GpuFormat.R16_UINT;
			case 0x8239 -> GpuFormat.RG16_SINT;
			case 0x823A -> GpuFormat.RG16_UINT;
			case 0x8D89, 0x8D88 -> GpuFormat.RGBA16_SINT;
			case 0x8D77, 0x8D76 -> GpuFormat.RGBA16_UINT;
			case 0x8235 -> GpuFormat.R32_SINT;
			case 0x8236 -> GpuFormat.R32_UINT;
			case 0x823B -> GpuFormat.RG32_SINT;
			case 0x823C -> GpuFormat.RG32_UINT;
			case 0x8D83, 0x8D82 -> GpuFormat.RGBA32_SINT;
			case 0x8D71, 0x8D70 -> GpuFormat.RGBA32_UINT;
			case 0x8C3A -> GpuFormat.RG11B10_FLOAT;                    // R11F_G11F_B10F
			case 0x8059 -> GpuFormat.RGB10A2_UNORM;
			case 0x906F -> GpuFormat.RGB10A2_UINT;
			case 0x8C3D -> GpuFormat.RGBA16_FLOAT;                     // RGB9_E5: no Minecraft equivalent, widen
			case 0x1902, 0x81A5, 0x81A6, 0x81A7, 0x8CAC -> GpuFormat.D32_FLOAT; // DEPTH_COMPONENT*
			case 0x84F9, 0x88F0, 0x8CAD -> GpuFormat.D32_FLOAT_S8_UINT;        // DEPTH_STENCIL*
			default -> throw new UnsupportedOperationException("Graft Shaders: GL internal format 0x" + Integer.toHexString(internalFormat) + " has no Metal mapping");
		};
	}

	public static int mipCount(int width, int height) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
	}
}
