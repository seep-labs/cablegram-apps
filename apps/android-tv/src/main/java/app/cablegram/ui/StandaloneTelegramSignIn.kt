package app.cablegram.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.cablegram.telegram.TgException

/** Pure state machine for standalone TV Telegram sign-in, testable on the JVM. */
enum class StandaloneStep { Consent, Qr, Phone, Code, Password, BotCompleting, Error }

data class StandaloneSignInState(
    val step: StandaloneStep = StandaloneStep.Consent,
    val ageConfirmed: Boolean = false,
    val qrLink: String? = null,
    val phone: String = "+",
    val code: String = "",
    val passwordTriesLeft: Int = 3,
    val errorText: String? = null,
)

fun mapStandaloneTgError(e: TgException, triesLeft: Int): Pair<String, Boolean> {
    val retry = e.retryAfterSeconds
    if (retry != null) {
        val mins = ((retry + 59) / 60).coerceAtLeast(1)
        val unit = if (mins == 1) "minute" else "minutes"
        return "Try again in $mins $unit." to false
    }
    return when {
        e.name.contains("PHONE_NUMBER_INVALID") -> "That phone number isn't valid." to false
        e.name.contains("PHONE_CODE_INVALID") || e.name.contains("PHONE_CODE_EXPIRED") -> "Wrong or expired code." to false
        e.name.contains("PASSWORD_HASH_INVALID") -> {
            if (triesLeft <= 1) "Too many wrong passwords. Please start again." to true
            else "Wrong password (${triesLeft - 1} tries left)." to false
        }
        else -> "Telegram sign-in failed (${e.name})." to false
    }
}

@Composable
fun TelegramSignInConsentScreen(
    termsVersion: String,
    privacyVersion: String,
    onContinue: () -> Unit,
    onPairWithPhone: () -> Unit,
) {
    var ageConfirmed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1016))
            .padding(48.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Sign in with Telegram",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "By continuing you accept Cablegram's Terms ($termsVersion) and Privacy Policy ($privacyVersion).",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFD0D4DC),
        )
        Text(
            "Already use Cablegram on your phone? Pair with your phone instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF9BA3B4),
        )
        OutlinedButton(onClick = { ageConfirmed = !ageConfirmed }) {
            Text("${if (ageConfirmed) "[x]" else "[ ]"} I confirm I am old enough to use Cablegram")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { if (ageConfirmed) onContinue() }) {
                Text("Continue")
            }
            OutlinedButton(onClick = onPairWithPhone) {
                Text("Pair with your phone")
            }
        }
    }
}

@Composable
fun TelegramQrSignInScreen(
    qrBitmap: Bitmap?,
    errorText: String?,
    onUsePhoneNumber: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1016))
            .padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Sign in with Telegram",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Open Telegram on your phone -> Settings -> Devices -> Link Desktop Device and scan this.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFD0D4DC),
            )
            if (errorText != null) {
                Text(errorText, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onUsePhoneNumber) { Text("Use phone number instead") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Telegram login QR", modifier = Modifier.fillMaxSize())
            } else {
                Text("Loading QR...", color = Color.Black)
            }
        }
    }
}

@Composable
fun TelegramPhoneSignInScreen(
    step: StandaloneStep,
    value: String,
    errorText: String?,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmitPhoneOrCode: () -> Unit,
    onSubmitPassword: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1016))
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when (step) {
                StandaloneStep.Phone -> "Enter phone number"
                StandaloneStep.Code -> "Enter Telegram code"
                else -> "Telegram two-step password"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        if (errorText != null) {
            Text(errorText, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodyMedium)
        }
        if (step == StandaloneStep.Password) {
            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .width(360.dp)
                    .border(1.dp, Color(0xFF3E4554), RoundedCornerShape(8.dp))
                    .padding(14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onSubmitPassword(password) }) { Text("Submit") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        } else {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in "1234567890") {
                    OutlinedButton(onClick = { onDigit(d) }) { Text(d.toString()) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onDelete) { Text("Delete") }
                Button(onClick = onSubmitPhoneOrCode) { Text("Next") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
