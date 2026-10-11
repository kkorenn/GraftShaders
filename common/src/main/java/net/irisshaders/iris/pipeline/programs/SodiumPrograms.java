package net.irisshaders.iris.pipeline.programs;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.caffeinemc.mods.sodium.client.gl.GlObject;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderType;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL43C;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public class SodiumPrograms {
	private final EnumMap<Pass, GlFramebuffer> framebuffers = new EnumMap<>(Pass.class);
	private final EnumMap<Pass, GlProgram<ChunkShaderInterface>> shaders = new EnumMap<>(Pass.class);

	private boolean hasBlockId;
	private boolean hasMidUv;
	private boolean hasNormal;
	private boolean hasMidBlock;

	public SodiumPrograms(IrisRenderingPipeline pipeline, ProgramSet programSet, ProgramFallbackResolver resolver,
						  RenderTargets renderTargets, Supplier<ShadowRenderTargets> shadowRenderTargets,
						  CustomUniforms customUniforms) {
		for (Pass pass : Pass.values()) {
			ProgramSource source = resolver.resolveNullable(pass.getOriginalId());
			Supplier<ImmutableSet<Integer>> flipState = getFlipState(pipeline, pass, pass == Pass.SHADOW || pass == Pass.SHADOW_CUTOUT);
			GlFramebuffer framebuffer = createFramebuffer(pass, source, shadowRenderTargets, renderTargets, flipState);
			framebuffers.put(pass, framebuffer);

			if (source == null) {
				continue;
			}

			AlphaTest alphaTest = getAlphaTest(pass, source);
			Map<PatchShaderType, String> transformed = transformShaders(source, alphaTest, programSet);
			GlProgram<ChunkShaderInterface> shader = createShader(pipeline, pass, source, alphaTest, customUniforms, flipState, transformed);
			shaders.put(pass, shader);
		}

		WorldRenderingSettings.INSTANCE.setVertexFormat(FormatAnalyzer.createFormat(hasBlockId, hasNormal, hasMidUv, hasMidBlock));
	}

	private AlphaTest getAlphaTest(Pass pass, ProgramSource source) {
		return source.getDirectives().getAlphaTestOverride().orElse(
			pass == Pass.TERRAIN_CUTOUT || pass == Pass.SHADOW_CUTOUT ? AlphaTests.ONE_TENTH_ALPHA : AlphaTest.ALWAYS);
	}

	private Map<PatchShaderType, String> transformShaders(ProgramSource source, AlphaTest alphaTest, ProgramSet programSet) {
		Map<PatchShaderType, String> transformed = TransformPatcher.patchSodium(
			source.getName(),
			source.getVertexSource().orElse(null),
			source.getGeometrySource().orElse(null),
			source.getTessControlSource().orElse(null),
			source.getTessEvalSource().orElse(null),
			source.getFragmentSource().orElse(null),
			alphaTest,
			programSet.getPackDirectives().getTextureMap());

		ShaderPrinter.printProgram("sodium_" + source.getName()).addSources(transformed).print();

		return transformed;
	}

	private Supplier<ImmutableSet<Integer>> getFlipState(IrisRenderingPipeline pipeline, Pass pass, boolean isShadowPass) {
		if (isShadowPass) {
			return pipeline::getFlippedBeforeShadow;
		}
		return () -> pass == Pass.TRANSLUCENT ? pipeline.getFlippedAfterTranslucent() : pipeline.getFlippedAfterPrepare();
	}

	private GlProgram<ChunkShaderInterface> createShader(IrisRenderingPipeline pipeline, Pass pass, ProgramSource source,
														 AlphaTest alphaTest,
														 CustomUniforms customUniforms, Supplier<ImmutableSet<Integer>> flipState,
														 Map<PatchShaderType, String> transformed) {
		// Metal: a Graft Shaders program; Sodium's GlProgram carries its id and Graft's SodiumMetal routes it to GraftShadersSodium.
		Map<String, Integer> attributes = new java.util.HashMap<>();
		attributes.put("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION);
		attributes.put("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR);
		attributes.put("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE);
		attributes.put("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX);
		attributes.put("mc_Entity", 11);
		attributes.put("mc_midTexCoord", 12);
		attributes.put("at_tangent", 13);
		attributes.put("iris_Normal", 10);
		attributes.put("at_midBlock", 14);
		String name = "sodium_" + (pass == Pass.TERRAIN ? "terrain_solid" : pass.name().toLowerCase(Locale.ROOT));
		int handle = net.irisshaders.iris.metal.MetalProgram.create(name, net.irisshaders.iris.metal.MetalGlsl.link(name,
			transformed.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.TESS_CONTROL), transformed.get(PatchShaderType.TESS_EVAL),
			transformed.get(PatchShaderType.GEOMETRY), transformed.get(PatchShaderType.FRAGMENT), attributes));

		if (!hasNormal) hasNormal = IrisRenderSystem.getAttribLocation(handle, "iris_Normal") != -1;
		if (!hasMidBlock) hasMidBlock = IrisRenderSystem.getAttribLocation(handle, "at_midBlock") != -1;
		if (!hasBlockId) hasBlockId = IrisRenderSystem.getAttribLocation(handle, "mc_Entity") != -1;
		if (!hasMidUv) hasMidUv = IrisRenderSystem.getAttribLocation(handle, "mc_midTexCoord") != -1;

		boolean containsTessellation = source.getTessEvalSource().isPresent();
		return new GlProgram<>(handle, context -> new SodiumShader(pipeline, pass, context, handle, source.getDirectives().getBlendModeOverride().orElse(null),
			createBufferBlendOverrides(source), customUniforms, flipState, alphaTest.reference(), containsTessellation)) {
		};
	}

	private GlFramebuffer createFramebuffer(Pass pass, ProgramSource source,
											Supplier<ShadowRenderTargets> shadowRenderTargets,
											RenderTargets renderTargets,
											Supplier<ImmutableSet<Integer>> flipState) {
		if (pass == Pass.SHADOW || pass == Pass.SHADOW_CUTOUT || pass == Pass.SHADOW_TRANS) {
			return shadowRenderTargets.get().createShadowFramebuffer(ImmutableSet.of(),
				source == null ? new int[]{0, 1} : (source.getDirectives().hasUnknownDrawBuffers() ? new int[]{0, 1} : source.getDirectives().getDrawBuffers()));
		} else {
			return renderTargets.createGbufferFramebuffer(flipState.get(), source == null ? new int[]{0, 1} : (source.getDirectives().hasUnknownDrawBuffers() ? new int[]{0} : source.getDirectives().getDrawBuffers()));
		}
	}

	private List<BufferBlendOverride> createBufferBlendOverrides(ProgramSource source) {
		List<BufferBlendOverride> overrides = new ArrayList<>();
		source.getDirectives().getBufferBlendOverrides().forEach(information -> {
			int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), information.index());
			if (index > -1) {
				overrides.add(new BufferBlendOverride(index, information.blendMode()));
			}
		});
		return overrides;
	}

	public GlProgram<ChunkShaderInterface> getProgram(TerrainRenderPass pass) {
		Pass pass2 = mapTerrainRenderPass(pass);
		return this.shaders.get(pass2);
	}

	public GlFramebuffer getFramebuffer(TerrainRenderPass pass) {
		Pass pass2 = mapTerrainRenderPass(pass);
		return this.framebuffers.get(pass2);
	}

	private Pass mapTerrainRenderPass(TerrainRenderPass pass) {
		if (pass == DefaultTerrainRenderPasses.SOLID) {
			return ShadowRenderingState.areShadowsCurrentlyBeingRendered() ? Pass.SHADOW : Pass.TERRAIN;
		} else if (pass == DefaultTerrainRenderPasses.CUTOUT) {
			return ShadowRenderingState.areShadowsCurrentlyBeingRendered() ? Pass.SHADOW_CUTOUT : Pass.TERRAIN_CUTOUT;
		} else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
			return ShadowRenderingState.areShadowsCurrentlyBeingRendered() ? Pass.SHADOW_TRANS : Pass.TRANSLUCENT;
		} else {
			throw new IllegalArgumentException("Unknown pass: " + pass);
		}
	}

	public enum Pass {
		SHADOW(ProgramId.ShadowSolid),
		SHADOW_CUTOUT(ProgramId.ShadowCutout),
		SHADOW_TRANS(ProgramId.ShadowWater),
		TERRAIN(ProgramId.TerrainSolid),
		TERRAIN_CUTOUT(ProgramId.TerrainCutout),
		TRANSLUCENT(ProgramId.Water);

		private final ProgramId originalId;

		Pass(ProgramId originalId) {
			this.originalId = originalId;
		}

		public ProgramId getOriginalId() {
			return originalId;
		}
	}
}
