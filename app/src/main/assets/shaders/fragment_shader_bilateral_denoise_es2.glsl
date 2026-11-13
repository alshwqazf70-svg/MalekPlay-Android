#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uStrength;
varying vec2 vTexSamplingCoord;

float luminance(vec3 color) {
  return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
  vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 sum = center.rgb;
  float totalWeight = 1.0;

  vec2 offsets[4];
  offsets[0] = vec2(uTexelSize.x, 0.0);
  offsets[1] = vec2(-uTexelSize.x, 0.0);
  offsets[2] = vec2(0.0, uTexelSize.y);
  offsets[3] = vec2(0.0, -uTexelSize.y);

  float centerLuma = luminance(center.rgb);
  for (int i = 0; i < 4; i++) {
    vec3 sampleColor = texture2D(uTexSampler, vTexSamplingCoord + offsets[i]).rgb;
    float difference = luminance(sampleColor) - centerLuma;
    float edgeWeight = exp(-difference * difference * 96.0);
    float weight = edgeWeight * 0.65;
    sum += sampleColor * weight;
    totalWeight += weight;
  }

  vec3 filtered = sum / max(totalWeight, 0.0001);
  gl_FragColor = vec4(mix(center.rgb, filtered, uStrength), center.a);
}
