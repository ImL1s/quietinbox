package dev.quietinbox.core.designsystem.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.theme.QualityColors
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.IdentityConfidence
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.MediaState

data class Labelled(val text: String, val icon: ImageVector, val tint: Color)

@Composable
fun identityLabel(confidence: IdentityConfidence): Labelled = when (confidence) {
    IdentityConfidence.VERIFIED_SOURCE_ID -> Labelled(stringResource(R.string.identity_verified), Icons.Outlined.Verified, QualityColors.verified)
    IdentityConfidence.INFERRED_FROM_STREAM -> Labelled(stringResource(R.string.identity_inferred), Icons.Outlined.Link, QualityColors.inferred)
    IdentityConfidence.UNRESOLVED -> Labelled(stringResource(R.string.identity_unresolved), Icons.Outlined.HelpOutline, QualityColors.uncertain)
}

@Composable
fun mediaLabel(state: MediaState): Labelled? = when (state) {
    MediaState.NONE -> null
    MediaState.PENDING -> Labelled(stringResource(R.string.conv_media_pending), Icons.Outlined.HourglassEmpty, QualityColors.inferred)
    MediaState.LOCAL_COPY -> Labelled(stringResource(R.string.conv_media_local), Icons.Outlined.Image, QualityColors.verified)
    MediaState.PLACEHOLDER_ONLY -> Labelled(stringResource(R.string.conv_media_placeholder), Icons.Outlined.HideImage, QualityColors.uncertain)
    MediaState.URI_EXPIRED -> Labelled(stringResource(R.string.conv_media_expired), Icons.Outlined.BrokenImage, QualityColors.uncertain)
    MediaState.PERMISSION_DENIED -> Labelled(stringResource(R.string.conv_media_denied), Icons.Outlined.Lock, QualityColors.uncertain)
    MediaState.TOO_LARGE -> Labelled(stringResource(R.string.conv_media_too_large), Icons.Outlined.PhotoSizeSelectLarge, QualityColors.uncertain)
    MediaState.DISABLED_BY_USER -> Labelled(stringResource(R.string.conv_media_disabled), Icons.Outlined.VisibilityOff, QualityColors.inferred)
    MediaState.FAILED -> Labelled(stringResource(R.string.conv_media_failed), Icons.Outlined.BrokenImage, QualityColors.failed)
}

@Composable
fun originLabel(origin: CaptureOrigin): String? = when (origin) {
    CaptureOrigin.LIVE -> null
    CaptureOrigin.ACTIVE_RESYNC -> stringResource(R.string.conv_origin_resync)
    CaptureOrigin.SYNTHETIC -> stringResource(R.string.conv_origin_synthetic)
    CaptureOrigin.REPLAY -> stringResource(R.string.conv_origin_replay)
}

@Composable
fun listenerStateLabel(state: ListenerState): String = when (state) {
    ListenerState.NOT_GRANTED -> stringResource(R.string.state_not_granted)
    ListenerState.GRANTED_DISCONNECTED -> stringResource(R.string.state_granted_disconnected)
    ListenerState.CONNECTED -> stringResource(R.string.state_connected)
    ListenerState.PAUSED -> stringResource(R.string.state_paused)
    ListenerState.RECONNECTING -> stringResource(R.string.state_reconnecting)
    ListenerState.DEGRADED -> stringResource(R.string.state_degraded)
}

@Composable
fun gapReasonLabel(reason: GapReason): String = when (reason) {
    GapReason.LISTENER_DISCONNECTED -> stringResource(R.string.gap_reason_disconnected)
    GapReason.PROCESS_RESTART -> stringResource(R.string.gap_reason_restart)
    GapReason.NOT_GRANTED -> stringResource(R.string.gap_reason_not_granted)
    GapReason.PAUSED_BY_USER -> stringResource(R.string.gap_reason_paused)
    GapReason.QUEUE_OVERFLOW -> stringResource(R.string.gap_reason_overflow)
    GapReason.BEFORE_FIRST_UNLOCK -> stringResource(R.string.gap_reason_first_unlock)
    GapReason.MAINTENANCE -> stringResource(R.string.gap_reason_maintenance)
    GapReason.COLD_START -> stringResource(R.string.gap_reason_cold_start)
    GapReason.UNKNOWN -> stringResource(R.string.gap_reason_unknown)
}
