#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUv;
layout (location = 2) in float aLayer;
layout (location = 3) in float aLight;

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;

out vec2 vUv;
flat out float vLayer;
out float vLight;
out float vDistance;

void main() {
    vec4 viewPos = uView * uModel * vec4(aPos, 1.0);
    vUv = aUv;
    vLayer = aLayer;
    vLight = aLight;
    vDistance = length(viewPos.xyz);
    gl_Position = uProjection * viewPos;
}
