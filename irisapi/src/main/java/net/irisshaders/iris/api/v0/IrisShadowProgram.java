package net.irisshaders.iris.api.v0;

/** Iris API v0.4: the shadow-pass program a render pipeline is drawn with (IrisApi.assignPipelineShadow). */
public enum IrisShadowProgram {
	SHADOW,
	SHADOW_TERRAIN_CUTOUT,
	SHADOW_TRANSLUCENT,
	SHADOW_ENTITIES,
	SHADOW_LIGHTNING,
	SHADOW_BLOCK
}
