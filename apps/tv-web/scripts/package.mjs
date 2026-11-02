// Stages dist/ plus a platform manifest into build/<platform>/ and prints the packaging command.
// Usage: node scripts/package.mjs tizen|webos   (run `npm run build` first)
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const platform = process.argv[2];
const manifest = { tizen: "config.xml", webos: "appinfo.json" }[platform];
if (!manifest) { console.error("usage: package.mjs tizen|webos"); process.exit(2); }
if (!existsSync(join(root, "dist/index.html"))) { console.error("run `npm run build` first"); process.exit(1); }

const out = join(root, "build", platform);
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
cpSync(join(root, "dist"), out, { recursive: true });
cpSync(join(root, "platforms", platform, manifest), join(out, manifest));
// The licence and notices travel inside every package, as in the Android apps (build.gradle.kts `copyLegalAssets`).
mkdirSync(join(out, "legal"), { recursive: true });
for (const name of ["LICENSE", "NOTICE"]) {
  const file = join(root, "../..", name);
  if (!existsSync(file)) { console.error(`${file} is missing; the package must carry the licence and notices`); process.exit(1); }
  cpSync(file, join(out, "legal", name));
}
const icon = join(root, "platforms/icon.png");
if (existsSync(icon)) cpSync(icon, join(out, "icon.png"));
else writeFileSync(join(out, "icon.png"), Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64"));

console.log(`Staged ${out}`);
console.log(platform === "tizen"
  ? `Package:  tizen package -t wgt -s <certificate profile> -- ${out}\nInstall:  tizen install -n Cablegram.wgt -t <target>`
  : `Package:  ares-package ${out} -o build\nInstall:  ares-install -d <device> build/app.cablegram.tv_0.4.0_all.ipk && ares-launch -d <device> app.cablegram.tv`);
