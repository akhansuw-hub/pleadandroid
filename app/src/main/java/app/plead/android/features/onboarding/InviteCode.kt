// Port of ArgueWin/Features/Onboarding/InviteCode.swift.
package app.plead.android.features.onboarding

/**
 * Invite-code rules shared by the onboarding partner step and `LinkCoupleView` (amendment as). Mirrors the
 * server: `gen_invite_code()` draws 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no 0 / O / 1 / I)
 * and `join_couple` checks `^[A-HJ-NP-Z2-9]{6}$`.
 */
object InviteCode {
    const val length = 6
    val alphabet: Set<Char> = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toSet()

    private fun isAsciiAlnum(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    /**
     * Whatever was typed or pasted, reduced to at most six uppercase letters / digits. A pasted invite message
     * ("… with code ABC234. https://…/join/ABC234") or link gives its code.
     */
    fun sanitize(raw: String): String {
        val upper = raw.uppercase()
        val at = upper.lastIndexOf("/JOIN/")
        if (at >= 0) {
            val tail = upper.substring(at + "/JOIN/".length).takeWhile(::isAsciiAlnum)
            if (tail.length == length) return tail
        }
        val tokens = upper.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            tokens.lastOrNull { isWellFormed(it) }?.let { return it }
        }
        return upper.filter(::isAsciiAlnum).take(length)
    }

    /** Six characters from the invite alphabet: worth sending to `join_couple`. */
    fun isWellFormed(code: String): Boolean = code.length == length && code.all { it in alphabet }

    const val lookalikeMessage = "Invite codes never use 0, O, 1 or I. Check the code and try again."

    /** Inline hint for a complete code that can't be valid (it has a 0, O, 1 or I), else null. */
    fun problem(code: String): String? = if (code.length == length && !isWellFormed(code)) lookalikeMessage else null
}
