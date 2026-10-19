package app.plead.android.services

/**
 * Display-name rules shared by onboarding and the Us tab's "Edit name" (amendment bj):
 * trimmed, at most 30 characters, never empty.
 */
object DisplayName {
    const val LIMIT = 30

    fun clean(raw: String): String = raw.trim().take(LIMIT)

    fun isValid(raw: String): Boolean = clean(raw).isNotEmpty()
}
