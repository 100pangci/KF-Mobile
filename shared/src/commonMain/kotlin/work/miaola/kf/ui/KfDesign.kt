@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One spacing rhythm for every screen. Values are deliberately small so the
 * density stays close to Material defaults while cards stop drifting apart.
 */
object KfSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val ScreenGutter = 16.dp
    val ListBottom = 32.dp
    val FabBottom = 96.dp
}

/** Content motion shared by common screens; navigation motion stays in KfMotion. */
object KfMotionSpec {
    const val Quick = 140
    const val Standard = 220
    const val Gentle = 320
}

val KfCardShape = RoundedCornerShape(18.dp)
val KfTileShape = RoundedCornerShape(14.dp)
val KfFieldShape = RoundedCornerShape(14.dp)
val KfPillShape = RoundedCornerShape(50)

/** The single card recipe: low tonal surface, hairline outline, roomy padding. */
@Composable
fun KfCard(
    modifier: Modifier = Modifier,
    shape: Shape = KfCardShape,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    contentPadding: PaddingValues = PaddingValues(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Section heading with either an icon or a small accent bar, never both. */
@Composable
fun KfSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = KfSpacing.Xs, bottom = KfSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            title,
            Modifier.weight(1f).padding(start = KfSpacing.Sm),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        trailing?.invoke(this)
    }
}

/** Skeleton placeholder used while a destination has no cached page yet. */
@Composable
fun KfSkeletonScreen(message: String = "正在加载…", rows: Int = 5, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "kf-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "kf-skeleton-alpha",
    )
    Column(
        modifier.fillMaxWidth().padding(horizontal = KfSpacing.ScreenGutter, vertical = KfSpacing.Md)
            .semantics { contentDescription = message },
        verticalArrangement = Arrangement.spacedBy(KfSpacing.Lg),
    ) {
        SkeletonBar(Modifier.fillMaxWidth(0.42f).height(24.dp), alpha)
        SkeletonBar(Modifier.fillMaxWidth(0.66f).height(13.dp), alpha)
        repeat(rows.coerceAtLeast(1)) { index ->
            Surface(shape = KfCardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(KfSpacing.Sm),
                ) {
                    SkeletonBar(Modifier.fillMaxWidth(if (index % 2 == 0) 0.3f else 0.4f).height(11.dp), alpha)
                    SkeletonBar(Modifier.fillMaxWidth(0.92f).height(15.dp), alpha)
                    SkeletonBar(Modifier.fillMaxWidth(if (index % 2 == 0) 0.55f else 0.7f).height(11.dp), alpha)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier, alpha: Float) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f + alpha * 0.1f)),
    )
}

/** Calm empty / error state shared by list and task screens. */
@Composable
fun KfEmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = KfSpacing.Xxl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KfSpacing.Md),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (!message.isNullOrBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Compact metric used by account, growth and bank headers. */
@Composable
fun KfStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small tonal label for floors, states and metadata. */
@Composable
fun KfPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(modifier, shape = KfPillShape, color = container) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(13.dp), tint = contentColor)
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

/**
 * Card whose body expands with a short height/fade animation. Used by forms,
 * FAQ entries, bank sections and other disclosure rows.
 */
@Composable
fun KfExpandableCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md),
    content: @Composable ColumnScope.() -> Unit,
) {
    KfCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(KfSpacing.Sm))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (!expanded && !subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开或收起")
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(KfMotionSpec.Standard)) + fadeIn(tween(KfMotionSpec.Quick)),
            exit = shrinkVertically(tween(KfMotionSpec.Quick)) + fadeOut(tween(KfMotionSpec.Quick)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(KfSpacing.Md), content = content)
            }
        }
    }
}
