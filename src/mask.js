export function thresholdMask(values, threshold = 0.5) {
  const cutoff = threshold <= 1 ? threshold * 255 : threshold;
  return Uint8Array.from(values, (value) => (value >= cutoff ? 255 : 0));
}

/** Separable dilation expands a binary mask so blur overlaps the subject edge. */
export function dilateMask(mask, width, height, radius = 2) {
  if (width < 1 || height < 1 || mask.length !== width * height) {
    throw new RangeError("Mask dimensions do not match its data.");
  }
  const r = Math.max(0, Math.floor(radius));
  if (r === 0) return Uint8Array.from(mask, (v) => (v ? 255 : 0));

  const horizontal = new Uint8Array(mask.length);
  const output = new Uint8Array(mask.length);
  for (let y = 0; y < height; y += 1) {
    const row = y * width;
    for (let x = 0; x < width; x += 1) {
      let found = false;
      for (let xx = Math.max(0, x - r); xx <= Math.min(width - 1, x + r); xx += 1) {
        if (mask[row + xx] > 0) { found = true; break; }
      }
      horizontal[row + x] = found ? 255 : 0;
    }
  }
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      let found = false;
      for (let yy = Math.max(0, y - r); yy <= Math.min(height - 1, y + r); yy += 1) {
        if (horizontal[yy * width + x] > 0) { found = true; break; }
      }
      output[y * width + x] = found ? 255 : 0;
    }
  }
  return output;
}

export function unionMasks(masks, width, height) {
  const result = new Uint8Array(width * height);
  for (const mask of masks) {
    if (!mask || mask.length !== result.length) continue;
    for (let i = 0; i < result.length; i += 1) {
      if (mask[i] > result[i]) result[i] = mask[i];
    }
  }
  return result;
}