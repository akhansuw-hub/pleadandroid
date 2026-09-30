// The `ExhibitTile(label:draft:ownerRole:)` initialiser of ArgueWin/DesignSystem/Components.swift, owed by wave 2b
// (it needs `DraftExhibit` from services/). A new file so Components.kt stays untouched.
package app.plead.android.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.Role
import app.plead.android.services.DraftExhibit

/** [ExhibitTile] for an exhibit still being assembled on-device (FileCase / Defence). */
@Composable
fun ExhibitTile(label: ExhibitLabel, draft: DraftExhibit, modifier: Modifier = Modifier, ownerRole: Role? = null) {
    ExhibitTile(
        label = label,
        type = draft.type,
        caption = draft.caption,
        modifier = modifier,
        body_ = draft.body,
        imageData = draft.imageData,
        occurredAt = draft.occurredAt,
        ownerRole = ownerRole,
    )
}
