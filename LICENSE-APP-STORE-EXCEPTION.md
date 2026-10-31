# Additional permissions for app stores and Google libraries

Cablegram is licensed under the GNU General Public License version 3 (`LICENSE`). As additional
permissions under section 7 of that licence, the copyright holders grant the two permissions below. They
are in addition to the permission in [LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md).

## 1. Distribution through app stores

You may convey a covered work in object code form through an app store or a similar distribution
service, such as Apple's App Store, Google Play, the LG Content Store or the Samsung Galaxy Store, even
though the service's terms or its technical measures place restrictions on recipients that the GPL would
otherwise not allow. Examples are limits on the number of devices, a requirement to accept the service's
terms of use, and code signing or encryption applied by the service. This permission applies only if:

1. the Corresponding Source of the work you convey is available to every recipient as section 6 of the
   GPL requires, under the GPL with these additional permissions; and
2. the restrictions come from the service, and you add none of your own.

This permission does not limit what recipients may do with the Corresponding Source under the GPL.

## 2. Google Play services and the Google Cast SDK

If you modify this Program, or any covered work, by linking or combining it with the libraries below
(or modified versions of them), the licensors of this Program grant you additional permission to convey
the resulting work, even though the licences of those libraries are not compatible with the GPL:

- the Google Play services and Google Identity client libraries for Android, such as
  `play-services-cast`, `play-services-cast-framework`, `play-services-cast-tv` and `googleid`, under the
  Android Software Development Kit License;
- the Google Cast SDK for iOS (`GoogleCastDynamic`), under the Google Cast SDK terms.

Corresponding Source for a non-source form of such a combination need not include the source code of
those libraries. It shall include the Corresponding Source of the covered work.

## Scope

These permissions are granted by the copyright holders of Cablegram's own code. They do not change the
licence of any third-party component ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)). In particular,
the TV web app (`apps/tv-web`) bundles `@cryptography/aes`, which its own authors license under
GPL-3.0-or-later; these permissions don't cover it. `libs/tv-media` is under the Apache License 2.0 and
needs neither permission.

## Why

App stores make every user accept their terms of use and apply code signing and usage limits. Section 10
of the GPL forbids imposing further restrictions on recipients, so a contributor to a GPL-only project can
object to its app store builds, as happened to VLC in Apple's App Store in 2011. The first permission lets
Cablegram and its forks ship through app stores as long as the source stays available under the GPL.

The phone apps send video to TVs with Google Cast, and the Android TV app receives it with Cast Connect.
Google publishes these libraries only in binary form under its own terms, which the GPL's system library
exception does not cover. The second permission covers them, the same way
[LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md) covers OpenSSL.

## Removing these permissions

You may remove either permission from your copy, or from modified versions of Cablegram, as section 7 of
the GPL allows. If you do, the distributions and combinations it covers are no longer covered by it.

Copyright (C) 2026 The Cablegram authors
