#version 330 compatibility
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
out vec2 uv;
out float alpha;
out float skip;
in vec4 mc_Entity;
in vec2 mc_midTexCoord;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform vec3 cameraPosition;
void main() {
	uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	alpha = gl_Color.a;
	float id = mc_Entity.x;
	skip = id == ID_WATER ? 1.0 : 0.0; // water casts no shadow
	vec4 vp = gl_ModelViewMatrix * gl_Vertex;
#ifdef WAVING
	if (id >= ID_PLANT && id <= ID_TALL_UPPER) {
		vec3 pp = (shadowModelViewInverse * vp).xyz;
		vec2 lm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
		pp += wind(pp + cameraPosition, id, gl_MultiTexCoord0.t < mc_midTexCoord.t, smoothstep(0.3, 0.9, lm.y));
		vp = shadowModelView * vec4(pp, 1.0);
	}
#endif
	gl_Position = gl_ProjectionMatrix * vp;
	gl_Position.xyz = distortShadow(gl_Position.xyz);
}
