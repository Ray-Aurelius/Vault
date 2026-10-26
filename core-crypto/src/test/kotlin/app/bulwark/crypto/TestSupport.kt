package app.bulwark.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

object TestSupport {
    val primitives: Primitives by lazy { Primitives(LazySodiumJava(SodiumJava())) }

    /** Cheap settings so tests run fast. Real vaults are refused below the production floor. */
    val fastParams = Argon2Params(opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
}
