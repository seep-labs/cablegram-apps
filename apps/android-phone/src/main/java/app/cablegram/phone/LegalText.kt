package app.cablegram.phone

import android.content.Context

/**
 * The licence and third-party notices shown on the About/Settings screen. `NOTICE` and `LICENSE` come from
 * the repository root: the Gradle task `copyLegalAssets` packs them as `assets/legal/`, so the text in the
 * app is always the text in the repository.
 */
/** The apps' public repository, linked from Settings next to the licence. */
const val SOURCE_CODE_URL = "https://github.com/seep-labs/cablegram-apps"

/** The public feedback page on cablegram.app, linked from Settings and the post-playback prompt. */
const val FEEDBACK_URL = "https://cablegram.app/feedback.html"

object LegalText {
    fun read(context: Context): String = listOf("NOTICE", "LICENSE").joinToString("\n\n") { name ->
        runCatching { context.assets.open("legal/$name").bufferedReader().use { it.readText() } }
            .getOrElse { "$name is missing from this build." }
    }
}
