import bigInt from "big-integer";
import type { BigInteger } from "big-integer";
import { BigInteger as JsbnBigInteger } from "jsbn";

/**
 * 4-bit windowed modular exponentiation using native BigInt.
 * Only called when `typeof BigInt === "function"`.
 */
function nativeWindowedModPow(a: bigint, b: bigint, n: bigint): bigint {
  const zero = BigInt(0);
  const one = BigInt(1);
  if (n === one) return zero;
  let r = one;
  let base = a % n;
  if (base < zero) base += n;
  const table = new Array(16);
  table[0] = one;
  table[1] = base;
  for (let i = 2; i < 16; i++) {
    table[i] = (table[i - 1] * base) % n;
  }
  const hex = b.toString(16);
  for (let i = 0; i < hex.length; i++) {
    const digit = parseInt(hex[i], 16);
    r = (r * r) % n;
    r = (r * r) % n;
    r = (r * r) % n;
    r = (r * r) % n;
    if (digit > 0) {
      r = (r * table[digit]) % n;
    }
  }
  return r;
}

/**
 * Converts a big-integer instance into a native bigint primitive.
 */
function toNative(bi: BigInteger): bigint {
  const val = (bi as unknown as { value: unknown }).value;
  if (typeof val === "bigint") return bi.isNegative() ? -val : val;
  return BigInt(bi.toString());
}

/**
 * Computes (a ^ b) mod n.
 *
 * GramJS's built-in Helpers.modExp uses naive square-and-multiply with big-integer division,
 * taking ~900 ms for the two 2048-bit MTProto DH exponentiations on a fast core, and ~19 s
 * on a 2019 ARM Samsung TV (Chromium 63) where native BigInt is unavailable.
 *
 * Here we accelerate it:
 * 1. If native BigInt is available (Chrome 67+, modern browsers): 4-bit windowed exponentiation (~18 ms).
 * 2. If native BigInt is unavailable (Chromium 63 on 2019 Tizen 5.0 TVs): jsbn Montgomery reduction
 *    with sliding-window exponentiation (~90 ms, an ~10x speedup).
 *
 * Returns a big-integer instance with identical value.
 */
export function fastModExp(a: BigInteger, b: BigInteger, n: BigInteger, forcePureJs = false): BigInteger {
  if (n.isZero()) throw new Error("Cannot take modPow with modulus 0");
  if (n.eq(bigInt.one)) return bigInt.zero;
  if (b.isZero()) return bigInt.one;
  if (b.eq(bigInt.one)) return a.mod(n);
  if (a.isZero()) return bigInt.zero;

  // 1. Native BigInt path when supported (and not forcing pure JS)
  if (!forcePureJs && typeof BigInt === "function") {
    try {
      const an = toNative(a);
      const bn = toNative(b);
      const nn = toNative(n);
      const rn = nativeWindowedModPow(an, bn, nn);
      return bigInt(rn.toString());
    } catch {
      // Fall through to jsbn if native BigInt fails
    }
  }

  // 2. Pure JavaScript Montgomery reduction via jsbn for older engines (Chromium 63)
  try {
    const aBN = new JsbnBigInteger(a.toString(16), 16);
    const bBN = new JsbnBigInteger(b.toString(16), 16);
    const nBN = new JsbnBigInteger(n.toString(16), 16);
    const rBN = aBN.modPow(bBN, nBN);
    return bigInt(rBN.toString(16), 16);
  } catch {
    // 3. Fallback to big-integer modPow in the improbable case jsbn throws
    return a.modPow(b, n);
  }
}
