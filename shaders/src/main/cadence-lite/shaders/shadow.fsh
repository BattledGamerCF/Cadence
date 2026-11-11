#version 330 compatibility
in vec2 uv;
in float alpha;
in float skip;
uniform sampler2D gtexture;
void main() {
	if (skip > 0.5 || texture(gtexture, uv).a * alpha < 0.1) discard;
}
