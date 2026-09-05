package dev.quietinbox.core.designsystem.components

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.quietinbox.core.designsystem.theme.AvatarPalette
import dev.quietinbox.core.designsystem.theme.AvatarPaletteDark
import dev.quietinbox.core.designsystem.theme.LocalQuietDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Monogram avatar with a stable colour derived from [key]; never shows a source photo. */
@Composable
fun MonogramAvatar(
    label: String?,
    key: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val palette = if (LocalQuietDark.current) AvatarPaletteDark else AvatarPalette
    val (bg, fg) = palette[(key.hashCode() and 0x7fffffff) % palette.size]
    val initials = monogram(label)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .semantics { contentDescription = label ?: "" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = fg,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp),
        )
    }
}

fun monogram(label: String?): String {
    val t = label?.trim().orEmpty()
    if (t.isEmpty()) return "?"
    val first = t.codePointAt(0)
    val firstStr = String(Character.toChars(first))
    if (Character.isIdeographic(first)) return firstStr
    val parts = t.split(' ', '　').filter { it.isNotBlank() }
    return if (parts.size >= 2) {
        (parts[0].take(1) + parts[1].take(1)).uppercase()
    } else {
        t.take(2).uppercase()
    }
}

/** Loads a launcher icon for [packageName] off the main thread, memoised per composition. */
@Composable
fun rememberAppIcon(packageName: String, sizePx: Int = 96): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }
    return icon
}

@Composable
fun rememberAppLabel(packageName: String): String {
    val context = LocalContext.current
    val label by produceState(initialValue = packageName, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName.substringAfterLast('.'))
        }
    }
    return label
}

/** Small source badge: the app icon clipped to a circle, or a fallback monogram. */
@Composable
fun SourceBadge(packageName: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val icon = rememberAppIcon(packageName)
    val label = rememberAppLabel(packageName)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = label,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        MonogramAvatar(label = label, key = packageName, modifier = modifier, size = size)
    }
}

@Suppress("unused")
private fun PackageManager.hasPackage(name: String): Boolean = runCatching { getPackageInfo(name, 0) }.isSuccess

@Suppress("unused")
private fun Canvas.noop() = Unit
