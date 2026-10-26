package net.irisshaders.iris.api.v0;

/** Iris API v0.3: the shader-pack program a render pipeline is drawn with (IrisApi.assignPipeline). */
public enum IrisProgram {
	BASIC,
	TEXTURED,
	TERRAIN,
	TERRAIN_SOLID,
	TERRAIN_CUTOUT,
	TRANSLUCENT,
	SKY_BASIC,
	SKY_TEXTURED,
	ARMOR_GLINT,
	ENTITIES,
	ENTITIES_TRANSLUCENT,
	CLOUDS,
	BLOCK,
	BLOCK_TRANSLUCENT,
	HAND,
	HAND_TRANSLUCENT,
	PARTICLES,
	PARTICLES_TRANSLUCENT,
	EMISSIVE_ENTITIES,
	BEACON_BEAM,
	LINES
}
