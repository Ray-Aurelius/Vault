package app.bulwark.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.bulwark.android.R

/** First launch: Welcome → How Bulwark works → Terms → Privacy & Security Statement. Skip jumps to the Terms. */
@Composable
fun OnboardingFlow(onAccepted: () -> Unit, onDecline: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }
    when (step) {
        0 -> WelcomeScreen(onStart = { step = 1 }, onSkip = { step = 2 })
        1 -> HowItWorksScreen(onNext = { step = 2 }, onBack = { step = 0 }, onSkip = { step = 2 })
        2 -> TermsScreen(onAgree = { step = 3 }, onDecline = onDecline, onBack = { step = 1 })
        else -> PrivacyScreen(onContinue = onAccepted, onBack = { step = 2 })
    }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onSkip: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            BrandHeader(stringResource(R.string.tagline))
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.welcome_line), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.widthIn(max = 480.dp),
            )
            Spacer(Modifier.height(32.dp))
            Column(Modifier.widthIn(max = 480.dp)) {
                PrimaryButton(stringResource(R.string.get_started), onStart)
                LinkButton(stringResource(R.string.skip_walkthrough), onSkip)
            }
        }
    }
}

@Composable
private fun HowItWorksScreen(onNext: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit) {
    Page(
        title = stringResource(R.string.how_title), onBack = onBack,
        bottomBar = {
            Column {
                PrimaryButton(stringResource(R.string.next), onNext)
                LinkButton(stringResource(R.string.skip_walkthrough), onSkip)
            }
        },
    ) {
        InfoCard(stringResource(R.string.how_1_title), stringResource(R.string.how_1_body), R.drawable.ic_lock)
        InfoCard(stringResource(R.string.how_2_title), stringResource(R.string.how_2_body), R.drawable.ic_close)
        InfoCard(stringResource(R.string.how_3_title), stringResource(R.string.how_3_body), R.drawable.ic_warning)
    }
}

/** The Terms gate: "I agree" stays disabled until the text has been scrolled to the end and both boxes are ticked. */
@Composable
private fun TermsScreen(onAgree: () -> Unit, onDecline: () -> Unit, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    val atEnd by remember { derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 } }
    var readToEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(atEnd) { if (atEnd) readToEnd = true }
    var agree by rememberSaveable { mutableStateOf(false) }
    var understand by rememberSaveable { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(stringResource(R.string.terms_title), onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp),
            ) { LegalText(R.raw.terms_of_service); Spacer(Modifier.height(16.dp)) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                if (!readToEnd) Quiet(stringResource(R.string.terms_scroll_hint), Modifier.padding(bottom = 4.dp))
                CheckRow(stringResource(R.string.terms_check_agree), agree, readToEnd) { agree = it }
                CheckRow(stringResource(R.string.terms_check_understand), understand, readToEnd) { understand = it }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(stringResource(R.string.i_agree), onAgree, enabled = readToEnd && agree && understand)
                LinkButton(stringResource(R.string.decline), onDecline)
            }
        }
    }
}

@Composable
private fun PrivacyScreen(onContinue: () -> Unit, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    val atEnd by remember { derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 } }
    var readToEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(atEnd) { if (atEnd) readToEnd = true }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(stringResource(R.string.privacy_title), onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp)) {
                LegalText(R.raw.privacy_statement); Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                if (!readToEnd) Quiet(stringResource(R.string.terms_scroll_hint), Modifier.padding(bottom = 4.dp))
                PrimaryButton(stringResource(R.string.privacy_ack), onContinue, enabled = readToEnd)
            }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp),
            color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
