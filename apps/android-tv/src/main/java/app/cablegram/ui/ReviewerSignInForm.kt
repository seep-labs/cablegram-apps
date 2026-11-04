package app.cablegram.ui

import app.cablegram.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

private const val CODE_LENGTH = 6

/**
 * Store review: a reviewer signs this TV in with the code from the review notes, with no phone or website. The pairing
 * screen offers it only while the server says so. On success the usual pairing poll takes the TV on to its profiles.
 */
@Composable
internal fun ReviewerSignInForm(
    signIn: suspend (String) -> String?,
    onCancel: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onCancel)

    fun submit() {
        if (checking || code.length != CODE_LENGTH) return
        keyboard?.hide()
        runCatching { signInFocus.requestFocus() }
        checking = true
        error = null
        scope.launch {
            error = signIn(code)
            // On success, stay on "Signing in…" until the poll moves on.
            if (error != null) checking = false
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark()
        Text(ui.getString(R.string.reviewer_sign_in), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(ui.getString(R.string.reviewer_sign_in_body), color = Muted, fontSize = 18.sp)
        error?.let { Text(it, color = Coral, fontSize = 14.sp) }
        // Field and buttons on one line, so they stay above the TV keyboard.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = code,
                onValueChange = { typed ->
                    val digits = typed.filter(Char::isDigit).take(CODE_LENGTH)
                    if (digits != code) error = null
                    code = digits
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                textStyle = TextStyle(color = Paper, fontSize = 28.sp, letterSpacing = 6.sp),
                cursorBrush = SolidColor(Cyan),
                modifier = Modifier.width(280.dp).focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused }
                    // A text field keeps the D-pad for its caret: down or right goes on to Sign in.
                    .onPreviewKeyEvent { event ->
                        val leave = event.type == KeyEventType.KeyDown && (event.key == Key.DirectionDown || event.key == Key.DirectionRight)
                        if (leave) runCatching { signInFocus.requestFocus() }
                        leave
                    }
                    .background(PanelRaised, RoundedCornerShape(10.dp))
                    .border(2.dp, if (fieldFocused) Cyan else Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (code.isEmpty()) Text(ui.getString(R.string.reviewer_sign_in_hint), color = Slate, fontSize = 16.sp)
                    inner()
                },
            )
            Button(onClick = ::submit, enabled = !checking && code.length == CODE_LENGTH, modifier = Modifier.focusRequester(signInFocus)) {
                Text(if (checking) ui.getString(R.string.reviewer_sign_in_checking) else ui.getString(R.string.reviewer_sign_in_action))
            }
            Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                Text(ui.getString(R.string.ui_cancel_19766e))
            }
        }
    }
}
