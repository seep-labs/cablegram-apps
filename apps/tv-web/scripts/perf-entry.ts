// Bundled by perf-throttle.mjs and run in Chrome under CPU throttling.
import "../src/telegram-env";
import bigInt from "big-integer";
import { helpers } from "telegram";
import { IGE } from "telegram/crypto/IGE";
import { createPlayer, HttpRangeSource } from "tv-media";
import { fastModExp } from "../src/telegram-modpow";

(helpers as Record<string, unknown>)["modExp"] = fastModExp;

declare global { interface Window { perf: unknown } }

const P_HEX = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF";
const B_HEX = "580662e08611816e832910605b08d4029de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68";
const GA_HEX = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68";

window.perf = {
  /** AES-256-IGE decryption, the cipher GramJS runs on every downloaded byte. Returns MB/s. */
  ige(mb: number): number {
    const ige = new IGE(Buffer.alloc(32, 1), Buffer.alloc(32, 2));
    const data = Buffer.alloc(mb * 1048576, 7);
    const t = performance.now();
    ige.decryptIge(data);
    return mb / ((performance.now() - t) / 1000);
  },
  /** Two 2048-bit modular exponentiations (g^b and g_a^b mod dhPrime), matching GramJS's MTProto auth-key exchange. Returns elapsed seconds. */
  dh(): number {
    const p = bigInt(P_HEX, 16);
    const b = bigInt(B_HEX, 16);
    const ga = bigInt(GA_HEX, 16);
    const g = bigInt(3);
    const t0 = performance.now();
    helpers.modExp(g, b, p);
    helpers.modExp(ga, b, p);
    return (performance.now() - t0) / 1000;
  },
  /** Demux + remux + append until [seconds] of media are buffered. Nothing is played, so decoding is not measured. */
  async pump(url: string, seconds: number): Promise<{ wallSec: number; mediaSec: number; mb: number }> {
    const video = document.createElement("video");
    const source = new HttpRangeSource(url);
    const t0 = performance.now();
    const player = createPlayer(video, source, { aheadSec: seconds, batchBytes: 1048576 });
    await player.ready;
    for (;;) {
      const b = video.buffered;
      if (b.length && b.end(b.length - 1) >= seconds) break;
      if (performance.now() - t0 > 120000) break;
      await new Promise((r) => setTimeout(r, 25));
    }
    const wallSec = (performance.now() - t0) / 1000;
    const b = video.buffered;
    const mediaSec = b.length ? b.end(b.length - 1) : 0;
    player.destroy();
    return { wallSec, mediaSec, mb: (mediaSec * 74) / 30 };
  },
};
