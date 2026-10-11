package net.irisshaders.iris.gl.blending;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pack blend overrides (blend.&lt;program&gt; and blend.&lt;program&gt;.&lt;buffer&gt; directives).
 * On Metal blending is part of the pipeline object, so this only records the active overrides; MetalProgram builds
 * (and caches) a pipeline variant per {@link #key()}.
 */
public class BlendModeStorage {
	/** Overrides for one draw. override == null with overridden == true means "blending off". */
	public record State(boolean overridden, BlendMode override, Map<Integer, BlendMode> perBuffer) {
		static final State NONE = new State(false, null, Map.of());
	}

	private static boolean overridden;
	private static BlendMode override;
	private static final TreeMap<Integer, BlendMode> perBuffer = new TreeMap<>();
	private static State key = State.NONE;

	public static boolean isBlendLocked() {
		return overridden || !perBuffer.isEmpty();
	}

	public static boolean isBlendUnknown() {
		return !perBuffer.isEmpty();
	}

	/** null = disable blending for this program. */
	public static void overrideBlend(BlendMode mode) {
		overridden = true;
		override = mode;
		key = null;
	}

	/** null = disable blending on that draw buffer. */
	public static void overrideBufferBlend(int index, BlendMode mode) {
		perBuffer.put(index, mode);
		key = null;
	}

	public static void restoreBlend() {
		overridden = false;
		override = null;
		perBuffer.clear();
		key = State.NONE;
	}

	/** Immutable snapshot of the active overrides; equal snapshots reuse the same Metal pipeline. */
	public static State key() {
		if (key == null) {
			key = new State(overridden, override, java.util.Collections.unmodifiableMap(new TreeMap<>(perBuffer)));
		}
		return key;
	}

	// GL-era hooks (deferred state while locked); blending is per-pipeline on Metal, so nothing to defer.
	public static void deferBlendModeToggle(boolean enabled) {
	}

	public static void deferBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
	}
}
