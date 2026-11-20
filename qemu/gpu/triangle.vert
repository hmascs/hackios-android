#version 450
layout(push_constant) uniform Params {
    vec4 xy01;
    vec4 xy2;
    vec4 rgba;
} params;
layout(location = 0) flat out vec4 color;
void main() {
    vec2 xy[3] = vec2[](params.xy01.xy, params.xy01.zw, params.xy2.xy);
    gl_Position = vec4(xy[gl_VertexIndex], 0.0, 1.0);
    color = params.rgba;
}
