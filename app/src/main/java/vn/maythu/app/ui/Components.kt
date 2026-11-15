package vn.maythu.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import vn.maythu.app.data.Channel
import vn.maythu.app.data.Kind
import vn.maythu.app.data.Txt
import vn.maythu.app.player.Playback
import vn.maythu.app.player.Status

/**
 * Ô bấm được, dùng chung cho cảm ứng và remote TV:
 * khi được chọn bằng remote thì phóng to nhẹ và có viền vàng.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = Pal.Panel,
    focusedColor: Color = Pal.Panel2,
    scaleOnFocus: Float = 1.05f,
    borderOnFocus: Boolean = true,
    onFocused: (() -> Unit)? = null,
    inner: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, label = "focusScale")
    LaunchedEffect(focused) {
        if (focused) onFocused?.invoke()
    }
    val base = modifier
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .background(if (focused) focusedColor else color)
    val withBorder = if (focused && borderOnFocus) base.border(2.dp, Pal.Amber, shape) else base
    Box(
        modifier = withBorder.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick,
        ),
    ) {
        inner(focused)
    }
}

/** Logo kênh; khi chưa có ảnh thì hiện chữ viết tắt. */
@Composable
fun LogoTile(
    ch: Channel,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    textSize: TextUnit = 16.sp,
    pad: Dp = 6.dp,
) {
    var loaded by remember(ch.logo) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(if (loaded) Pal.LogoBg else Pal.Panel2),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                text = Txt.initials(ch.name),
                color = Pal.Muted,
                fontWeight = FontWeight.Bold,
                fontSize = textSize,
            )
        }
        if (ch.logo.startsWith("http")) {
            AsyncImage(
                model = ch.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}

/** Ba vạch nhảy cho biết kênh đang phát. */
@Composable
fun Eq(modifier: Modifier = Modifier, color: Color = Pal.Amber, moving: Boolean = true) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(660), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.5f, 0.95f, infiniteRepeatable(tween(430), RepeatMode.Reverse), label = "c")
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (h in listOf(a, b, c)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (moving) h else 0.55f)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
    }
}

/** Thang dò sóng: kim đỏ chỉ vị trí kênh đang phát trong danh sách. */
@Composable
fun Dial(position: Float?, modifier: Modifier = Modifier) {
    val pos by animateFloatAsState(position ?: 0f, tween(550), label = "needle")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val step = 8.dp.toPx()
        val stroke = 1.dp.toPx()
        var x = 0f
        var i = 0
        while (x <= w) {
            val major = i % 6 == 0
            drawLine(
                color = Pal.Muted.copy(alpha = if (major) 0.55f else 0.25f),
                start = Offset(x, 0f),
                end = Offset(x, if (major) h * 0.8f else h * 0.42f),
                strokeWidth = stroke,
            )
            x += step
            i++
        }
        if (position != null) {
            val nx = pos.coerceIn(0f, 1f) * w
            drawLine(Pal.Red.copy(alpha = 0.3f), Offset(nx, 0f), Offset(nx, h), strokeWidth = 7.dp.toPx())
            drawLine(Pal.Red, Offset(nx, 0f), Offset(nx, h), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Biểu tượng thương hiệu nhỏ (máy thu có mặt số và kim). */
@Composable
fun BrandMark(size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 108f
        val body = androidx.compose.ui.geometry.Size(60f * s, 42f * s)
        drawLine(Pal.Muted, Offset(46f * s, 38f * s), Offset(38f * s, 27f * s), strokeWidth = 4f * s)
        drawLine(Pal.Muted, Offset(62f * s, 38f * s), Offset(70f * s, 27f * s), strokeWidth = 4f * s)
        drawRoundRect(
            color = Pal.Panel2,
            topLeft = Offset(24f * s, 38f * s),
            size = body,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * s),
        )
        drawRoundRect(
            color = Pal.Amber,
            topLeft = Offset(24f * s, 38f * s),
            size = body,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f * s),
        )
        drawLine(Pal.Muted, Offset(31f * s, 50f * s), Offset(65f * s, 50f * s), strokeWidth = 2f * s)
        drawLine(Pal.Red, Offset(48f * s, 43f * s), Offset(48f * s, 58f * s), strokeWidth = 4f * s)
        drawCircle(
            color = Pal.Amber,
            radius = 6f * s,
            center = Offset(72f * s, 63f * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f * s),
        )
        drawLine(Pal.Muted, Offset(31f * s, 64f * s), Offset(57f * s, 64f * s), strokeWidth = 4f * s)
        drawLine(Pal.Muted, Offset(31f * s, 71f * s), Offset(50f * s, 71f * s), strokeWidth = 4f * s)
    }
}

/** Thẻ kênh dạng ô (lưới và hàng ngang). */
@Composable
fun ChannelCard(
    ch: Channel,
    isTv: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = Playback.current?.id == ch.id && Playback.status != Status.IDLE
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp), color = Pal.Panel) { _ ->
        Column(Modifier.padding(10.dp)) {
            LogoTile(
                ch = ch,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (ch.kind == Kind.TV) 16f / 10f else 1.25f),
                textSize = if (isTv) 22.sp else 20.sp,
                pad = 10.dp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = ch.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isTv) 15.sp else 14.sp,
                color = if (playing) Pal.Amber else Pal.Text,
            )
            Text(
                text = ch.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = Pal.Muted,
            )
        }
        if (playing) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Pal.Ink.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Eq(moving = Playback.status == Status.PLAYING)
            }
        }
    }
}

/** Dòng kênh/đài trong danh sách dọc. */
@Composable
fun ChannelRow(
    ch: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    fav: Boolean = false,
    onFav: (() -> Unit)? = null,
) {
    val playing = Playback.current?.id == ch.id && Playback.status != Status.IDLE
    FocusBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (playing) Pal.AmberDim else Color.Transparent,
        focusedColor = Pal.Panel2,
        scaleOnFocus = 1.02f,
    ) { _ ->
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                Text(
                    text = number.toString(),
                    color = Pal.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.width(34.dp),
                )
            }
            LogoTile(ch, Modifier.size(48.dp), textSize = 15.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = ch.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = if (playing) Pal.Amber else Pal.Text,
                )
                Text(
                    text = ch.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.5.sp,
                    color = Pal.Muted,
                )
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                Eq(moving = Playback.status == Status.PLAYING)
            }
            if (onFav != null) {
                Spacer(Modifier.width(4.dp))
                RoundIcon(
                    icon = if (fav) Ic.Star else Ic.StarBorder,
                    label = if (fav) "Bỏ khỏi yêu thích" else "Thêm vào yêu thích",
                    onClick = onFav,
                    tint = if (fav) Pal.Amber else Pal.Muted,
                    size = 40.dp,
                    filled = false,
                )
            }
        }
    }
}

/** Nút tròn có biểu tượng, chọn được bằng remote. */
@Composable
fun RoundIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    filled: Boolean = false,
    tint: Color = if (filled) Pal.AmberInk else Pal.Text,
    iconSize: Dp = size * 0.5f,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = if (filled) Pal.Amber else Color.Transparent,
        focusedColor = if (filled) Pal.Amber else Pal.Panel2,
        scaleOnFocus = 1.12f,
    ) { _ ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .align(Alignment.Center),
        )
    }
}

/** Nút viên thuốc có chữ (nhóm kênh, lựa chọn). */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(50)
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = if (selected) Pal.AmberDim else Color.Transparent,
        focusedColor = Pal.Panel2,
        scaleOnFocus = 1.06f,
    ) { _ ->
        Row(
            modifier = Modifier
                .border(1.dp, if (selected) Pal.Amber else Pal.Line, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (selected) Pal.Amber else Pal.Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (selected) Pal.Amber else Pal.Text, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** Nút chữ chính (nền vàng) hoặc phụ (viền). */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = if (primary) Pal.Amber else Color.Transparent,
        focusedColor = if (primary) Color(0xFFF6C362) else Pal.Panel2,
        scaleOnFocus = 1.06f,
    ) { _ ->
        Row(
            modifier = Modifier
                .then(if (primary) Modifier else Modifier.border(1.dp, Pal.Line, shape))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (primary) Pal.AmberInk else Pal.Text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (primary) Pal.AmberInk else Pal.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = Pal.Muted)
    }
}

@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Pal.Amber, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Pal.Muted, fontSize = 14.sp)
    }
}

@Composable
fun MessageBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.padding(24.dp)) {
        Text(title, color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Pal.Muted, fontSize = 14.sp)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextAction(action, onAction, icon = Ic.Refresh)
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun openUrl(ctx: Context, url: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}
