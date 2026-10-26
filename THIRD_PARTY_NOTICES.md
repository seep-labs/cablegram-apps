# Third-party notices

Cablegram is distributed under the GNU General Public License, version 3 ([LICENSE](LICENSE)). It also
contains or depends on the components below. Their copyrights remain with their authors, and their licence
terms continue to apply. [NOTICE](NOTICE) is the short summary of this file.

The apps ship different components, so each has its own section. Libraries that several apps use are
listed once, under "Shared".

Versions come from the build files on `main`: `apps/android-phone/build.gradle.kts`,
`apps/android-tv/build.gradle.kts`, `apps/ios-phone/project.yml`, and the `package.json` and
`package-lock.json` files under `apps/tv-web`, `apps/web-remote` and `libs/tv-media`. Licences of Gradle
artifacts are the ones declared in their published POM files. Check them again when you change a
dependency.

## Android phone (`app.cablegram.phone`)

| Component | Version | Licence | Upstream |
| --- | --- | --- | --- |
| Compose BOM (Compose UI, Material 3, Material icons extended) | 2025.03.00 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose> |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Activity Compose | 1.10.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Lifecycle (runtime-compose, viewmodel-compose, service) | 2.8.7 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Browser | 1.9.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Credentials, credentials-play-services-auth | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX MediaRouter | 1.7.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| Google Identity `googleid` | 1.1.1 | Android Software Development Kit License | <https://developer.android.com/studio/terms.html> |
| Google Cast framework (`play-services-cast-framework`) | 22.3.1 | Android Software Development Kit License | <https://developers.google.com/cast> |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | <https://github.com/journeyapps/zxing-android-embedded> |
| NanoHTTPD | 2.3.1 | BSD-3-Clause | <https://github.com/NanoHttpd/nanohttpd> |

## Android TV (`app.cablegram`)

| Component | Version | Licence | Upstream |
| --- | --- | --- | --- |
| Compose BOM (Compose UI) | 2025.03.00 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose> |
| AndroidX TV Material | 1.0.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/tv> |
| AndroidX Activity Compose | 1.10.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Lifecycle (process, runtime-compose, viewmodel-compose) | 2.8.7 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Media | 1.7.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| LibVLC for Android (`libvlc-all`) | 3.6.5 | LGPL-2.1-or-later | <https://code.videolan.org/videolan/vlc-android> |
| ZXing core | 3.5.3 | Apache-2.0 | <https://github.com/zxing/zxing> |
| Coil 3 (compose, network-okhttp, gif) | 3.2.0 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| Google Cast (`play-services-cast`, `play-services-cast-tv`) | 22.3.1, 21.1.1 | Android Software Development Kit License | <https://developers.google.com/cast> |
| NanoHTTPD | 2.3.1 | BSD-3-Clause | <https://github.com/NanoHttpd/nanohttpd> |

LibVLC is used unmodified, as a separate library that the app links dynamically. Its licence lets you replace it
with your own build. The playback code in `libs/tv-media` is described under "TV media library" below.

## iPhone (`apps/ios-phone`)

| Component | Version | Licence | Upstream |
| --- | --- | --- | --- |
| GoogleSignIn-iOS | 9.1.0 (exact) | Apache-2.0 | <https://github.com/google/GoogleSignIn-iOS> |
| Google Cast SDK for iOS (`GoogleCastDynamic`) | 4.8.6 (exact) | Google Cast SDK terms (binary framework) | <https://github.com/googlecast/google-cast-ios-sdk> |
| TDLibFramework | 1.8.67-fa8294b4 (exact) | MIT (the wrapper); it contains TDLib and OpenSSL below | <https://github.com/Swiftgram/TDLibFramework> |

GoogleSignIn-iOS pulls in AppAuth-iOS (2.0.0 or later), GTMAppAuth (5.0.0 or later), GTMSessionFetcher
(3.3.0 or later), GoogleUtilities (8.0.0 or later), App Check (11.0.0 or later), Promises and
interop-ios-for-google-sdks, all Apache-2.0. The repository doesn't commit a `Package.resolved`, so Swift
Package Manager chooses the exact versions at build time within those ranges.

## TV web app: LG webOS and Samsung Tizen (`apps/tv-web`)

| Component | Version | Licence | Upstream |
| --- | --- | --- | --- |
| GramJS (`telegram`) | 2.26.22 | MIT | <https://github.com/gram-js/gramjs> |
| @cryptography/aes | 0.1.1 | GPL-3.0-or-later | <https://github.com/spalt08/cryptography> |
| big-integer | 1.6.52 | Unlicense | <https://github.com/peterolson/BigInteger.js> |
| buffer | 6.0.3 | MIT | <https://github.com/feross/buffer> |
| events | 3.3.0 | MIT | <https://github.com/browserify/events> |
| jsbn | 1.1.0 | MIT | <https://github.com/andyperlitch/jsbn> |
| qrcode-generator | 2.0.4 | MIT | <https://github.com/kazuhikoarase/qrcode-generator> |
| async-mutex | 0.3.2 | MIT | <https://github.com/DirtyHairy/async-mutex> |
| pako | 2.2.0 | MIT AND Zlib | <https://github.com/nodeca/pako> |
| websocket | 1.0.35 | Apache-2.0 | <https://github.com/theturtle32/WebSocket-Node> |

Versions are the ones in `apps/tv-web/package-lock.json`. The app is GPL-3.0, which is compatible with
@cryptography/aes; keep that package out of `libs/tv-media`. Direct Telegram playback in this app is built
only with `VITE_TG_DIRECT=1`. The app doesn't contain TDLib or OpenSSL.

## Web remote (`apps/web-remote`)

No third-party runtime dependencies. `package.json` lists none, and the page is plain HTML, CSS and JavaScript.

## TV media library (`libs/tv-media`)

Version 0.0.0 (`libs/tv-media/package.json`), Apache-2.0, copyright Seep Labs: [libs/tv-media/LICENSE](libs/tv-media/LICENSE) and
[libs/tv-media/NOTICE](libs/tv-media/NOTICE). It has no runtime dependencies, so other projects can use it without
GPL obligations. Its Telegram byte source takes any object that implements its small `TelegramFileApi`; the TV web
app implements it with GramJS, which is not part of the library. Its development tools (esbuild, Playwright,
TypeScript, Vitest) are not shipped.

## Shared

| Component | Version | Licence | Upstream |
| --- | --- | --- | --- |
| TDLib (Telegram Database Library) | 1.8.67 (Android: bundled in `tdl-coroutines-android` 15.0.0; iPhone: TDLibFramework 1.8.67-fa8294b4) | BSL-1.0 | <https://github.com/tdlib/td> |
| tdl-coroutines-android (Kotlin bindings for TDLib) | 15.0.0 | Apache-2.0 | <https://github.com/g000sha256/tdl-coroutines> |
| OkHttp | 4.12.0 | Apache-2.0 | <https://github.com/square/okhttp> |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| kotlinx.serialization | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| Sentry Android SDK | 8.16.0 | MIT | <https://github.com/getsentry/sentry-java> |
| Kotlin | 2.4.10 | Apache-2.0 | <https://github.com/JetBrains/kotlin> |

Sentry is used for crash and bug reports in both Android apps.

### OpenSSL

TDLib links OpenSSL statically.

- **Android (phone and TV): OpenSSL 1.1.1w**, in `libtdjsonjava.so` for `arm64-v8a`, `armeabi-v7a`, `x86` and
  `x86_64`. Read with `strings` from the `tdl-coroutines-android` 15.0.0 AAR. Licence: the OpenSSL License and the
  original SSLeay License (dual licence). Source:
  <https://www.openssl.org/source/old/1.1.1/openssl-1.1.1w.tar.gz>
- **iPhone: OpenSSL 3.1.5**, as built by TDLib's `example/ios/build-openssl.sh` at commit `fa8294b4` for
  TDLibFramework 1.8.67-fa8294b4. Licence: Apache-2.0. Taken from those build scripts; the binary framework itself
  was not inspected.

As the OpenSSL License requires: this product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (<https://www.openssl.org/>). This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com).

The OpenSSL License is not compatible with the GPL, so Cablegram's own code carries an additional permission to be
combined with it; see [LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md).

## Fonts and visual assets

- **Fonts:** the repository contains no font files (`.ttf`, `.otf`, `.woff`, `.woff2`).
- **Screenshots** in `docs/screenshots/` show Blender Foundation open movies (Sintel, Tears of Steel, Elephants
  Dream, Big Buck Bunny, Caminandes: Gran Dillama) under Creative Commons Attribution licences. Credits and
  licence versions: [docs/screenshots/CREDITS.md](docs/screenshots/CREDITS.md).
- **Title search, metadata and artwork** shown in the apps come from The Movie Database (TMDB) through the
  Cablegram service. This product uses the TMDB API but is not endorsed or certified by TMDB.
- **App icons** in the repository were made with AI tools (Gemini, ChatGPT and Claude) at the direction of the
  Cablegram authors, and are published under the licence of this repository.

## Licence texts

- GPL-3.0: [LICENSE](LICENSE), <https://www.gnu.org/licenses/gpl-3.0.html>
- Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0.txt>
- LGPL-2.1: <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- BSL-1.0: <https://www.boost.org/LICENSE_1_0.txt>
- MIT: <https://opensource.org/license/mit>
- BSD-3-Clause: <https://opensource.org/license/bsd-3-clause>
- Unlicense: <https://unlicense.org/>
- Zlib: <https://zlib.net/zlib_license.html>
- OpenSSL License and SSLeay License: <https://www.openssl.org/source/license-openssl-ssleay.txt>
- Android Software Development Kit License: <https://developer.android.com/studio/terms.html>
- CC BY 3.0 and CC BY 2.5: <https://creativecommons.org/licenses/by/3.0/>, <https://creativecommons.org/licenses/by/2.5/>
