#version 330 core

in vec2 vUv;
flat in float vLayer;
in float vLight;
in float vDistance;

uniform sampler2DArray uTextures;
uniform float uDaylight;   // 昼夜の明るさ (0〜1)
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uAlpha;      // 水など半透明のもの用
uniform vec3 uTint;        // 他プレイヤーの服の色など

out vec4 FragColor;

void main() {
    vec4 tex = texture(uTextures, vec3(vUv, vLayer));
    vec3 color = tex.rgb * uTint * vLight * uDaylight;
    // 描画距離の端を霧で隠し、チャンクの出現・消滅を目立たなくする
    float fog = clamp((vDistance - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
    FragColor = vec4(mix(color, uFogColor, fog), uAlpha);
}
