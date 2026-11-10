package dev.contactguard.dialer

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every shared size lives here. */
object Dimens {
    val gutter = 16.dp
    val rowGap = 12.dp
    val avatar = 40.dp
    val avatarLarge = 112.dp
    val key = 76.dp
    val searchHeight = 56.dp
    val callButton = 72.dp
    val tile = 72.dp
}

/** Google Phone colours its letter avatars from a fixed set of tonal pairs. */
private val avatarLight = listOf(
    Color(0xFFD3E3FD) to Color(0xFF041E49), Color(0xFFC4EED0) to Color(0xFF072711), Color(0xFFFFDBCB) to Color(0xFF341100),
    Color(0xFFE9DDFF) to Color(0xFF22005D), Color(0xFFFFD8E4) to Color(0xFF3E001D), Color(0xFFC2E8FF) to Color(0xFF001E2C),
)
private val avatarDark = listOf(
    Color(0xFF0842A0) to Color(0xFFD3E3FD), Color(0xFF0F5223) to Color(0xFFC4EED0), Color(0xFF7A2E00) to Color(0xFFFFDBCB),
    Color(0xFF4F378A) to Color(0xFFE9DDFF), Color(0xFF7D2950) to Color(0xFFFFD8E4), Color(0xFF004C69) to Color(0xFFC2E8FF),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFA8C7FA))
        else -> lightColorScheme(primary = Color(0xFF0B57D0))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Brand colours that stay fixed across themes: call green and hang-up red, as in the stock dialer. */
object CallColors {
    val answer = Color(0xFF1E8E3E)
    val hangup = Color(0xFFDC362E)
    val onCall = Color.White
}

@Composable
fun Avatar(name: String, photo: String?, size: Dp = Dimens.avatar, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { withContext(Dispatchers.IO) { Phone.photo(ctx, it) } }
    }
    val pal = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) avatarDark else avatarLight
    val (bg, fg) = pal[(name.hashCode() and 0x7fffffff) % pal.size]
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
            color = fg, fontSize = (size.value * 0.42f).sp, style = MaterialTheme.typography.titleMedium,
        )
    }
}
