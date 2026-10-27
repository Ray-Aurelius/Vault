package app.bulwark.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.bulwark.android.session.VaultSession
import app.bulwark.crypto.Primitives
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

class BulwarkApplication : Application() {
    /** The single libsodium binding for the whole app, loaded on first use. */
    val primitives: Primitives by lazy { Primitives(LazySodiumAndroid(SodiumAndroid())) }

    lateinit var session: VaultSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = VaultSession(this) { primitives }

        // Auto-lock: whenever Bulwark leaves the screen (unless the system file picker is open for it)...
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = session.onAppBackgrounded()
        })
        // ...and always when the screen turns off.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = session.onScreenOff()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
