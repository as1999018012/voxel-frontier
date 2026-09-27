#version 330 core

in vec2 vUv;
flat in float vLayer;
in vec4 vColor;

uniform sampler2DArray uTextures;

out vec4 FragColor;

void main() {
    if (vLayer < 0.0) {
        FragColor = vColor; // 単色の四角
    } else {
        FragColor = texture(uTextures, vec3(vUv, vLayer)) * vColor;
    }
}
