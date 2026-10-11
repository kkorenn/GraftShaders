package net.irisshaders.iris.metal;

import dev.graft.metal.MetalCommands;
import dev.graft.v1_21_11.MetalGpuDevice;

/** Dispatches Iris compute programs (ComputeProgram) on Metal's compute encoder. */
public final class GraftShadersCompute {
	private GraftShadersCompute() {
	}

	private static MetalProgram bindActive() {
		MetalProgram program = GraftShadersState.program;
		if (program == null || program.linked.computeMsl() == null) {
			throw new IllegalStateException("Graft Shaders: dispatch without an active compute program");
		}
		MetalCommands commands = MetalGpuDevice.current().commands();
		commands.setComputePipeline(program.computePipeline());
		BINDER.commands = commands;
		BINDER.kinds = program.linked.kinds();
		GraftShadersDraw.bindIrisResources(program, BINDER, null);
		return program;
	}

	private static final ComputeBinder BINDER = new ComputeBinder();

	private static final class ComputeBinder implements GraftShadersDraw.Binder {
		MetalCommands commands;
		java.util.List<dev.graft.metal.ShaderTranslator.Kind> kinds;

		@Override
		public void buffer(int slot, long buffer, long offset) {
			commands.setComputeBuffer(dev.graft.metal.ShaderTranslator.bufferSlot(kinds, slot), buffer, offset);
		}

		@Override
		public void texture(int slot, long texture, long sampler) {
			commands.setComputeTexture(slot, texture, dev.graft.metal.ShaderTranslator.samplerSlot(kinds, slot), sampler);
		}

		@Override
		public void bytes(int slot, java.nio.ByteBuffer data) {
			commands.setComputeBytes(dev.graft.metal.ShaderTranslator.bufferSlot(kinds, slot), data);
		}

		@Override
		public void sampler(int index, long sampler) {
			commands.setComputeSampler(index, sampler);
		}
	}

	public static void dispatch(int groupsX, int groupsY, int groupsZ) {
		if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
			return;
		}
		MetalProgram program = bindActive();
		int[] local = program.linked.localSize();
		MetalGpuDevice.current().commands().dispatch(groupsX, groupsY, groupsZ, local[0], local[1], local[2]);
	}

	/** Indirect args: three uint32 group counts at offset in the MetalBuffers buffer Iris bound as the dispatch buffer. */
	public static void dispatchIndirect(long offset) {
		MetalBuffers.Buffer args = MetalBuffers.get(GraftShadersState.dispatchIndirectBuffer);
		if (args == null) {
			throw new IllegalStateException("Graft Shaders: indirect dispatch with no GL_DISPATCH_INDIRECT_BUFFER bound");
		}
		MetalProgram program = bindActive();
		int[] local = program.linked.localSize();
		MetalGpuDevice.current().commands().dispatchIndirect(args.mtl(), offset, local[0], local[1], local[2]);
	}
}
