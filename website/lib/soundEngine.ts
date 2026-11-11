/**
 * WebAudio port of Luma Home's UiSoundEngine.java ("Haptics-for-Ears").
 * Synthesizes physical-modeling 44.1kHz acoustic micro-samples with
 * spatial stereo panning (±28% living-room image) and melodic pitch-glide contours.
 */

export type SoundPackId = "wood" | "glass" | "pulse" | "dew";
export type SoundRole = "focus" | "select" | "back" | "blade" | "detent";

export const SOUND_PACKS: Array<{ id: SoundPackId; label: string; desc: string }> = [
  { id: "wood", label: "Warm Wood", desc: "Tactile marimba-like resonance" },
  { id: "glass", label: "Liquid Glass", desc: "Crisp harmonic crystal chime" },
  { id: "pulse", label: "Velvet Pulse", desc: "Soft low-end living-room thrum" },
  { id: "dew", label: "Dewdrop", desc: "Airy upward water-droplet inflection" },
];

const SR = 44100;

class LumaSoundEngine {
  private ctx: AudioContext | null = null;
  private buffers: Map<string, AudioBuffer> = new Map();
  private lastFocusMs = 0;
  private lastDetentMs = 0;
  private glideStep = 0;

  private ensureContext(): AudioContext | null {
    if (typeof window === "undefined") return null;
    if (!this.ctx) {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtx) return null;
      this.ctx = new AudioCtx({ sampleRate: SR });
    }
    if (this.ctx.state === "suspended") {
      this.ctx.resume().catch(() => {});
    }
    return this.ctx;
  }

  private getBuffer(ctx: AudioContext, pack: SoundPackId, role: SoundRole): AudioBuffer {
    const key = `${pack}_${role}`;
    const existing = this.buffers.get(key);
    if (existing) return existing;

    const samples = synthesize(pack, role);
    const audioBuffer = ctx.createBuffer(1, samples.length, SR);
    audioBuffer.copyToChannel(samples, 0);
    this.buffers.set(key, audioBuffer);
    return audioBuffer;
  }

  playFocus(pack: SoundPackId, enabled: boolean, pan01: number, direction: number) {
    if (!enabled) return;
    const now = performance.now();
    if (now - this.lastFocusMs < 34) return;

    if (now - this.lastFocusMs < 380) {
      this.glideStep = Math.max(-4, Math.min(4, this.glideStep + (direction >= 0 ? 1 : -1)));
    } else {
      this.glideStep = 0;
    }
    this.lastFocusMs = now;
    const rate = 1.0 + this.glideStep * 0.016;
    this.playInternal(pack, "focus", pan01, rate, 0.36);
  }

  playSelect(pack: SoundPackId, enabled: boolean, pan01 = 0.5) {
    if (!enabled) return;
    this.glideStep = 0;
    this.playInternal(pack, "select", pan01, 1.0, 0.44);
  }

  playBack(pack: SoundPackId, enabled: boolean) {
    if (!enabled) return;
    this.glideStep = 0;
    this.playInternal(pack, "back", 0.5, 1.0, 0.38);
  }

  playBlade(pack: SoundPackId, enabled: boolean) {
    if (!enabled) return;
    this.glideStep = 0;
    this.playInternal(pack, "blade", 0.72, 1.0, 0.4);
  }

  playDetent(pack: SoundPackId, enabled: boolean, stepPct: number) {
    if (!enabled) return;
    const now = performance.now();
    if (now - this.lastDetentMs < 26) return;
    this.lastDetentMs = now;
    const rate = 0.94 + Math.max(0, Math.min(1, stepPct / 100)) * 0.14;
    this.playInternal(pack, "detent", 0.65, rate, 0.3);
  }

  private playInternal(pack: SoundPackId, role: SoundRole, pan01: number, rate: number, maxGain: number) {
    const ctx = this.ensureContext();
    if (!ctx) return;

    try {
      const buffer = this.getBuffer(ctx, pack, role);
      const src = ctx.createBufferSource();
      src.buffer = buffer;
      src.playbackRate.value = Math.max(0.8, Math.min(1.25, rate));

      const gainNode = ctx.createGain();
      gainNode.gain.value = maxGain * 0.75;

      if (typeof ctx.createStereoPanner === "function") {
        const panner = ctx.createStereoPanner();
        const centered = (Math.max(0, Math.min(1, pan01)) - 0.5) * 2;
        panner.pan.value = Math.max(-0.45, Math.min(0.45, centered * 0.32));
        src.connect(gainNode);
        gainNode.connect(panner);
        panner.connect(ctx.destination);
      } else {
        src.connect(gainNode);
        gainNode.connect(ctx.destination);
      }

      src.start();
    } catch {
      // Ignore WebAudio autoplay restrictions before first interaction
    }
  }
}

function synthesize(pack: SoundPackId, role: SoundRole): Float32Array<ArrayBuffer> {
  const durMs =
    role === "select" ? 125 : role === "back" ? 92 : role === "blade" ? 108 : role === "detent" ? 26 : 46;
  const n = Math.floor((SR * durMs) / 1000);
  const buf = new Float32Array(new ArrayBuffer(n * 4));

  if (pack === "wood") synthWood(buf, role);
  else if (pack === "pulse") synthPulse(buf, role);
  else if (pack === "dew") synthDew(buf, role);
  else synthGlass(buf, role);

  let peak = 0.001;
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
  const scale = 0.8 / peak;

  const fadeIn = Math.min(Math.floor(n / 4), Math.floor(SR * 0.0018));
  const fadeOut = Math.min(Math.floor(n / 3), Math.floor(SR * 0.0045));

  for (let i = 0; i < n; i++) {
    let env = 1;
    if (i < fadeIn) {
      env = 0.5 * (1 - Math.cos((Math.PI * i) / fadeIn));
    } else if (i >= n - fadeOut) {
      const rem = n - 1 - i;
      env = 0.5 * (1 - Math.cos((Math.PI * rem) / fadeOut));
    }
    buf[i] = Math.max(-1, Math.min(1, buf[i] * scale * env));
  }
  return buf;
}

function synthGlass(b: Float32Array, role: SoundRole) {
  if (role === "focus") {
    addMode(b, 0, 540, 540, 0.0018, 0.0065, 0.42);
    addMode(b, 0, 1490, 1490, 0.0015, 0.0095, 0.52);
    addMode(b, 0, 3420, 3420, 0.001, 0.0038, 0.12);
  } else if (role === "select") {
    addMode(b, 0, 370, 370, 0.003, 0.032, 0.32);
    addMode(b, 0, 740, 740, 0.002, 0.028, 0.48);
    const delay = Math.floor(SR * 0.024);
    addMode(b, delay, 1110, 1110, 0.0035, 0.038, 0.52);
    addMode(b, delay, 2220, 2220, 0.0025, 0.018, 0.14);
  } else if (role === "back") {
    addMode(b, 0, 780, 740, 0.002, 0.018, 0.44);
    const delay = Math.floor(SR * 0.02);
    addMode(b, delay, 520, 495, 0.003, 0.028, 0.52);
  } else if (role === "blade") {
    addMode(b, 0, 440, 660, 0.006, 0.038, 0.46);
    addMode(b, Math.floor(SR * 0.012), 1320, 1360, 0.004, 0.024, 0.24);
  } else {
    addMode(b, 0, 640, 640, 0.001, 0.0035, 0.38);
    addMode(b, 0, 1880, 1880, 0.001, 0.0038, 0.52);
  }
}

function synthWood(b: Float32Array, role: SoundRole) {
  if (role === "focus") {
    addMode(b, 0, 170, 145, 0.0015, 0.0048, 0.34);
    addMode(b, 0, 385, 382, 0.0018, 0.0115, 0.68);
    addMode(b, 0, 1060, 1055, 0.0012, 0.0042, 0.22);
  } else if (role === "select") {
    addMode(b, 0, 330, 330, 0.002, 0.032, 0.55);
    addMode(b, 0, 909, 909, 0.0015, 0.01, 0.18);
    const delay = Math.floor(SR * 0.026);
    addMode(b, delay, 495, 495, 0.0025, 0.038, 0.58);
    addMode(b, delay, 1364, 1364, 0.0015, 0.011, 0.16);
  } else if (role === "back") {
    addMode(b, 0, 392, 385, 0.002, 0.02, 0.48);
    const delay = Math.floor(SR * 0.022);
    addMode(b, delay, 262, 255, 0.0025, 0.028, 0.58);
  } else if (role === "blade") {
    addMode(b, 0, 349, 349, 0.0025, 0.026, 0.46);
    addMode(b, Math.floor(SR * 0.02), 440, 440, 0.0025, 0.034, 0.52);
  } else {
    addMode(b, 0, 680, 660, 0.001, 0.0042, 0.64);
    addMode(b, 0, 1860, 1840, 0.001, 0.002, 0.18);
  }
}

function synthPulse(b: Float32Array, role: SoundRole) {
  if (role === "focus") {
    addMode(b, 0, 285, 108, 0.0018, 0.0135, 0.76);
    addMode(b, 0, 760, 520, 0.0012, 0.0034, 0.18);
  } else if (role === "select") {
    addMode(b, 0, 146, 146, 0.005, 0.045, 0.56);
    addMode(b, 0, 293, 293, 0.004, 0.038, 0.42);
    addMode(b, Math.floor(SR * 0.018), 440, 440, 0.005, 0.036, 0.34);
  } else if (role === "back") {
    addMode(b, 0, 215, 96, 0.0025, 0.03, 0.72);
  } else if (role === "blade") {
    addMode(b, 0, 145, 255, 0.006, 0.038, 0.58);
    addMode(b, Math.floor(SR * 0.014), 510, 520, 0.005, 0.026, 0.24);
  } else {
    addMode(b, 0, 320, 140, 0.001, 0.0046, 0.72);
  }
}

function synthDew(b: Float32Array, role: SoundRole) {
  if (role === "focus") {
    addMode(b, 0, 560, 890, 0.0022, 0.0115, 0.68);
  } else if (role === "select") {
    addMode(b, 0, 540, 790, 0.0025, 0.022, 0.48);
    addMode(b, Math.floor(SR * 0.028), 810, 1170, 0.0025, 0.03, 0.56);
  } else if (role === "back") {
    addMode(b, 0, 740, 465, 0.0025, 0.025, 0.62);
  } else if (role === "blade") {
    addMode(b, 0, 490, 740, 0.004, 0.032, 0.52);
    addMode(b, Math.floor(SR * 0.016), 980, 1080, 0.004, 0.024, 0.22);
  } else {
    addMode(b, 0, 790, 1080, 0.0012, 0.0042, 0.62);
  }
}

function addMode(
  out: Float32Array,
  startSample: number,
  fStart: number,
  fEnd: number,
  attackSec: number,
  tauSec: number,
  amp: number
) {
  let phase = 0;
  const len = out.length - startSample;
  if (len <= 0) return;
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const glide = 1 - Math.exp(-t / 0.014);
    const freq = fStart + (fEnd - fStart) * glide;
    phase += (2 * Math.PI * freq) / SR;
    const att = attackSec <= 0 ? 1 : Math.min(1, t / attackSec);
    const dec = Math.exp(-t / tauSec);
    out[startSample + i] += Math.sin(phase) * att * dec * amp;
  }
}

export const soundEngine = new LumaSoundEngine();
