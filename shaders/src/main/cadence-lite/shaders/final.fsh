#version 330 compatibility
// Bloom (from colortex2's mip chain), tone and colour grade. With everything at its neutral value this is one read and one write.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
in vec2 texcoord;
uniform sampler2D colortex0;
/*
const int colortex0Format = R11F_G11F_B10F;
const int colortex2Format = R11F_G11F_B10F;
*/
#ifdef BLOOM
uniform sampler2D colortex2;
const bool colortex2MipmapEnabled = true;
#endif
layout(location = 0) out vec4 outColor;

void main() {
	vec3 c = texture(colortex0, texcoord).rgb;
#ifdef BLOOM
	vec3 b = textureLod(colortex2, texcoord, 2.0).rgb * 0.30 + textureLod(colortex2, texcoord, 3.0).rgb * 0.28
	       + textureLod(colortex2, texcoord, 4.0).rgb * 0.24 + textureLod(colortex2, texcoord, 5.5).rgb * 0.18;
	c += b * BLOOM_STRENGTH * 4.0;
#endif
#ifdef TONEMAP
	// keeps values below ~0.75 almost untouched (vanilla look), rolls highlights off instead of clipping
	vec3 hi = 0.75 + 0.25 * (1.0 - exp(-(c - 0.75) * 4.0));
	c = mix(c, hi, step(0.75, c));
#endif
	float l = luma(c);
	// vibrance: pushes muted colours more than already-saturated ones
	float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
	c = mix(vec3(l), c, 1.0 + (VIBRANCE - 1.0) * (1.6 - sat));
	c = (c - 0.5) * CONTRAST + 0.5;
	outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
