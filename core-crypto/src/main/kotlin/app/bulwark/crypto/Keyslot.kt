package app.bulwark.crypto

/**
 * A keyslot is one lock box around the master key. Each unlock method (password, PIN, recovery
 * key, biometric, hardware key, split recovery) gets its own slot, so methods can be added,
 * replaced or revoked without touching the others or re-encrypting any file.
 *
 * Binary layout (version 1), all integers big-endian:
 *
 *     "BWKS" | version u8 = 1 | type u8 | slotId[16] | hasKdf u8
 *     [ salt[16] | opsLimit u32 | memLimitKiB u32 ]   (only when hasKdf = 1)
 *     wrappedLength u16 | wrapped[wrappedLength]
 *
 * Everything before `wrapped` is authenticated as associated data, so changing the type, ID or
 * cost settings makes the slot fail to open rather than silently weakening it.
 */
class Keyslot private constructor(
    val type: Type,
    val slotId: ByteArray,
    val salt: ByteArray?,
    val kdfParams: Argon2Params?,
    private val wrapped: ByteArray,
) {
    enum class Type(val code: Int, val usesPasswordKdf: Boolean) {
        PASSWORD(1, true),
        PIN(2, true),
        RECOVERY_KEY(3, false),
        BIOMETRIC(4, false),
        HARDWARE_KEY(5, false),
        SPLIT_RECOVERY(6, false);

        companion object {
            fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
                ?: throw UnsupportedFormatException("Unknown keyslot type $code")
        }
    }

    val slotIdHex: String get() = slotId.toHex()

    fun serialize(): ByteArray = BinaryWriter().bytes(headerBytes()).u16(wrapped.size).bytes(wrapped).toByteArray()

    private fun headerBytes(): ByteArray = Companion.headerBytes(type, slotId, salt, kdfParams)

    /** Opens this slot with a typed password or PIN. The caller wipes [password]. */
    fun unlockWithPassword(primitives: Primitives, password: CharArray): MasterKey {
        require(type.usesPasswordKdf) { "Not a password or PIN slot" }
        val pw = PasswordEncoding.encode(password)
        val kek = try {
            primitives.argon2id(pw, salt!!, kdfParams!!)
        } finally {
            SecretBytes.wipe(pw)
        }
        return openWith(primitives, kek)
    }

    /** Opens this slot with a 32-byte secret (recovery key, biometric-released key, hardware key output, rebuilt shares). */
    fun unlockWithSecret(primitives: Primitives, secret: SecretBytes): MasterKey {
        require(!type.usesPasswordKdf) { "Password slots need a password" }
        val kek = secret.withBytes { secretKek(primitives, type, it) }
        return openWith(primitives, kek)
    }

    private fun openWith(primitives: Primitives, kek: ByteArray): MasterKey {
        try {
            val mk = try {
                primitives.aeadOpen(kek, wrapped, headerBytes())
            } catch (e: CorruptDataException) {
                // A wrong password and a tampered slot look identical by design.
                throw WrongCredentialsException()
            }
            return MasterKey.fromBytes(mk, primitives)
        } finally {
            SecretBytes.wipe(kek)
        }
    }

    companion object {
        private val MAGIC = "BWKS".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        const val SLOT_ID_BYTES = 16
        private const val MAX_WRAPPED = 256

        fun createPassword(
            primitives: Primitives,
            masterKey: MasterKey,
            password: CharArray,
            params: Argon2Params,
            type: Type = Type.PASSWORD,
            allowBelowFloor: Boolean = false,
        ): Keyslot {
            require(type.usesPasswordKdf) { "$type does not use a password" }
            require(allowBelowFloor || params.meetsProductionFloor) { "Argon2 settings below Bulwark's floor" }
            val salt = primitives.randomBytes(Argon2Params.SALT_BYTES)
            val id = primitives.randomBytes(SLOT_ID_BYTES)
            val pw = PasswordEncoding.encode(password)
            val kek = try { primitives.argon2id(pw, salt, params) } finally { SecretBytes.wipe(pw) }
            return seal(primitives, masterKey, kek, type, id, salt, params)
        }

        fun createWithSecret(primitives: Primitives, masterKey: MasterKey, type: Type, secret: SecretBytes): Keyslot {
            require(!type.usesPasswordKdf) { "$type needs a password" }
            val id = primitives.randomBytes(SLOT_ID_BYTES)
            val kek = secret.withBytes { secretKek(primitives, type, it) }
            return seal(primitives, masterKey, kek, type, id, null, null)
        }

        fun parse(bytes: ByteArray): Keyslot {
            val r = BinaryReader(bytes)
            if (!r.bytes(4).contentEquals(MAGIC)) throw CorruptDataException("Not a keyslot")
            val version = r.u8()
            if (version != VERSION) throw UnsupportedFormatException("Keyslot version $version")
            val type = Type.fromCode(r.u8())
            val id = r.bytes(SLOT_ID_BYTES)
            val hasKdf = r.u8()
            var salt: ByteArray? = null
            var params: Argon2Params? = null
            when (hasKdf) {
                0 -> if (type.usesPasswordKdf) throw CorruptDataException("Password slot without KDF settings")
                1 -> {
                    if (!type.usesPasswordKdf) throw CorruptDataException("Unexpected KDF settings")
                    salt = r.bytes(Argon2Params.SALT_BYTES)
                    val ops = r.u32()
                    val memKiB = r.u32()
                    params = try { Argon2Params(ops, memKiB * 1024) } catch (e: IllegalArgumentException) {
                        throw CorruptDataException("Keyslot cost settings out of range")
                    }
                }
                else -> throw CorruptDataException("Bad KDF flag")
            }
            val len = r.u16()
            if (len !in (Primitives.AEAD_NONCE_BYTES + Primitives.AEAD_TAG_BYTES + 1)..MAX_WRAPPED) {
                throw CorruptDataException("Bad wrapped key length")
            }
            val wrapped = r.bytes(len)
            r.requireEnd()
            return Keyslot(type, id, salt, params, wrapped)
        }

        private fun seal(
            primitives: Primitives, masterKey: MasterKey, kek: ByteArray, type: Type, id: ByteArray,
            salt: ByteArray?, params: Argon2Params?,
        ): Keyslot {
            try {
                val header = headerBytes(type, id, salt, params)
                val wrapped = masterKey.withBytes { primitives.aeadSeal(kek, it, header) }
                return Keyslot(type, id, salt, params, wrapped)
            } finally {
                SecretBytes.wipe(kek)
            }
        }

        private fun headerBytes(type: Type, id: ByteArray, salt: ByteArray?, params: Argon2Params?): ByteArray {
            val w = BinaryWriter().bytes(MAGIC).u8(VERSION).u8(type.code).bytes(id)
            if (salt != null && params != null) {
                w.u8(1).bytes(salt).u32(params.opsLimit).u32(params.memLimitBytes / 1024)
            } else {
                w.u8(0)
            }
            return w.toByteArray()
        }

        /** Each non-password slot type derives its key-encryption key under its own context. */
        private fun secretKek(primitives: Primitives, type: Type, secret: ByteArray): ByteArray {
            if (secret.size != Primitives.KEY_BYTES) throw IllegalArgumentException("Secret must be ${Primitives.KEY_BYTES} bytes")
            val context = when (type) {
                Type.RECOVERY_KEY -> "BWrecov1"
                Type.BIOMETRIC -> "BWbiom01"
                Type.HARDWARE_KEY -> "BWhwkey1"
                Type.SPLIT_RECOVERY -> "BWsplit1"
                else -> error("unreachable")
            }
            return primitives.deriveSubkey(secret, 1, context)
        }
    }
}

/**
 * The set of keyslots for one vault, stored as a single file (sealed again by the device layer on
 * the phone; written as-is into backups).
 *
 *     "BWKR" | version u8 = 1 | count u8 | ( length u16 | keyslot[length] ) * count
 */
class Keyring(slots: List<Keyslot>) {
    val slots: List<Keyslot> = slots.toList()

    init {
        require(this.slots.size <= MAX_SLOTS) { "Too many keyslots" }
    }

    fun ofType(type: Keyslot.Type) = slots.filter { it.type == type }

    fun with(slot: Keyslot) = Keyring(slots + slot)

    /** Removes a slot, e.g. when a recovery method is revoked. */
    fun without(slotIdHex: String) = Keyring(slots.filterNot { it.slotIdHex == slotIdHex })

    /** Tries every password slot of [type]; throws [WrongCredentialsException] if none opens. */
    fun unlockWithPassword(primitives: Primitives, password: CharArray, type: Keyslot.Type = Keyslot.Type.PASSWORD): MasterKey {
        for (slot in ofType(type)) {
            try { return slot.unlockWithPassword(primitives, password) } catch (_: WrongCredentialsException) {}
        }
        throw WrongCredentialsException()
    }

    fun unlockWithSecret(primitives: Primitives, type: Keyslot.Type, secret: SecretBytes): MasterKey {
        for (slot in ofType(type)) {
            try { return slot.unlockWithSecret(primitives, secret) } catch (_: WrongCredentialsException) {}
        }
        throw WrongCredentialsException()
    }

    fun serialize(): ByteArray {
        val w = BinaryWriter().bytes(MAGIC).u8(VERSION).u8(slots.size)
        for (s in slots) s.serialize().let { w.u16(it.size).bytes(it) }
        return w.toByteArray()
    }

    companion object {
        private val MAGIC = "BWKR".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        const val MAX_SLOTS = 32

        fun parse(bytes: ByteArray): Keyring {
            val r = BinaryReader(bytes)
            if (!r.bytes(4).contentEquals(MAGIC)) throw CorruptDataException("Not a keyring")
            val version = r.u8()
            if (version != VERSION) throw UnsupportedFormatException("Keyring version $version")
            val count = r.u8()
            if (count > MAX_SLOTS) throw CorruptDataException("Too many keyslots")
            val slots = List(count) { Keyslot.parse(r.bytes(r.u16())) }
            r.requireEnd()
            return Keyring(slots)
        }
    }
}
