package app.bulwark.android.ui

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.bulwark.android.R
import app.bulwark.vault.PasswordStrength
import kotlinx.coroutines.delay

/** Amber for warnings, never used as a brand colour. */
val WarningAmber = Color(0xFF8A5300)
val WarningAmberDark = Color(0xFFFFC46B)

/** A full-screen page with an optional top bar and a scrolling body, kept clear of system bars and keyboard. */
@Composable
fun Page(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    scroll: Boolean = true,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (title != null || onBack != null) TopBar(title, onBack, actions)
            val body = Modifier.weight(1f).fillMaxWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
            Box(body, contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), content = content)
            }
            if (bottomBar != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()) { bottomBar() }
                }
            }
        }
    }
}

@Composable
fun TopBar(title: String?, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
        } else Spacer(Modifier.width(16.dp))
        Text(
            title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}

@Composable
fun BrandHeader(subtitle: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_bulwark_mark), null, Modifier.size(width = 72.dp, height = 64.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Headline(text: String) = Text(
    text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
    modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp),
)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onBackground) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier.padding(vertical = 4.dp))

@Composable
fun Quiet(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) =
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) =
    OutlinedButton(onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

@Composable
fun DangerButton(text: String, onClick: () -> Unit, enabled: Boolean = true) =
    Button(
        onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
fun LinkButton(text: String, onClick: () -> Unit) =
    TextButton(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
fun InfoCard(title: String?, body: String, @DrawableRes icon: Int? = null, warning: Boolean = false) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val accent = if (warning) (if (dark) WarningAmberDark else WarningAmber) else MaterialTheme.colorScheme.primary
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(painterResource(icon), null, tint = accent, modifier = Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = if (warning) accent else MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Password, PIN or recovery-word field with the show/hide eye. Hidden every time it appears;
 * once shown it hides itself again after 15 seconds (spec: show-password eye).
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    isError: Boolean = false,
    supporting: String? = null,
    singleLine: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) { delay(15_000); visible = false } }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun StrengthMeter(estimate: PasswordStrength.Estimate) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val (fraction, color, label) = when (estimate.level) {
        PasswordStrength.Level.TOO_WEAK -> Triple(0.2f, MaterialTheme.colorScheme.error, R.string.strength_too_weak)
        PasswordStrength.Level.WEAK -> Triple(0.5f, if (dark) WarningAmberDark else WarningAmber, R.string.strength_weak)
        PasswordStrength.Level.GOOD -> Triple(0.8f, MaterialTheme.colorScheme.primary, R.string.strength_good)
        PasswordStrength.Level.STRONG -> Triple(1f, MaterialTheme.colorScheme.primary, R.string.strength_strong)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LinearProgressIndicator(
            progress = { fraction }, color = color, trackColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.weight(1f))
        }
        val hint = when (estimate.hint) {
            PasswordStrength.Hint.TOO_SHORT -> R.string.hint_too_short
            PasswordStrength.Hint.COMMON -> R.string.hint_common
            PasswordStrength.Hint.REPEATS -> R.string.hint_repeats
            PasswordStrength.Hint.SEQUENCE -> R.string.hint_sequence
            PasswordStrength.Hint.ADD_VARIETY -> R.string.hint_variety
            null -> null
        }
        if (hint != null) Quiet(stringResource(hint))
    }
}

/** Renders the bundled legal texts: "# " lines are headings, "- " lines bullets, the rest paragraphs. */
@Composable
fun LegalText(@RawRes res: Int) {
    val context = LocalContext.current
    val lines = remember(res) { context.resources.openRawResource(res).bufferedReader().use { it.readLines() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in lines) when {
            line.isBlank() -> Spacer(Modifier.height(4.dp))
            line.startsWith("# ") -> Text(
                line.removePrefix("# "), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            line.startsWith("- ") -> Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.width(18.dp))
                Text(line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            else -> Text(
                line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground,
                fontWeight = if (line.startsWith("You agree that") || line.startsWith("The short version")) FontWeight.SemiBold else null,
            )
        }
    }
}

@Composable
fun Pill(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
)
