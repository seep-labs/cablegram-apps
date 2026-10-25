# Additional permission for OpenSSL

Cablegram is licensed under the GNU General Public License version 3 (`LICENSE`). As an additional
permission under section 7 of that licence, the copyright holders grant the following:

If you modify this Program, or any covered work, by linking or combining it with OpenSSL (or a
modified version of that library), containing parts covered by the terms of the OpenSSL License or
the original SSLeay License, the licensors of this Program grant you additional permission to
convey the resulting work. Corresponding Source for a non-source form of such a combination shall
include the source code for the parts of OpenSSL used as well as that of the covered work.

## Why

The Android apps (phone and TV) reach Telegram through TDLib. The `tdl-coroutines-android` 15.0.0
package they depend on bundles TDLib 1.8.67 linked against OpenSSL 1.1.1w. OpenSSL releases before
3.0 are under the OpenSSL and SSLeay licences, which the Free Software Foundation considers
incompatible with the GPL. OpenSSL 3 is under Apache-2.0 and needs no exception; this permission
covers the older version. The iPhone app uses a TDLib build with OpenSSL 3.1.5 and the TV web app
ships no OpenSSL; see [NOTICE](NOTICE).

## Removing this permission

You may remove this additional permission from your copy, or from modified versions of Cablegram,
as section 7 of the GPL allows. If you do, the combination with OpenSSL before 3.0 is no longer
covered by it.

Copyright (C) 2026 The Cablegram authors
