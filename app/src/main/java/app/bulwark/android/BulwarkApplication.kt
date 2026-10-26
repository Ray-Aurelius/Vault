package app.bulwark.android

import android.app.Application
import app.bulwark.crypto.Primitives
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

class BulwarkApplication : Application() {
    /** The single libsodium binding for the whole app, loaded on first use. */
    val primitives: Primitives by lazy { Primitives(LazySodiumAndroid(SodiumAndroid())) }
}
