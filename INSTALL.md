# Installing Cablegram outside the app stores

Cablegram is two apps that work together: the phone app, where you choose your videos, and the TV app, which
plays them. Both are on [GitHub Releases](https://github.com/seep-labs/cablegram-apps/releases) as signed
files you can install yourself. Verify what you download with [VERIFY.md](VERIFY.md).

Want to try a test version through Google Play instead? Follow the tester guide at
[cablegram.app/test/](https://cablegram.app/test/).

| You have | Install | Section |
| --- | --- | --- |
| Android phone | `cablegram-phone-<abi>-<tag>.apk` | [Android phone](#android-phone) |
| Android TV or Google TV | `cablegram-tv-<abi>-<tag>.apk` | [Android TV](#android-tv-and-google-tv) |
| LG TV (webOS) | the `.ipk` of a `tv-v*` release | [LG webOS](#lg-webos) |
| Samsung TV (Tizen) | not yet | [Samsung Tizen](#samsung-tizen) |
| iPhone | not as a file | [iPhone](#iphone) |

## Which APK to pick

Each app is released as one APK per processor type. There is no universal APK. Releases made before the
per-ABI split (v0.2.0) have a single file per app.

| ABI | Devices |
| --- | --- |
| `arm64-v8a` | Almost every phone and current TV |
| `armeabi-v7a` | Older 32-bit phones and TVs |

If you don't know which one you have, try `arm64-v8a`. Android refuses to install an APK that doesn't fit, so
a wrong pick does no harm. Releases marked "pre-release" are test builds.

## Android phone

1. On the phone, open the [releases page](https://github.com/seep-labs/cablegram-apps/releases) in your
   browser and download `cablegram-phone-<abi>-<tag>.apk`.
2. Open the downloaded file. Android asks you to allow your browser (or file manager) to **install unknown
   apps**. Allow it for that app only (Settings > Apps > Special access > Install unknown apps), then go back
   and tap **Install**.
3. Open Cablegram. You can switch the permission off again afterwards.

If you installed Cablegram from Google Play first, uninstall it before installing the APK: the two can be signed
with different keys, and Android won't update an app with a build signed by another key.

## Android TV and Google TV

The TV app has no browser download button on most TVs. Two common ways:

**With the Downloader app (no computer):**

1. Install **Downloader** from the TV's app store, and allow it to install unknown apps (Settings > Apps >
   Security & restrictions > Unknown sources, or the prompt that appears on first use; the path varies by TV).
2. In Downloader, enter the direct URL of `cablegram-tv-<abi>-<tag>.apk` from the release page (right-click the
   asset on a computer, copy the link) and install.
3. Open **Cablegram TV**, then pair it with the phone app using the code it shows.

**With a computer and adb:**

```sh
adb connect <tv-ip>:5555        # enable Developer options > ADB debugging on the TV first
adb install cablegram-tv-arm64-v8a-<tag>.apk
```

## LG webOS

The LG app is a small wrapper that opens the hosted TV app at <https://tv.cablegram.app>. It is not in the LG
Content Store yet, so it needs Developer Mode on the TV:

1. Download the `.ipk` attached to the latest `tv-v*` release.
2. Turn on Developer Mode on the TV and note the TV's IP address and passphrase.
3. Install the `.ipk` with webOS Dev Manager or the `ares-install` command.

The step-by-step guide for both tools is at
[cablegram.app/lg/](https://cablegram.app/lg/). You can also open <https://tv.cablegram.app> in the TV's
browser without installing anything.

## Samsung Tizen

Not yet. The Tizen package (`.wgt`) must be signed with a Samsung certificate profile, and there is no signed
package to download. You can build one yourself ([apps/tv-web/README.md](apps/tv-web/README.md)) or open
<https://tv.cablegram.app> in the TV's browser.

## iPhone

There is no installable file. The iPhone app is built from `apps/ios-phone` and published through Apple's
channels. Until then, the [web remote](https://app.cablegram.app/) controls the TV from Safari (Share > Add to
Home Screen).

## Check the signing certificate of an APK

Every Android release is signed with the Cablegram release key. To see the certificate of a file you downloaded:

```sh
apksigner verify --print-certs cablegram-phone-arm64-v8a-<tag>.apk
```

`apksigner` ships with the Android SDK build tools (`<sdk>/build-tools/<version>/apksigner`). Look at the line
`certificate SHA-256 digest`. Read from the signed v0.2.0 release APK (`cablegram-phone-v0.2.0.apk`), it is:

```
02d4f66d77fd08ad94adafa7b2b2f5c2e23a485b5d666323f354cf8d6ce2f913
```

If the digest on a file you have is different, don't install it. VERIFY.md also covers the file hash
(`SHA256SUMS`), the build attestation (`gh attestation verify`) and checking an app that is already installed.
The Google Play build is signed differently; see "If you installed from Google Play" in VERIFY.md.
