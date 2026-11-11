/**
 * Port of Luma Home's ThemePalette.java 24-bin HSV weighted histogram
 * wallpaper color extraction and intensity-scaled tinting engine.
 */

export interface DerivedPalette {
  seedHex: string;
  accentHex: string;
  surfaceRgba: string;
  mutedHex: string;
  glowRgba: string;
  sceneTopHex: string;
  sceneMidHex: string;
  sceneNearHex: string;
}

export function extractSeedFromImageData(data: Uint8ClampedArray): number {
  const weights = new Float64Array(24);
  const red = new Float64Array(24);
  const green = new Float64Array(24);
  const blue = new Float64Array(24);
  const pixelCount = Math.floor(data.length / 4);

  for (let i = 0; i < data.length; i += 4) {
    const a = data[i + 3];
    if (a < 192) continue;
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const [h, s, v] = rgbToHsv(r, g, b);
    if (s < 0.1 || v < 0.08 || (v > 0.98 && s < 0.15)) continue;
    const bin = Math.min(23, Math.floor(h / 15));
    const weight = (0.2 + 0.8 * s) * (0.4 + 0.6 * Math.sqrt(v));
    weights[bin] += weight;
    red[bin] += r * weight;
    green[bin] += g * weight;
    blue[bin] += b * weight;
  }

  let best = 0;
  for (let i = 1; i < 24; i++) {
    if (weights[i] > weights[best]) best = i;
  }

  if (weights[best] >= Math.max(1, pixelCount * 0.008)) {
    const r = Math.round(red[best] / weights[best]);
    const g = Math.round(green[best] / weights[best]);
    const b = Math.round(blue[best] / weights[best]);
    return (r << 16) | (g << 8) | b;
  }

  // Fallback pass for muted or subtle images
  const weights2 = new Float64Array(24);
  const r2 = new Float64Array(24);
  const g2 = new Float64Array(24);
  const b2 = new Float64Array(24);
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] < 192) continue;
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const [h, s, v] = rgbToHsv(r, g, b);
    if (s < 0.04 || v < 0.06) continue;
    const bin = Math.min(23, Math.floor(h / 15));
    const weight = s * v;
    weights2[bin] += weight;
    r2[bin] += r * weight;
    g2[bin] += g * weight;
    b2[bin] += b * weight;
  }

  let best2 = 0;
  for (let i = 1; i < 24; i++) {
    if (weights2[i] > weights2[best2]) best2 = i;
  }
  if (weights2[best2] > 0) {
    const r = Math.round(r2[best2] / weights2[best2]);
    const g = Math.round(g2[best2] / weights2[best2]);
    const b = Math.round(b2[best2] / weights2[best2]);
    return (r << 16) | (g << 8) | b;
  }
  return 0x91aa7b;
}

export function derivePalette(seedRgb: number, intensityPct: number): DerivedPalette {
  const r = (seedRgb >> 16) & 255;
  const g = (seedRgb >> 8) & 255;
  const b = seedRgb & 255;
  const [hue, baseSat] = rgbToHsv(r, g, b);

  const factor = Math.max(0, Math.min(1, intensityPct / 100));

  // Accent ring & active tile tone
  const accSat = Math.min(0.85, Math.max(0.18, 0.22 + factor * 0.52 * Math.max(0.45, baseSat)));
  const accVal = Math.min(0.98, Math.max(0.84, 0.88 + factor * 0.08));
  const [ar, ag, ab] = hsvToRgb(hue, accSat, accVal);

  // Surface tint
  const surfSat = Math.max(0.14, factor * 0.58);
  const surfVal = 0.12 + factor * 0.09;
  const [sr, sg, sb] = hsvToRgb(hue, surfSat, surfVal);

  // Muted secondary text
  const mutSat = Math.min(0.32, Math.max(0.06, 0.08 + factor * 0.2));
  const [mr, mg, mb] = hsvToRgb(hue, mutSat, 0.82);

  // Scene gradient layers
  const [tR, tG, tB] = hsvToRgb(hue, Math.min(0.65, 0.25 + factor * 0.35), 0.36 + factor * 0.16);
  const [mR, mG, mB] = hsvToRgb((hue + 8) % 360, Math.min(0.58, 0.22 + factor * 0.3), 0.26 + factor * 0.12);
  const [nR, nG, nB] = hsvToRgb((hue + 350) % 360, Math.min(0.62, 0.28 + factor * 0.25), 0.13 + factor * 0.06);

  return {
    seedHex: toHex(r, g, b),
    accentHex: toHex(ar, ag, ab),
    surfaceRgba: `rgba(${sr}, ${sg}, ${sb}, 0.78)`,
    mutedHex: toHex(mr, mg, mb),
    glowRgba: `rgba(${ar}, ${ag}, ${ab}, ${(0.14 + factor * 0.24).toFixed(2)})`,
    sceneTopHex: toHex(tR, tG, tB),
    sceneMidHex: toHex(mR, mG, mB),
    sceneNearHex: toHex(nR, nG, nB),
  };
}

function rgbToHsv(r8: number, g8: number, b8: number): [number, number, number] {
  const r = r8 / 255;
  const g = g8 / 255;
  const b = b8 / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d > 0) {
    if (max === r) h = 60 * (((g - b) / d) % 6);
    else if (max === g) h = 60 * ((b - r) / d + 2);
    else h = 60 * ((r - g) / d + 4);
  }
  if (h < 0) h += 360;
  return [h, max === 0 ? 0 : d / max, max];
}

function hsvToRgb(hue: number, saturation: number, value: number): [number, number, number] {
  const c = value * saturation;
  const x = c * (1 - Math.abs(((hue / 60) % 2) - 1));
  const m = value - c;
  let r = 0;
  let g = 0;
  let b = 0;
  const sector = Math.floor(hue / 60) % 6;
  switch (sector) {
    case 0:
      r = c;
      g = x;
      break;
    case 1:
      r = x;
      g = c;
      break;
    case 2:
      g = c;
      b = x;
      break;
    case 3:
      g = x;
      b = c;
      break;
    case 4:
      r = x;
      b = c;
      break;
    default:
      r = c;
      b = x;
      break;
  }
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
}

function toHex(r: number, g: number, b: number): string {
  return (
    "#" +
    [r, g, b]
      .map((v) => Math.max(0, Math.min(255, v)).toString(16).padStart(2, "0"))
      .join("")
  );
}
