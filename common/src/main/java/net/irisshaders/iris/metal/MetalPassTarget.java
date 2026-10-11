package net.irisshaders.iris.metal;

/** Which Graft Shaders framebuffer a render pass currently draws into (0 = the attachments it was created with). */
public interface MetalPassTarget {
	int graftshaders$target();

	void graftshaders$setTarget(int framebuffer);
}
