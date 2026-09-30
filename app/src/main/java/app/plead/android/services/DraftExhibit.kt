// Port of ArgueWin/Services/DraftExhibit.swift.
package app.plead.android.services

import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** An exhibit being assembled on-device before filing (FileCase / Defence). */
data class DraftExhibit(
    val id: UUID = UUID.randomUUID(),
    val type: ExhibitType,
    val caption: String,
    /** Text quote or receipt line ("12 Sept, 21:14: said he'd be home by 9"). */
    val body: String? = null,
    /** JPEG data for photo/screenshot exhibits. */
    val imageData: ByteArray? = null,
    /** Optional date/time the evidence refers to (receipts, screenshots) → `exhibits.occurred_at`. */
    val occurredAt: Instant? = null,
) {
    val needsUpload: Boolean get() = (type == ExhibitType.photo || type == ExhibitType.screenshot) && imageData != null

    // Swift `Hashable`: byte-wise equality for the image data.
    override fun equals(other: Any?): Boolean {
        if (other !is DraftExhibit) return false
        val a = imageData
        val b = other.imageData
        val sameData = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
        return other.id == id && other.type == type && other.caption == caption && other.body == body &&
            other.occurredAt == occurredAt && sameData
    }

    override fun hashCode(): Int = listOf(id, type, caption, body, occurredAt, imageData?.contentHashCode()).hashCode()

    companion object {
        /** Only receipts and screenshots carry a date in the MVP. */
        fun supportsDate(type: ExhibitType): Boolean = type == ExhibitType.receipt || type == ExhibitType.screenshot

        /** Receipt body formatting: "12 Sept, 21:14: <line>" (en_GB `d MMM HH:mm`). */
        fun receiptLine(date: Instant, text: String, zone: ZoneId = ZoneId.systemDefault()): String {
            // en_GB's localized "d MMM, HH:mm" (Foundation's template), with September abbreviated "Sept".
            val formatted = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK).withZone(zone).format(date)
                .replace("Sep ", "Sept ")
            return "$formatted: $text"
        }
    }
}

/** Labels are assigned in order: A, B, C… Z, AA, AB… (each side has its own sequence; no cap). */
@Suppress("UnusedReceiverParameter")
fun List<DraftExhibit>.label(at: Int): ExhibitLabel = ExhibitLabel.at(at)
