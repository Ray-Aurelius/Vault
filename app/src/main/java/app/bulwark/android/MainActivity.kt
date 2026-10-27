package app.bulwark.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.bulwark.android.session.VaultSession.State
import app.bulwark.android.ui.BulwarkTheme
import app.bulwark.android.ui.ForcedNewPasswordScreen
import app.bulwark.android.ui.OnboardingFlow
import app.bulwark.android.ui.SetupFlow
import app.bulwark.android.ui.UnlockScreen
import app.bulwark.android.ui.VaultHome
import kotlinx.coroutines.delay

/**
 * Bulwark's only screen host. Every window gets FLAG_SECURE before any content is drawn. The
 * activity handles rotation and theme changes itself, so secrets typed during setup stay in memory
 * and are never written into Android's saved-state bundle.
 */
class MainActivity : ComponentActivity() {
    private val session get() = (application as BulwarkApplication).session

    override fun onCreate(savedInstanceState: Bundle?) {
        SecureWindow.apply(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BulwarkTheme {
                val state by session.state.collectAsState()
                LaunchedEffect(Unit) { while (true) { delay(5_000); session.checkIdle() } }
                when (val s = state) {
                    State.NeedsTerms -> OnboardingFlow(onAccepted = session::acceptTerms, onDecline = ::finishAndRemoveTask)
                    State.NeedsSetup -> SetupFlow(session)
                    State.Locked -> UnlockScreen(session)
                    is State.Unlocked -> if (s.mustChangePassword) ForcedNewPasswordScreen(session) else VaultHome(session, s.vault)
                }
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        session.touch()
    }
}
