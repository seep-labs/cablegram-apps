import { describe, expect, it } from "vitest";
import bigInt from "big-integer";
import type { BigInteger } from "big-integer";
import { fastModExp } from "../src/telegram-modpow";

/** GramJS baseline modExp implementation from telegram/Helpers.ts */
function baselineModExp(a: BigInteger, b: BigInteger, n: BigInteger): BigInteger {
  a = a.remainder(n);
  let result = bigInt.one;
  let x = a;
  while (b.greater(bigInt.zero)) {
    const leastSignificantBit = b.remainder(bigInt(2));
    b = b.divide(bigInt(2));
    if (leastSignificantBit.eq(bigInt.one)) {
      result = result.multiply(x);
      result = result.remainder(n);
    }
    x = x.multiply(x);
    x = x.remainder(n);
  }
  return result;
}

const RFC3526_P_HEX = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF";
const B_HEX = "580662e08611816e832910605b08d4029de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68";
const GA_HEX = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68";

describe("fastModExp", () => {
  it("matches baseline for small numbers and edge cases", () => {
    const cases: [BigInteger, BigInteger, BigInteger][] = [
      [bigInt(3), bigInt(0), bigInt(17)],
      [bigInt(3), bigInt(1), bigInt(17)],
      [bigInt(3), bigInt(5), bigInt(17)],
      [bigInt(10), bigInt(20), bigInt(31)],
      [bigInt(65537), bigInt(17), bigInt(104729)],
      [bigInt(0), bigInt(15), bigInt(31)],
      [bigInt("12345678901234567890"), bigInt("9876543210"), bigInt("1000000007")],
    ];

    for (const [a, b, n] of cases) {
      const exp = baselineModExp(a, b, n);
      const act = fastModExp(a, b, n);
      expect(act.toString()).toBe(exp.toString());
    }
  });

  it("matches baseline for 2048-bit Diffie-Hellman parameters", () => {
    const p = bigInt(RFC3526_P_HEX, 16);
    const b = bigInt(B_HEX, 16);
    const ga = bigInt(GA_HEX, 16);
    const g = bigInt(3);

    const gbExpected = baselineModExp(g, b, p);
    const gbActual = fastModExp(g, b, p);
    expect(gbActual.toString(16)).toBe(gbExpected.toString(16));

    const gabExpected = baselineModExp(ga, b, p);
    const gabActual = fastModExp(ga, b, p);
    expect(gabActual.toString(16)).toBe(gabExpected.toString(16));
  });

  it("operates identically when forcing pure JS Montgomery reduction (Chromium 63 mode)", () => {
    const p = bigInt(RFC3526_P_HEX, 16);
    const b = bigInt(B_HEX, 16);
    const ga = bigInt(GA_HEX, 16);
    const g = bigInt(3);

    const gb = fastModExp(g, b, p, true);
    expect(gb.toString(16).slice(0, 32)).toBe("d2a0bc8b052ae42ae24c0434ee50aec2");

    const gab = fastModExp(ga, b, p, true);
    expect(gab.toString(16).slice(0, 32)).toBe("20df9ce37bd55b88c34f3123a7d3e337");

    const gbNative = fastModExp(g, b, p, false);
    expect(gb.toString(16)).toBe(gbNative.toString(16));

    const gabNative = fastModExp(ga, b, p, false);
    expect(gab.toString(16)).toBe(gabNative.toString(16));
  });
});
