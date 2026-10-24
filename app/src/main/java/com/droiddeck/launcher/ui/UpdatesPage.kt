package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.AppUpdates.Offer
import com.droiddeck.launcher.update.AppUpdates.Release

class UpdatesState(
    val catalog: AppUpdates.Catalog? = null,
    val checking: Boolean = false,
    val error: String? = null,
    val stage: String? = null,
    val percent: Int = -1,
    val askPermission: Boolean = false,
) {
    val hasUpdate: Boolean get() = AppUpdates.hasUpdate(catalog)
}

class UpdatesActions(
    val onCheck: () -> Unit = {},
    val onInstall: (Release) -> Unit = {},
    val onAllowInstalls: () -> Unit = {},
    val onDismissPermission: () -> Unit = {},
)

@Composable
internal fun UpdatesPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val u = s.updates
    val ua = a.updates
    val me = remember { AppUpdates.installed() }
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        PageHeader(stringResource(R.string.upd_title)) {
            Text(
                stringResource(R.string.upd_build, me.version, s.buildLabel), fontSize = 12.5.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            val checked = u.catalog?.checkedAt?.takeIf { it > 0 }
            if (!LocalNarrowPane.current) Text(
                if (u.checking) stringResource(R.string.common_checking) else if (checked != null) stringResource(R.string.upd_checked, ago(checked).replaceFirstChar { it.lowercase() }) else stringResource(R.string.upd_not_checked),
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.store_check_updates), busy = u.checking, enabled = !u.checking && u.stage == null, onClick = ua.onCheck)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val stacked = LocalNarrowPane.current || maxWidth < 600.dp
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                if (stacked) {
                    StatusPanel(s, u, ua, me)
                    Box(Modifier.height(18.dp))
                    ChannelLabel()
                    ChannelPicker(u)
                } else {
                    ChannelLabel()
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.weight(1f)) { ChannelPicker(u) }
                        Box(Modifier.weight(1.15f)) { StatusPanel(s, u, ua, me) }
                    }
                }
            }
        }
    }
    if (u.askPermission) AlertDialog(
        onDismissRequest = ua.onDismissPermission,
        title = { Text(stringResource(R.string.upd_perm_title)) },
        text = {
            Text(
                stringResource(R.string.upd_perm_text),
            )
        },
        confirmButton = { TextButton(onClick = ua.onAllowInstalls) { Text(stringResource(R.string.upd_open_settings)) } },
        dismissButton = { TextButton(onClick = ua.onDismissPermission) { Text(stringResource(R.string.upd_not_now)) } },
    )
}

@Composable
private fun StatusPanel(s: FrontEndState, u: UpdatesState, ua: UpdatesActions, me: AppUpdates.Installed) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val catalog = u.catalog
    val release = catalog?.stable
    val offer = if (me.updatable) catalog?.let { AppUpdates.offer(it, me) } else null
    val offered = release != null && offer == Offer.UPDATE
    val noApk = offered && release?.apk == null
    class Look(val tint: Color, val status: String, val headline: String, val detail: String?)
    val look = when {
        !me.updatable -> Look(colors.onSurfaceVariant, stringResource(R.string.upd_signed_status), stringResource(R.string.upd_signed_headline),
            stringResource(R.string.upd_signed_detail))
        catalog == null -> Look(colors.onSurfaceVariant, if (u.checking) stringResource(R.string.common_checking) else stringResource(R.string.upd_not_checked), stringResource(R.string.upd_title), null)
        offer == Offer.NONE -> Look(colors.onSurfaceVariant, stringResource(R.string.upd_none_status), stringResource(R.string.upd_none_headline),
            stringResource(R.string.upd_none_detail, AppUpdates.REPO))
        noApk -> Look(colors.onSurfaceVariant, stringResource(R.string.upd_blocked_status), newBuild(release!!), stringResource(R.string.upd_block_no_apk))
        offer == Offer.UPDATE -> Look(AttentionAmber, stringResource(R.string.upd_available), newBuild(release!!), null)
        offer == Offer.AHEAD -> Look(pal.signal, stringResource(R.string.upd_ahead_status), stringResource(R.string.upd_ahead_headline),
            stringResource(R.string.upd_ahead_detail))
        else -> Look(pal.good, stringResource(R.string.upd_current_status), stringResource(R.string.upd_current_headline, stringResource(R.string.upd_stable)), null)
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(look.tint))
            Text(look.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.tint)
            if (offered) Text(stringResource(R.string.upd_dot_ago, ago(release!!.publishedAt)), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Text(look.headline, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 4.dp))
        if (offered) {
            ReleaseNotes(changeTitle(release!!.title.ifBlank { release.tag }), release.summary, release.tag)
        }
        if (look.detail != null) Text(look.detail, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        if (u.error != null) Text(u.error, fontSize = 13.sp, color = pal.error, modifier = Modifier.padding(top = 12.dp))
        val installable = release?.apk != null && !s.sessionRunning
        val button = release?.apk?.size?.takeIf { it > 0 }?.let { " · ${megabytes(it)}" }.orEmpty()
        Box(Modifier.padding(top = 14.dp)) {
            when {
                u.stage != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (u.percent >= 0) stringResource(R.string.upd_stage_percent, u.stage, u.percent) else stringResource(R.string.upd_stage, u.stage), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    if (u.percent >= 0) LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
                offered && !noApk -> PrimaryButton(stringResource(R.string.upd_update_button, button), enabled = installable, main = true) { ua.onInstall(release!!) }
            }
        }
        if (s.sessionRunning && offered) {
            Text(stringResource(R.string.upd_stop_session), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ReleaseNotes(title: String, notes: String, key: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var cut by remember(key) { mutableStateOf(false) }
    Text(
        title, fontSize = 15.sp, color = colors.onBackground, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) cut = true }, modifier = Modifier.padding(top = 10.dp),
    )
    if (notes.isNotBlank()) Text(
        notes, fontSize = 13.5.sp, color = colors.onSurfaceVariant, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) cut = true }, modifier = Modifier.padding(top = 4.dp),
    )
    if (cut || open) {
        val src = remember { MutableInteractionSource() }
        val hot = rememberHot(src)
        val toggle = { open = !open }
        Text(
            if (open) stringResource(R.string.upd_less) else stringResource(R.string.upd_more), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
            modifier = Modifier.padding(top = 2.dp).offset(x = (-6).dp).paneItem("notes:more")
                .clip(Shape12).glideBorder(hot, Shape12, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = toggle)
                .controllerConfirm(onClick = toggle)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun newBuild(r: Release) = stringResource(R.string.upd_new_stable, r.version)

private fun changeTitle(t: String): String =
    t.replace(Regex("""^[a-z]+(\([^)]*\))?!?:\s*"""), "").replaceFirstChar { it.uppercase() }

@Composable
private fun megabytes(bytes: Long) = stringResource(R.string.upd_mb, ((bytes + 524_288) / 1_048_576).toInt())

@Composable
private fun ChannelLabel() {
    Text(
        stringResource(R.string.upd_channel), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChannelPicker(u: UpdatesState) {
    val stable = u.catalog?.stable
    ChannelCard(
        Icons.Outlined.Verified, stringResource(R.string.upd_stable), "github.com/${AppUpdates.REPO}",
        stable?.let { stringResource(R.string.upd_version_ago, it.version, ago(it.publishedAt)) }, selected = true,
    ) {}
}

@Composable
private fun ChannelCard(
    icon: ImageVector, title: String, hint: String, latest: String?, selected: Boolean,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val fill = if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().paneItem("channel:$title")
            .clip(Shape14).background(colors.surface).background(fill).glideBorder(hot, Shape14, pal.signal, pal.line)
            .alpha(if (enabled) 1f else 0.55f)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp).clip(Shape12).background(if (selected) pal.signal.copy(alpha = 0.18f) else colors.surfaceVariant)) {
            Icon(icon, contentDescription = null, tint = if (selected) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                if (latest != null) Text(latest, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioDot(selected)
    }
}

@Composable
private fun RadioDot(selected: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val pal = LocalPalette.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(CircleShape).border(2.dp, if (selected) pal.signal else pal.line2, CircleShape)) {
        if (selected) Box(Modifier.size(size / 2).clip(CircleShape).background(pal.signal))
    }
}

@Composable
private fun ago(millis: Long): String {
    if (millis <= 0) return stringResource(R.string.upd_while_ago)
    if (System.currentTimeMillis() - millis in 0 until 60_000) return stringResource(R.string.upd_just_now)
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
