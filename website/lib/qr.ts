/**
 * Self-contained ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level M, Versions 1..6).
 * Ported directly from Luma Home's QrCode.java for zero-dependency crisp SVG rendering.
 */

const VERSION_M: Array<[number, number, number] | null> = [
  null,
  [26, 10, 1], // V1: max 14 UTF-8 bytes
  [44, 16, 1], // V2: max 26 UTF-8 bytes
  [70, 26, 1], // V3: max 42 UTF-8 bytes
  [100, 18, 2], // V4: max 62 UTF-8 bytes
  [134, 24, 2], // V5: max 84 UTF-8 bytes
  [172, 16, 4], // V6: max 106 UTF-8 bytes
];

export function encodeQrMatrix(text: string): boolean[][] {
  const payload = new TextEncoder().encode(text);
  let ver = 1;
  while (ver <= 6) {
    const v = VERSION_M[ver]!;
    const dataCap = v[0] - v[1] * v[2];
    if (payload.length + 2 <= dataCap) break;
    ver++;
  }
  if (ver > 6) ver = 6;

  const [totalCw, ecPerBlock, numBlocks] = VERSION_M[ver]!;
  const totalDataCw = totalCw - ecPerBlock * numBlocks;

  const dataCw = new Uint8Array(totalDataCw);
  const bitLen = totalDataCw * 8;
  let bitPos = 0;
  bitPos = appendBits(dataCw, bitPos, 0b0100, 4);
  bitPos = appendBits(dataCw, bitPos, payload.length, 8);
  for (let i = 0; i < payload.length; i++) {
    bitPos = appendBits(dataCw, bitPos, payload[i] & 0xff, 8);
  }
  const term = Math.min(4, bitLen - bitPos);
  bitPos = appendBits(dataCw, bitPos, 0, term);
  while ((bitPos & 7) !== 0) bitPos++;
  let padByte = 0xec;
  for (let i = bitPos >>> 3; i < totalDataCw; i++) {
    dataCw[i] = padByte;
    padByte = padByte === 0xec ? 0x11 : 0xec;
  }

  const blockDataLen = Math.floor(totalDataCw / numBlocks);
  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  const rsGen = rsGeneratorPoly(ecPerBlock);

  for (let b = 0; b < numBlocks; b++) {
    const block = dataCw.slice(b * blockDataLen, (b + 1) * blockDataLen);
    dataBlocks.push(block);
    ecBlocks.push(rsComputeRemainder(block, rsGen));
  }

  const allCw = new Uint8Array(totalCw);
  let idx = 0;
  for (let i = 0; i < blockDataLen; i++) {
    for (let b = 0; b < numBlocks; b++) allCw[idx++] = dataBlocks[b][i];
  }
  for (let i = 0; i < ecPerBlock; i++) {
    for (let b = 0; b < numBlocks; b++) allCw[idx++] = ecBlocks[b][i];
  }

  const size = ver * 4 + 17;
  const modules: boolean[][] = Array.from({ length: size }, () => Array(size).fill(false));
  const isFunc: boolean[][] = Array.from({ length: size }, () => Array(size).fill(false));

  drawFinderPattern(modules, isFunc, 3, 3);
  drawFinderPattern(modules, isFunc, size - 4, 3);
  drawFinderPattern(modules, isFunc, 3, size - 4);

  for (let i = 8; i < size - 8; i++) {
    const dark = i % 2 === 0;
    setFunc(modules, isFunc, 6, i, dark);
    setFunc(modules, isFunc, i, 6, dark);
  }

  if (ver >= 2) {
    drawAlignmentPattern(modules, isFunc, size - 7, size - 7);
  }

  drawFormatBits(modules, isFunc, 0);

  let bitIdx = 0;
  const totalBits = allCw.length * 8;
  let upward = true;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let v = 0; v < size; v++) {
      const r = upward ? size - 1 - v : v;
      for (let j = 0; j < 2; j++) {
        const c = right - j;
        if (!isFunc[r][c]) {
          let dark = false;
          if (bitIdx < totalBits) {
            dark = ((allCw[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) !== 0;
            bitIdx++;
          }
          modules[r][c] = dark;
        }
      }
    }
    upward = !upward;
  }

  let bestMask = 0;
  let bestPenalty = Number.MAX_SAFE_INTEGER;
  for (let mask = 0; mask < 8; mask++) {
    const trial = modules.map((row) => [...row]);
    applyMask(trial, isFunc, mask);
    drawFormatBits(trial, null, mask);
    const pen = penaltyScore(trial);
    if (pen < bestPenalty) {
      bestPenalty = pen;
      bestMask = mask;
    }
  }
  applyMask(modules, isFunc, bestMask);
  drawFormatBits(modules, isFunc, bestMask);
  return modules;
}

function appendBits(out: Uint8Array, bitPos: number, val: number, len: number): number {
  for (let i = len - 1; i >= 0; i--) {
    if (((val >>> i) & 1) !== 0) {
      out[bitPos >>> 3] |= 1 << (7 - (bitPos & 7));
    }
    bitPos++;
  }
  return bitPos;
}

function setFunc(m: boolean[][], fn: boolean[][] | null, r: number, c: number, dark: boolean) {
  m[r][c] = dark;
  if (fn) fn[r][c] = true;
}

function drawFinderPattern(m: boolean[][], fn: boolean[][], cr: number, cc: number) {
  const size = m.length;
  for (let dy = -4; dy <= 4; dy++) {
    for (let dx = -4; dx <= 4; dx++) {
      const r = cr + dy;
      const c = cc + dx;
      if (r >= 0 && r < size && c >= 0 && c < size) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        setFunc(m, fn, r, c, dist !== 2 && dist !== 4);
      }
    }
  }
}

function drawAlignmentPattern(m: boolean[][], fn: boolean[][], cr: number, cc: number) {
  for (let dy = -2; dy <= 2; dy++) {
    for (let dx = -2; dx <= 2; dx++) {
      const dist = Math.max(Math.abs(dx), Math.abs(dy));
      setFunc(m, fn, cr + dy, cc + dx, dist !== 1);
    }
  }
}

function drawFormatBits(m: boolean[][], fn: boolean[][] | null, mask: number) {
  const size = m.length;
  const data5 = (0b00 << 3) | mask;
  let rem = data5 << 10;
  for (let i = 14; i >= 10; i--) {
    if (((rem >>> i) & 1) !== 0) rem ^= 0x537 << (i - 10);
  }
  const bits = ((data5 << 10) | rem) ^ 0x5412;
  const bit = (x: number, i: number) => ((x >>> i) & 1) !== 0;

  for (let i = 0; i <= 5; i++) setFunc(m, fn, i, 8, bit(bits, i));
  setFunc(m, fn, 7, 8, bit(bits, 6));
  setFunc(m, fn, 8, 8, bit(bits, 7));
  setFunc(m, fn, 8, 7, bit(bits, 8));
  for (let i = 9; i < 15; i++) setFunc(m, fn, 8, 14 - i, bit(bits, i));

  for (let i = 0; i < 8; i++) setFunc(m, fn, 8, size - 1 - i, bit(bits, i));
  for (let i = 8; i < 15; i++) setFunc(m, fn, size - 15 + i, 8, bit(bits, i));
  setFunc(m, fn, size - 8, 8, true);
}

function applyMask(m: boolean[][], fn: boolean[][], mask: number) {
  const n = m.length;
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (fn[r][c]) continue;
      let invert = false;
      switch (mask) {
        case 0:
          invert = (r + c) % 2 === 0;
          break;
        case 1:
          invert = r % 2 === 0;
          break;
        case 2:
          invert = c % 3 === 0;
          break;
        case 3:
          invert = (r + c) % 3 === 0;
          break;
        case 4:
          invert = (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
          break;
        case 5:
          invert = ((r * c) % 2) + ((r * c) % 3) === 0;
          break;
        case 6:
          invert = (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
          break;
        default:
          invert = (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
          break;
      }
      if (invert) m[r][c] = !m[r][c];
    }
  }
}

function penaltyScore(m: boolean[][]): number {
  const n = m.length;
  let score = 0;
  for (let r = 0; r < n; r++) {
    let runH = 1;
    let runV = 1;
    for (let c = 1; c < n; c++) {
      if (m[r][c] === m[r][c - 1]) {
        runH++;
        if (runH === 5) score += 3;
        else if (runH > 5) score++;
      } else runH = 1;

      if (m[c][r] === m[c - 1][r]) {
        runV++;
        if (runV === 5) score += 3;
        else if (runV > 5) score++;
      } else runV = 1;
    }
  }
  for (let r = 0; r < n - 1; r++) {
    for (let c = 0; c < n - 1; c++) {
      const v = m[r][c];
      if (v === m[r][c + 1] && v === m[r + 1][c] && v === m[r + 1][c + 1]) score += 3;
    }
  }
  let dark = 0;
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (m[r][c]) dark++;
  const total = n * n;
  const k = Math.floor(Math.abs(dark * 20 - total * 10) / total);
  score += k * 10;
  return score;
}

function rsGeneratorPoly(degree: number): Uint8Array {
  const poly = new Uint8Array(degree);
  poly[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      poly[j] = gfMul(poly[j] & 0xff, root) ^ (j + 1 < degree ? poly[j + 1] & 0xff : 0);
    }
    root = gfMul(root, 0x02);
  }
  return poly;
}

function rsComputeRemainder(data: Uint8Array, gen: Uint8Array): Uint8Array {
  const rem = new Uint8Array(gen.length);
  for (let i = 0; i < data.length; i++) {
    const factor = (data[i] ^ rem[0]) & 0xff;
    rem.copyWithin(0, 1);
    rem[rem.length - 1] = 0;
    for (let j = 0; j < gen.length; j++) {
      rem[j] ^= gfMul(gen[j] & 0xff, factor);
    }
  }
  return rem;
}

function gfMul(x: number, y: number): number {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}
