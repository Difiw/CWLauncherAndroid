package ru.cw.launcher.ui

import android.app.ActivityManager
import ru.cw.launcher.engine.compareVersions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import ru.cw.launcher.engine.SavedStyle
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import ru.cw.launcher.CwApplication
import ru.cw.launcher.R
import ru.cw.launcher.engine.Account
import ru.cw.launcher.engine.AccountType
import ru.cw.launcher.engine.AppPaths
import ru.cw.launcher.engine.CwLog
import ru.cw.launcher.engine.Lang
import ru.cw.launcher.engine.CwSettings
import ru.cw.launcher.engine.Engine
import ru.cw.launcher.engine.formatBytes
import ru.cw.launcher.engine.formatEta
import ru.cw.launcher.engine.formatSpeed
import ru.cw.launcher.engine.Phase
import ru.cw.launcher.engine.Screen
import ru.cw.launcher.engine.Skins
import ru.cw.launcher.engine.UiState
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipFile
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Panel: Color get() = Tc(0xD2081224)
private val Panel2: Color get() = Tc(0xE6162744)
private val Field: Color get() = Tc(0xCC101A2C)
private val Line: Color get() = Tc(0xBE4C96FF)
private val TextMain: Color get() = Tc(0xFFF4F7FB)
private val Muted: Color get() = Tc(0xFF9BB0D0)
private val Green: Color get() = Tc(0xFF3DDC97)
private val Amber: Color get() = Tc(0xFFF0B429)
private val Red: Color get() = Tc(0xFFFF6B6B)
private val Blue: Color get() = Tc(0xFF3B82F6)
private val BlueDeep: Color get() = Tc(0xFF2F6FE0)
private val CardShape: RoundedCornerShape get() = RoundedCornerShape(Pal.corner.dp)

// The settings screen is rebuilt when the palette changes; this keeps the open tab.
private var lastSettingsTab = 0

/** True while the "which build to launch" chooser is open (developer mode). */
private var chooseBuild by mutableStateOf(false)

class LauncherViewModel(app: android.app.Application) : AndroidViewModel(app) {
    val engine: Engine = (app as CwApplication).engine
}

@Composable
fun CwRoot(model: LauncherViewModel = viewModel()) {
    val state by model.engine.state.collectAsState()
    Lang.use(model.engine.cfg.resolvedLanguage())
    val context = LocalContext.current
    val systemDensity = LocalDensity.current
    BackHandler(enabled = state.screen != Screen.HOME && !state.booting) {
        model.engine.open(Screen.HOME)
    }
    val customBg = remember(state.lookTick) { loadBackground(Backgrounds.file(model.engine.cfg)?.absolutePath.orEmpty()) }
    val builtinBg = Backgrounds.item(model.engine.cfg).res
    val dimOther = !state.booting && state.screen != Screen.HOME && state.screen != Screen.VERSIONS
    val dimAlpha = if (!dimOther) 0 else {
        val percent = model.engine.cfg.backgroundDim.coerceIn(0, 80) * 100 / 80
        percent * 220 / 100
    }
    val requestedFontScale = model.engine.cfg.uiFontScale.coerceIn(85, 125) / 100f
    LaunchedEffect(state.lookTick, model.engine.cfg.launcherImmersive, model.engine.cfg.keepScreenAwake) {
        (context as? MainActivity)?.applyPhoneUi()
    }
    CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, systemDensity.fontScale * requestedFontScale)) {
    androidx.compose.runtime.key(Pal.version) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = customBg ?: painterResource(builtinBg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().then(if (dimOther && model.engine.cfg.uiBlur) Modifier.blur(12.dp) else Modifier),
            contentScale = ContentScale.Crop
        )
        if (dimAlpha > 0) Box(Modifier.fillMaxSize().background(Color(dimAlpha shl 24)))
        if (state.booting) {
            Splash(state.bootText, state.bootProgress)
        } else {
            when (state.screen) {
                Screen.HOME, Screen.VERSIONS -> Home(state, model.engine, context)
                Screen.SETTINGS -> SettingsPage(model.engine)
                Screen.ACCOUNT -> AccountPage(state, model.engine, context)
                Screen.NEWS -> NewsPage(state, model.engine)
                Screen.UPDATES -> UpdatesPage(state, model.engine)
                Screen.FILES -> FilesPage("Папка Minecraft", state, model.engine)
                Screen.MODS -> FilesPage("Моды", state, model.engine)
                Screen.SKIN -> SkinPage(model.engine)
                Screen.LOG -> LogPage(state, model.engine)
            }
            if (state.screen == Screen.VERSIONS) VersionPopup(state, model.engine)
        }
        if (!state.booting && state.updatePrompt && state.launcherRemote != null) {
            LauncherUpdateDialog(state.launcherRemote!!, model.engine, context)
        }
        if (chooseBuild) BuildChooser(model.engine, model.engine.devBuilds()) { chooseBuild = false }
        state.dialog?.let { dialog ->
            Notice(
                dialog.text,
                dialog.confirm,
                dialog.dismiss,
                { model.engine.confirmDialog() },
                { model.engine.dismissDialog() },
                dialog.alt
            ) { model.engine.altDialog() }
        }
        state.info?.let { text ->
            Notice(
                text,
                "Закрыть",
                null,
                { model.engine.dismissInfo() },
                { model.engine.dismissInfo() },
                "Экспорт логов"
            ) {
                model.engine.dismissInfo()
                model.engine.exportLogs()
            }
        }
    }
    }
    }
}

@Composable
private fun Splash(text: String, progress: Float) {
    val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("CWLauncher", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(text, color = Tc(0xFFC5D4EA), fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                color = Blue,
                trackColor = Tc(0xFF162033),
                drawStopIndicator = {}
            )
            Spacer(Modifier.height(10.dp))
            Text("$percent%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Home(state: UiState, engine: Engine, context: Context) {
    val server = state.server
    val clock = rememberClock()
    val fps = rememberFps(state.lookTick >= 0 && engine.cfg.showFps)
    val compact = state.lookTick >= 0 && engine.cfg.uiStyle == "compact"
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton("site") { openLink(context, "https://commonworld.ru/index.php") }
                Spacer(Modifier.width(6.dp))
                IconButton("send") { openLink(context, engine.cfg.telegramUrl) }
                Spacer(Modifier.width(6.dp))
                IconButton("chat") { openLink(context, engine.cfg.discordUrl) }
                Spacer(Modifier.width(6.dp))
                IconButton("user") { engine.open(Screen.ACCOUNT) }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    Text("CW ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Launcher", color = Tc(0xFF4C8DFF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text("Common World", color = Tc(0xFF9BB4E0), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(0.30f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Glass(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph("mountain", Blue, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Common World", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "●  ${server?.statusText() ?: "Статус сервера недоступен"}",
                        color = if (server?.online == true) Green else Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "IP: ${engine.cfg.serverIp}",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Tc(0x332F6FE0)).clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ip", engine.cfg.serverIp))
                                Toast.makeText(context, "Адрес скопирован", Toast.LENGTH_SHORT).show()
                            },
                            contentAlignment = Alignment.Center
                        ) { Glyph("copy", TextMain, Modifier.size(12.dp)) }
                    }
                }
                Glass(Modifier.weight(1f).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph("news", Blue, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Lang.t("news"), color = Blue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.news?.title ?: newsStatusText(state.newsStatus),
                        color = TextMain,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    // The text takes exactly the lines that fit the card and ends with an ellipsis; the font size stays.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val lineDp = with(LocalDensity.current) { 15.sp.toDp() }
                        val lines = (maxHeight / lineDp).toInt().coerceAtLeast(1)
                        if (!state.news?.text.isNullOrBlank()) {
                            Text(state.news?.text.orEmpty(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        Lang.t("more"),
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.End).clickable { engine.open(Screen.NEWS) }
                    )
                }
            }
            Column(
                Modifier.weight(0.40f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Glass(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MinecraftMark(Modifier.size(36.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (engine.cfg.commonWorld) "Сервер Common World" else state.versionLabel,
                                color = TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("Minecraft ${engine.cfg.minecraftVersion}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val tone = statusTone(state.phase)
                Text("●  ${readyCaption(state)}", color = tone, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.busy) {
                    Spacer(Modifier.height(4.dp))
                    if (state.xferTotal > 0L) {
                        TransferBar(state)
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                            color = Blue,
                            trackColor = Field,
                            drawStopIndicator = {}
                        )
                        Text(state.progressText, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!state.javaFound && !state.busy) {
                    Text(Lang.t("java_missing"), color = Amber, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                if (state.phase == Phase.RUNNING || state.phase == Phase.STARTING) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { engine.resumeGame() },
                            enabled = true,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BlueDeep,
                                contentColor = Color.White,
                                disabledContainerColor = BlueDeep,
                                disabledContentColor = Color.White
                            )
                        ) {
                            Text(
                                "Вернуться в игру",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = { engine.stopGame() },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Tc(0xFF3D4C63), contentColor = Color.White)
                        ) {
                            Text(Lang.t("close_game"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    val locked = state.phase == Phase.STARTING
                    Button(
                        onClick = {
                            val ready = state.phase == Phase.READY || state.phase == Phase.OFFLINE
                            if (engine.cfg.developerMode && ready && engine.devBuilds().isNotEmpty()) chooseBuild = true else engine.onMain()
                        },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonColor(state.phase, state.busy),
                            contentColor = Color.White,
                            disabledContainerColor = BlueDeep,
                            disabledContentColor = Color.White
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!state.busy && !locked) Glyph("play", Color.White, Modifier.size(14.dp))
                            if (!state.busy && !locked) Spacer(Modifier.width(8.dp))
                            Text(buttonLabel(state.phase, state.busy, state.detail), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (!state.busy) {
                    Spacer(Modifier.height(6.dp))
                    GhostButton(Lang.t("version"), "chevron") { engine.open(Screen.VERSIONS) }
                }
                Spacer(Modifier.weight(1f))
                if (!state.busy) {
                    Button(
                        onClick = { engine.open(Screen.SETTINGS) },
                        modifier = Modifier.width(180.dp).height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tc(0xCC1A2C4A), contentColor = TextMain)
                    ) {
                        Glyph("gear", TextMain, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Lang.t("settings"), fontSize = 13.sp)
                    }
                }
                val remote = state.launcherRemote
                if (remote != null && compareVersions(remote, CwLog.VERSION) > 0) {
                    TextButton(onClick = { engine.downloadLauncher() }) {
                        Text("Обновить лаунчер $remote", color = Amber, fontSize = 11.sp)
                    }
                }
            }
            Column(Modifier.weight(0.30f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Glass(Modifier.fillMaxWidth()) {
                    Text(Lang.t("status_build"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    CheckLine(state.mcReady, "Minecraft ${engine.cfg.minecraftVersion}")
                    CheckLine(state.fabricOk, state.fabricLabel)
                    CheckLine(state.modsOk && state.modsCount > 0, "Моды (${state.modsCount})")
                    CheckLine(state.buildVersion.isNotBlank(), "Версия модов: ${state.buildVersion.ifBlank { "—" }}")
                    CheckLine(server?.online == true, server?.statusText() ?: "Статус сервера недоступен", badIsRed = true)
                }
                Glass(Modifier.weight(1f).fillMaxWidth()) {
                    Text(Lang.t("latest"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    val note = state.notes.firstOrNull()
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tc(0x332F6FE0)).clickable { engine.open(Screen.UPDATES) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Glyph("info", Blue, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(note?.title ?: "Список обновлений пуст", color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Lang.t("more"), color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).clickable { engine.open(Screen.UPDATES) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(58.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(0.30f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionTile("folder", Lang.t("folder"), Modifier.weight(1f)) { engine.open(Screen.FILES) }
                    ActionTile("box", Lang.t("mods"), Modifier.weight(1f)) { engine.open(Screen.MODS) }
                    ActionTile("skin", Lang.t("skin"), Modifier.weight(1f)) { engine.open(Screen.SKIN) }
                }
                Spacer(Modifier.weight(0.40f))
                Spacer(Modifier.weight(0.30f))
            }
            if (state.busy) {
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { engine.open(Screen.SETTINGS) },
                        modifier = Modifier.width(168.dp).height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tc(0xCC1A2C4A), contentColor = TextMain)
                    ) {
                        Glyph("gear", TextMain, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Lang.t("settings"), fontSize = 13.sp)
                    }
                    Button(
                        onClick = { openLink(context, engine.cfg.supportUrl) },
                        modifier = Modifier.width(168.dp).height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tc(0xCC1A2C4A), contentColor = TextMain)
                    ) {
                        Glyph("info", TextMain, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Lang.t("support"), fontSize = 12.sp, maxLines = 1)
                    }
                }
            } else {
                Button(
                    onClick = { openLink(context, engine.cfg.supportUrl) },
                    modifier = Modifier.align(Alignment.Center).height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tc(0xCC1A2C4A), contentColor = TextMain)
                ) {
                    Glyph("info", TextMain, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Lang.t("support"), fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "●  ${server?.statusText() ?: Lang.t("foot_none")}",
                color = if (server?.online == true) Green else Muted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (state.modsOk) "●  ${Lang.t("mods_fresh")}" else "●  ${Lang.t("mods_check")}",
                color = if (state.modsOk) Green else Amber,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (engine.cfg.showFps) "$clock   $fps FPS   CWLauncher v${CwLog.VERSION}" else "$clock   CWLauncher v${CwLog.VERSION}",
                color = Muted,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(Lang.t("logs"), color = Muted, fontSize = 10.sp, modifier = Modifier.clickable { engine.open(Screen.LOG) })
        }
    }
}

@Composable
private fun SettingsPage(engine: Engine) {
    val cfg = engine.cfg
    val context = LocalContext.current
    val engineState = engine.state.collectAsState().value
    val totalMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt().coerceAtLeast(1024)
    }
    val capGb = (totalMb / 1024).coerceAtLeast(1)
    var tab by remember { mutableIntStateOf(lastSettingsTab) }
    lastSettingsTab = tab
    var ramGb by remember { mutableIntStateOf(if (cfg.ramMb > 0) ((cfg.ramMb + 512) / 1024).coerceIn(1, capGb) else (totalMb / 2048).coerceIn(1, capGb)) }
    var autoStart by remember { mutableStateOf(cfg.autoStart) }
    var autoBuild by remember { mutableStateOf(cfg.autoUpdateBuild) }
    var autoLauncher by remember { mutableStateOf(cfg.autoUpdateLauncher) }
    var launcherEvery by remember { mutableStateOf(cfg.launcherUpdateEvery) }
    var modsEvery by remember { mutableStateOf(cfg.modsUpdateEvery) }
    var timeUnit by remember { mutableStateOf(cfg.playTimeUnit) }
    var offline by remember { mutableStateOf(cfg.offlineAllowed) }
    var verify by remember { mutableStateOf(cfg.verifyBuildOnLaunch) }
    var backups by remember { mutableStateOf(cfg.modBackups) }
    var copies by remember { mutableIntStateOf(if (cfg.modBackupCount >= 10) 10 else if (cfg.modBackupCount <= 3) 3 else 5) }
    var dev by remember { mutableStateOf(cfg.developerMode) }
    var hideLogs by remember { mutableStateOf(cfg.hideBootLogs) }
    var bootStyle by remember { mutableStateOf(cfg.bootStyle) }
    var verbose by remember { mutableStateOf(cfg.verboseLogging) }
    var leader by remember { mutableStateOf(cfg.internetLeader) }
    var limitNet by remember { mutableStateOf(cfg.limitBandwidth) }
    var band by remember { mutableIntStateOf(cfg.bandwidthMbit) }
    var disk by remember { mutableStateOf(cfg.checkDiskSpace) }
    var proxy by remember { mutableStateOf(cfg.useSystemProxy) }
    var notes by remember { mutableStateOf(cfg.notificationsEnabled) }
    var language by remember { mutableStateOf(cfg.gameLanguage) }
    var ownJava by remember { mutableStateOf(cfg.javaPath.isNotBlank()) }
    var javaPath by remember { mutableStateOf(cfg.javaPath) }
    var jvm by remember { mutableStateOf(cfg.extraJvmArgs) }
    var width by remember { mutableStateOf(cfg.screenWidth.toString()) }
    var height by remember { mutableStateOf(cfg.screenHeight.toString()) }
    var fps by remember { mutableStateOf(cfg.framerateLimit.toString()) }
    var fullscreen by remember { mutableStateOf(cfg.screenFullscreen) }
    var vsync by remember { mutableStateOf(cfg.vsync) }
    var noFs by remember { mutableStateOf(cfg.disableFullscreenOpt) }
    var highPri by remember { mutableStateOf(cfg.highPriority) }
    var noRt by remember { mutableStateOf(cfg.disableRealtimeOpt) }
    var effects by remember { mutableStateOf(cfg.uiEffects) }
    var blur by remember { mutableStateOf(cfg.uiBlur) }
    var anim by remember { mutableStateOf(cfg.uiAnim) }
    var fade by remember { mutableStateOf(cfg.uiTransitions) }
    var style by remember { mutableStateOf(cfg.uiStyle) }
    var fontScale by remember { mutableIntStateOf(cfg.uiFontScale.coerceIn(85, 125)) }
    var immersive by remember { mutableStateOf(cfg.launcherImmersive) }
    var keepAwake by remember { mutableStateOf(cfg.keepScreenAwake) }
    var autoConn by remember { mutableStateOf(cfg.autoConnect) }
    var palette by remember { mutableStateOf(cfg.uiPalette) }
    var surface by remember { mutableIntStateOf(cfg.uiSurface) }
    var renderer by remember { mutableIntStateOf(cfg.rendererMode) }
    var gcSel by remember { mutableIntStateOf(cfg.gcMode) }
    var jitSel by remember { mutableIntStateOf(cfg.jitMode) }
    var directMb by remember { mutableIntStateOf(cfg.directMemoryMb) }
    var renderPct by remember { mutableIntStateOf(cfg.renderScale) }
    var shaderAuto by remember { mutableStateOf(cfg.shaderAutoScale) }
    var shaderPct by remember { mutableIntStateOf(cfg.shaderScale) }
    var optimization by remember { mutableIntStateOf(cfg.optimizationMode) }
    var screenOff by remember { mutableIntStateOf(cfg.screenOffMode) }
    var screenOffMin by remember { mutableIntStateOf(cfg.screenOffMinutes) }
    var showFps by remember { mutableStateOf(cfg.showFps) }
    var dim by remember { mutableIntStateOf((cfg.backgroundDim.coerceIn(0, 80) * 100 / 80).coerceIn(0, 100)) }
    var askWipe by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    var askReinstall by remember { mutableStateOf(false) }
    var bgTick by remember { mutableIntStateOf(0) }
    var cacheLabel by remember { mutableStateOf(human(folderBytes(AppPaths.cache) + folderBytes(AppPaths.tmp))) }
    var shaderTick by remember { mutableIntStateOf(0) }
    var pendingShader by remember { mutableStateOf<String?>(null) }
    var pendingBg by remember { mutableStateOf<File?>(null) }
    val working = engine.working.collectAsState().value
    val pickShaders = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) engine.importShaders(uris) { text ->
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            shaderTick++
        }
    }
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = copyBackground(context, uri) ?: return@rememberLauncherForActivityResult
        engine.cfg.backgroundPath = dest.absolutePath
        engine.cfg.bgAuto = false
        engine.cfg.save()
        engine.noteLook()
    }
    fun save() {
        val startOn = autoStart
        val syncOn = vsync
        val fpsOn = showFps
        val javaFile = if (ownJava) javaPath.trim() else ""
        engine.saveSettings {
            ramMb = ramGb * 1024
            autoStart = startOn
            autoUpdateBuild = autoBuild
            autoUpdateLauncher = autoLauncher
            launcherUpdateEvery = launcherEvery
            modsUpdateEvery = modsEvery
            playTimeUnit = timeUnit
            offlineAllowed = offline
            verifyBuildOnLaunch = verify
            modBackups = backups
            modBackupCount = copies
            developerMode = dev
            hideBootLogs = hideLogs
            verboseLogging = verbose
            internetLeader = leader
            limitBandwidth = limitNet
            bandwidthMbit = if (limitNet) band else 0
            checkDiskSpace = disk
            useSystemProxy = proxy
            notificationsEnabled = notes
            gameLanguage = language
            javaPath = javaFile
            extraJvmArgs = jvm
            screenWidth = width.toIntOrNull() ?: 1280
            screenHeight = height.toIntOrNull() ?: 720
            framerateLimit = fps.toIntOrNull() ?: 0
            screenFullscreen = fullscreen
            vsync = syncOn
            disableFullscreenOpt = noFs
            highPriority = highPri
            disableRealtimeOpt = noRt
            uiEffects = effects
            uiBlur = blur
            uiAnim = anim
            uiTransitions = fade
            uiStyle = style
            uiFontScale = fontScale
            launcherImmersive = immersive
            keepScreenAwake = keepAwake
            autoConnect = autoConn
            rendererMode = renderer
            gcMode = gcSel
            jitMode = jitSel
            directMemoryMb = directMb
            renderScale = renderPct
            shaderAutoScale = shaderAuto
            shaderScale = shaderPct
            optimizationMode = optimization
            screenOffMode = screenOff
            screenOffMinutes = screenOffMin
            showFps = fpsOn
            backgroundDim = dim * 80 / 100
        }
    }
    fun reset() {
        val defGb = (totalMb / 2048).coerceIn(1, capGb)
        ramGb = defGb
        autoStart = true
        autoBuild = true
        autoLauncher = true
        launcherEvery = "day"
        modsEvery = "day"
        timeUnit = "sec"
        offline = true
        verify = true
        backups = true
        copies = 5
        dev = false
        hideLogs = false
        verbose = false
        leader = false
        limitNet = false
        band = 0
        disk = true
        proxy = false
        notes = true
        language = ""
        ownJava = false
        javaPath = ""
        jvm = ""
        width = "1280"
        height = "720"
        fps = "240"
        fullscreen = false
        vsync = true
        noFs = false
        highPri = false
        noRt = false
        effects = true
        blur = true
        anim = true
        fade = false
        style = "standard"
        fontScale = 100
        immersive = true
        keepAwake = true
        showFps = false
        dim = 60
        engine.cfg.backgroundDim = 48
        engine.noteLook()
    }
    val every = listOf("day" to Lang.t("every_day"), "start" to Lang.t("every_start"), "manual" to Lang.t("every_manual"))
    val times = listOf("sec" to Lang.t("time_sec"), "min" to Lang.t("time_min"), "hour" to Lang.t("time_hour"), "day" to Lang.t("time_day"), "week" to Lang.t("time_week"))
    val bands = listOf(0 to Lang.t("band_none"), 5 to "5 ${Lang.t("mbit")}", 10 to "10 ${Lang.t("mbit")}", 25 to "25 ${Lang.t("mbit")}", 50 to "50 ${Lang.t("mbit")}", 100 to "100 ${Lang.t("mbit")}")
    val lookTick = engineState.lookTick
    val preview = remember(lookTick) { loadBackground(Backgrounds.file(engine.cfg)?.absolutePath.orEmpty()) }
    val previewBuiltin = Backgrounds.item(engine.cfg).res
    Overlay("CWLauncher", "Настройки", engine) {
        val tabList = buildList {
            add(0 to Lang.t("tab_main"))
            add(1 to Lang.t("tab_game"))
            add(2 to Lang.t("tab_mods"))
            add(3 to Lang.t("tab_look"))
            add(6 to "Управление")
            add(4 to Lang.t("tab_extra"))
            if (dev) add(5 to "Сборки")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabList.forEach { (id, title) ->
                val on = tab == id
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Tc(0xE6244882) else Tc(0xD2122A4E)).border(1.dp, Tc(0xFF4C8DFF), RoundedCornerShape(12.dp)).clickable { tab = id }.padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(title, color = Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LaunchedEffect(dev) { if (!dev && tab == 5) tab = 4 }
        when (tab) {
            0 -> Split {
                OptionCard(Lang.t("autostart"), Lang.t("autostart_h"), autoStart, { autoStart = it })
                OptionCard(Lang.t("autoupdate"), Lang.t("autoupdate_h"), autoLauncher, { autoLauncher = it }) {
                    Text(Lang.t("check_updates"), color = Muted, fontSize = 11.sp)
                    Choice(every.labelOf(launcherEvery), every.map { it.second }) { launcherEvery = every[it].first }
                }
                OptionCard(Lang.t("behavior"), Lang.t("behavior_h"), null, null) {
                    Text(Lang.t("on_launch"), color = Muted, fontSize = 11.sp)
                    Text(Lang.t("menu_behavior"), color = TextMain, fontSize = 13.sp)
                }
            }
            1 -> Split {
                OptionCard(Lang.t("ram"), "${Lang.t("ram_hint")} $capGb ${Lang.t("gb")}.", null, null) {
                    Text("$ramGb ${Lang.t("gb")} ${Lang.t("ram_of")} $capGb ${Lang.t("gb")}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Slider(ramGb.toFloat(), { ramGb = it.toInt().coerceIn(1, capGb) }, valueRange = 1f..capGb.toFloat(), colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                }
                OptionCard(Lang.t("java"), Lang.t("java_h"), ownJava, { ownJava = it }) {
                    Text(if (engineState.javaFound) "Java найдена" else "Будет использована встроенная Java 17", color = Muted, fontSize = 11.sp)
                    Field("Путь к Java", javaPath, ownJava) { javaPath = it }
                }
                OptionCard(Lang.t("jvm"), Lang.t("jvm_h"), null, null) {
                    Field("Аргументы", jvm) { jvm = it }
                }
                OptionCard("Шейдеры", "Добавьте скачанный архив шейдеров (.zip). Затем в игре: Настройки → Настройки графики → Шейдеры. Работает через мод Iris. Шейдеры для ПК тяжёлы для телефона: берите лёгкие паки, в их настройках уменьшайте тени и дальность, а в блоке «Оптимизация» снижайте разрешение игры.", null, null) {
                    GhostButton("Добавить шейдеры (.zip)") {
                        pickShaders.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
                    }
                    val packs = remember(shaderTick, working) { engine.shaderPacks() }
                    if (packs.isEmpty()) {
                        Text("Шейдеров пока нет", color = Muted, fontSize = 11.sp)
                    }
                    packs.forEach { pack ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pack.name, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("Удалить", color = Red, fontSize = 12.sp, modifier = Modifier.clickable { pendingShader = pack.name }.padding(6.dp))
                        }
                    }
                }
                OptionCard("Оптимизация", "По умолчанию выключена. Если включить, выбранный профиль применяется при каждом запуске игры.", optimization != 0, { optimization = if (it) 1 else 0 }) {
                    val profiles = listOf(
                        "Сбалансированная (8 чанков, 60 FPS)",
                        "Экономия памяти (5 чанков)",
                        "Упор на видеочип (75% разрешения)",
                        "Максимум FPS (3 чанка, 60% разрешения)",
                        "Шейдеры + FPS (до 50% разрешения)",
                        "Экстремальная (2 чанка, 40% разрешения)"
                    )
                    if (optimization != 0) {
                        Text("Профиль оптимизации", color = Muted, fontSize = 11.sp)
                        Choice(profiles[(optimization - 1).coerceIn(profiles.indices)], profiles) { optimization = it + 1 }
                        Spacer(Modifier.height(6.dp))
                    } else {
                        Text("Выключено — настройки графики Minecraft лаунчер не изменяет", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Графика (рендерер)", color = Muted, fontSize = 11.sp)
                    val renderers = listOf("Авто (по модам)", "GL4ES (совместимость)", "OpenLTW (Sodium, современные моды)")
                    Choice(renderers[renderer.coerceIn(0, 2)], renderers) { renderer = it }
                    Spacer(Modifier.height(6.dp))
                    Text("Сборщик мусора", color = Muted, fontSize = 11.sp)
                    val gcs = listOf("Serial (экономит память, по умолчанию)", "Parallel (быстрее при большой памяти)")
                    Choice(gcs[gcSel.coerceIn(0, 1)], gcs) { gcSel = it }
                    Spacer(Modifier.height(6.dp))
                    Text("Компиляция Java (JIT)", color = Muted, fontSize = 11.sp)
                    val jits = listOf("Полная (по умолчанию)", "Только C1 (меньше нагрев и память)")
                    Choice(jits[jitSel.coerceIn(0, 1)], jits) { jitSel = it }
                    Spacer(Modifier.height(6.dp))
                    Text("Память под текстуры и буферы", color = Muted, fontSize = 11.sp)
                    val directs = listOf(256, 512, 1024, 2048)
                    Choice("$directMb МБ", directs.map { "$it МБ" }) { directMb = directs[it] }
                    Spacer(Modifier.height(6.dp))
                    Text("Разрешение игры (картинку растягивает видеочип; чем ниже, тем больше FPS)", color = Muted, fontSize = 11.sp)
                    val scales = listOf(100, 85, 75, 60, 50, 40, 33)
                    Choice("$renderPct%", scales.map { "$it%" }) { renderPct = scales[it] }
                    Toggle("Снижать разрешение, когда включены шейдеры", shaderAuto) { shaderAuto = it }
                    Toggle(Lang.t("hipri"), highPri) { highPri = it }
                    if (shaderAuto) {
                        Text("Разрешение при шейдерах", color = Muted, fontSize = 11.sp)
                        val shaderScales = listOf(85, 75, 60, 50, 40, 33)
                        Choice("$shaderPct%", shaderScales.map { "$it%" }) { shaderPct = shaderScales[it] }
                    }
                }
                OptionCard("Автоподключение к серверу", "После загрузки игры сразу зайти на сервер Common World. Один раз, без повторов. Логин и пароль вводятся как обычно.", autoConn, { autoConn = it })
                OptionCard("Экран телефона погас", "Что делать с Minecraft, когда экран выключился. Простое сворачивание игру не закрывает.", null, null) {
                    val modes = listOf("Сразу завершать Minecraft", "Завершать через таймер", "Всегда оставлять активным")
                    Choice(modes[screenOff.coerceIn(0, 2)], modes) { screenOff = it }
                    if (screenOff == 1) {
                        val minutes = listOf(1, 3, 5, 10, 15, 30, 60)
                        val labels = minutes.map { "$it мин" }
                        Choice("${screenOffMin} мин".let { if (it in labels) it else labels[2] }, labels) { screenOffMin = minutes[it] }
                    }
                }
                OptionCard("Управление", "Джойстик, кнопки, размер, прозрачность, положение", null, null) {
                    GhostButton("Настроить управление") {
                        context.startActivity(Intent(context, ControlsEditorActivity::class.java))
                    }
                }
            }
            2 -> Split {
                OptionCard(Lang.t("modsup"), Lang.t("modsup_h"), autoBuild, { autoBuild = it }) {
                    Text(Lang.t("check_updates"), color = Muted, fontSize = 11.sp)
                    Choice(every.labelOf(modsEvery), every.map { it.second }) { modsEvery = every[it].first }
                }
                OptionCard(Lang.t("verify"), Lang.t("verify_h"), verify, { verify = it })
                OptionCard(Lang.t("modmgr"), Lang.t("modmgr_h"), null, null) {
                    GhostButton(Lang.t("open_mods")) { engine.open(Screen.MODS) }
                }
            }
            3 -> Split {
                OptionCard(Lang.t("bg"), Lang.t("bg_h"), null, null) {
                    Image(
                        painter = preview ?: painterResource(previewBuiltin),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(6.dp))
                    val chosen = engine.cfg.backgroundPath
                    val manual = !engine.cfg.bgAuto
                    Backgrounds.items.forEach { item ->
                        val on = manual && (chosen == Backgrounds.BUILTIN + item.id || (chosen.isBlank() && item.id == "blue"))
                        BackgroundRow(item.title, on) {
                            engine.cfg.backgroundPath = Backgrounds.BUILTIN + item.id
                            engine.cfg.bgAuto = false
                            engine.cfg.save()
                            engine.noteLook()
                        }
                    }
                    backgroundFiles().forEach { file ->
                        if (bgTick < 0) return@forEach
                        BackgroundRow(file.name, manual && file.absolutePath == chosen, onDelete = { pendingBg = file }) {
                            engine.cfg.backgroundPath = file.absolutePath
                            engine.cfg.bgAuto = false
                            engine.cfg.save()
                            engine.noteLook()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    GhostButton(Lang.t("add_bg")) { pickBg.launch("image/*") }
                }
                OptionCard(Lang.t("dim"), Lang.t("dim_hint"), null, null) {
                    Text("$dim%", color = Color.White, fontWeight = FontWeight.Bold)
                    Slider(dim.toFloat(), {
                        val next = it.toInt()
                        dim = next
                        engine.cfg.backgroundDim = next * 80 / 100
                        engine.cfg.save()
                        engine.noteLook()
                    }, valueRange = 0f..100f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                }
                OptionCard(Lang.t("blur"), Lang.t("blur_h"), blur, {
                    blur = it
                    engine.cfg.uiBlur = it
                    engine.cfg.save()
                    engine.noteLook()
                })
                OptionCard("Цветовая тема", "Цвет интерфейса и фон. «Свой» — собственный стиль: выберите оттенок сами.", null, null) {
                    var autoBg by remember { mutableStateOf(engine.cfg.bgAuto) }
                    var hue by remember { mutableIntStateOf(engine.cfg.uiCustomHue) }
                    ThemePicker(palette) { id ->
                        palette = id
                        engine.cfg.uiPalette = id
                        engine.cfg.save()
                        Pal.set(id, surface, engine.cfg.uiCustomHue, engine.cfg.uiDynHues)
                        engine.noteLook()
                    }
                    if (palette == "custom") {
                        Spacer(Modifier.height(8.dp))
                        Text("Свой стиль: оттенок $hue°", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Box(
                            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.8f, 0.95f) })
                            )
                        )
                        Slider(hue.toFloat(), { hue = it.toInt().coerceIn(0, 359) }, onValueChangeFinished = {
                            engine.cfg.uiCustomHue = hue
                            engine.cfg.uiDynHues = ""
                            engine.cfg.save()
                            Pal.set("custom", surface, hue, "")
                            engine.noteLook()
                        }, valueRange = 0f..359f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.hsv(hue.toFloat(), 0.8f, 0.95f), activeTrackColor = Color.hsv(hue.toFloat(), 0.8f, 0.95f)))
                        Text("Прозрачность панелей и свой фон настраиваются ниже и во вкладке фона.", color = Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Toggle("Подобрать фон", autoBg) {
                        autoBg = it
                        engine.cfg.bgAuto = it
                        engine.cfg.save()
                        engine.noteLook()
                    }
                    Text(
                        if (autoBg) "Фон выбирается сам: ${Backgrounds.item(engine.cfg).title}." else "Фон выбран вручную в списке «Фон лаунчера».",
                        color = Muted, fontSize = 11.sp
                    )
                }
                OptionCard("Экран запуска игры", "Как выглядит окно «Загрузка Minecraft». Цвета берутся из выбранной темы.", null, null) {
                    BootStylePicker(bootStyle) { id ->
                        bootStyle = id
                        engine.cfg.bootStyle = id
                        engine.cfg.save()
                    }
                }
                StylesCard(engine, lookTick)
                OptionCard("Углы и обводка", "Насколько скруглены карточки и какой толщины их линия.", null, null) {
                    var corner by remember { mutableIntStateOf(engine.cfg.uiCorner) }
                    var edge by remember { mutableIntStateOf(engine.cfg.uiBorder) }
                    Text("Скругление: $corner dp", color = TextMain, fontSize = 12.sp)
                    Slider(corner.toFloat(), { corner = it.toInt() }, onValueChangeFinished = {
                        engine.cfg.uiCorner = corner
                        engine.cfg.save()
                        Pal.look(corner, edge)
                    }, valueRange = 4f..32f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                    Text("Толщина линии: $edge dp", color = TextMain, fontSize = 12.sp)
                    Slider(edge.toFloat(), { edge = it.toInt() }, onValueChangeFinished = {
                        engine.cfg.uiBorder = edge
                        engine.cfg.save()
                        Pal.look(corner, edge)
                    }, valueRange = 0f..4f, steps = 3, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                }
                OptionCard("Прозрачность панелей","Насколько фон просвечивает сквозь карточки и окна: %.", null, null) {
                    Slider(surface.toFloat(), {
                        surface = it.toInt().coerceIn(50, 140)
                    }, onValueChangeFinished = {
                        engine.cfg.uiSurface = surface
                        engine.cfg.save()
                        Pal.set(palette, surface, engine.cfg.uiCustomHue)
                    }, valueRange = 50f..140f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                }
            }
            6 -> ControlsTab(engine, context)
            5 -> if (dev) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { DevBuildsCard(engine, lookTick) }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        OptionCard(Lang.t("cache"), Lang.t("cache_h"), null, null) {
                            Text("${Lang.t("cache_size")}$cacheLabel", color = Muted, fontSize = 12.sp)
                            GhostButton(Lang.t("clear")) {
                                engine.clearCache()
                                cacheLabel = human(folderBytes(AppPaths.cache) + folderBytes(AppPaths.tmp))
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        OptionCard(Lang.t("logs"), Lang.t("logs_h"), null, null) {
                            Text("Размер: ${human(folderBytes(AppPaths.logs))}", color = Muted, fontSize = 12.sp)
                            GhostButton(Lang.t("logs")) { engine.open(Screen.LOG) }
                        }
                    }
                }
                OptionCard(Lang.t("extra_opts"), Lang.t("extra_h"), null, null) {
                    Toggle(Lang.t("fps"), showFps) { showFps = it; engine.cfg.showFps = it; engine.cfg.save(); engine.noteLook() }
                    Toggle(Lang.t("disk"), disk) { disk = it }
                    Toggle(Lang.t("proxy"), proxy) { proxy = it }
                    Toggle(Lang.t("offline"), offline) { offline = it }
                    Toggle(Lang.t("dev"), dev) { dev = it }
                    Toggle(Lang.t("verbose"), verbose) { verbose = it }
                    if (dev) Toggle("Скрывать подробные логи при запуске игры", hideLogs) { hideLogs = it }
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        OptionCard(Lang.t("downloads"), Lang.t("downloads_h"), null, null) {
                            GhostButton(Lang.t("open_dl")) { engine.tell("${Lang.t("downloads")}:\n${AppPaths.cache.absolutePath}") }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        OptionCard(Lang.t("diag"), Lang.t("diag_h"), null, null) {
                            GhostButton(Lang.t("logs")) { engine.open(Screen.LOG) }
                        }
                    }
                }
                OptionCard(Lang.t("reset_card"), Lang.t("reset_h"), null, null) {
                    GhostButton(Lang.t("reset_short")) { reset() }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(Lang.t("wipe_ver")) { askWipe = true }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(Lang.t("wipe_re")) { askReinstall = true }
                }
            }
        }
        if (tab == 0) {
            Spacer(Modifier.height(8.dp))
            val density = LocalDensity.current
            var notesHeight by remember { mutableIntStateOf(0) }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("notes"), Lang.t("notes_h"), notes, { notes = it }) {
                        Text(Lang.t("time_show"), color = Muted, fontSize = 11.sp)
                        Choice(times.labelOf(timeUnit), times.map { it.second }) { timeUnit = times[it].first }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("lang"), Lang.t("lang_hint"), null, null, stretch = true) {
                        Text(Lang.t("lang_pick"), color = Muted, fontSize = 11.sp)
                        Choice(languageName(language), languageOptions.map { if (it.first.isEmpty()) Lang.t("lang_sys") else it.second }) {
                            language = languageOptions[it].first
                        }
                    }
                }
            }
        }
        if (tab == 1) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("leader"), Lang.t("leader_h"), leader, { leader = it }) {
                        Toggle(Lang.t("limit_name"), limitNet) { limitNet = it }
                        Text(Lang.t("mbit"), color = Muted, fontSize = 11.sp)
                        Choice(bands.firstOrNull { it.first == band }?.second ?: "Без ограничений", bands.map { it.second }) { band = bands[it].first }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("gameopt"), Lang.t("gameopt_h"), null, null) {
                        Toggle("VSync", vsync) { vsync = it }
                        Field(Lang.t("fps_cap"), fps) { fps = it.filter { ch -> ch.isDigit() } }
                    }
                }
            }
        }
        if (tab == 2) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("sources"), Lang.t("sources_h"), null, null) {
                        GhostButton(Lang.t("edit")) { engine.tell(Lang.t("sources_h")) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("backups"), Lang.t("backups_h"), backups, { backups = it }) {
                        Text(Lang.t("copies"), color = Muted, fontSize = 11.sp)
                        Choice(copies.toString(), listOf("3", "5", "10")) { copies = listOf(3, 5, 10)[it] }
                    }
                }
            }
        }
        if (tab == 3) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("effects"), Lang.t("effects_h"), effects, {
                        effects = it
                        engine.cfg.uiEffects = it
                        engine.cfg.save()
                        engine.noteLook()
                    }) {
                        Toggle(Lang.t("anim"), anim) { anim = it; engine.cfg.uiAnim = it; engine.cfg.save() }
                        Toggle(Lang.t("fade"), fade) { fade = it; engine.cfg.uiTransitions = it; engine.cfg.save() }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    OptionCard(Lang.t("uistyle"), Lang.t("uistyle_h"), null, null) {
                        Choice(if (style == "compact") Lang.t("style_compact") else Lang.t("style_std"), listOf(Lang.t("style_std"), Lang.t("style_compact"))) {
                            style = if (it == 1) "compact" else "standard"
                            engine.cfg.uiStyle = style
                            engine.cfg.save()
                            engine.noteLook()
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(Lang.t("font_scale"), color = Muted, fontSize = 11.sp)
                        Choice("$fontScale%", listOf("85%", "100%", "110%", "125%")) {
                            fontScale = listOf(85, 100, 110, 125)[it]
                        }
                        Toggle(Lang.t("immersive"), immersive) { immersive = it }
                        Toggle(Lang.t("keep_awake"), keepAwake) { keepAwake = it }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { reset() }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Tc(0xD2122A4E), contentColor = TextMain)) {
                Text(Lang.t("reset"))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { askSave = true }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) {
                Text(Lang.t("save"))
            }
        }
    }
    if (askSave) {
        Notice("Сохранить настройки?", "Сохранить", "Отмена", { askSave = false; save() }, { askSave = false })
    }
    pendingShader?.let { name ->
        Notice("Удалить шейдеры «$name»?", "Удалить", "Отмена", {
            pendingShader = null
            engine.deleteShader(name) { shaderTick++ }
        }, { pendingShader = null })
    }
    pendingBg?.let { file ->
        Notice("Удалить фон «${file.name}»?", "Удалить", "Отмена", {
            pendingBg = null
            val was = engine.cfg.backgroundPath == file.absolutePath
            file.delete()
            if (was) {
                engine.cfg.backgroundPath = ""
                engine.cfg.bgAuto = true
                engine.cfg.save()
            }
            bgTick++
            engine.noteLook()
        }, { pendingBg = null })
    }
    if (askWipe) {
        Notice(
            "Удалить все версии игры, моды и миры? Лаунчер останется, но игру нужно будет скачать заново.",
            "Удалить версии",
            "Отмена",
            { askWipe = false; engine.resetGame() },
            { askWipe = false }
        )
    }
    if (askReinstall) {
        Notice(
            "Переустановка сотрёт все данные: добавленные фоны, аккаунты, миры, моды и скачанные версии. Само приложение останется.",
            "Переустановить",
            "Отмена",
            { askReinstall = false; engine.reinstallLauncher() },
            { askReinstall = false }
        )
    }
}

private fun newsStatusText(status: String): String = when (status) {
    "loading" -> "Загрузка новостей…"
    "empty" -> "Новостей пока нет"
    else -> "Источник новостей не ответил"
}

@Composable
private fun NewsPage(state: UiState, engine: Engine) {
    SplitList(
        title = Lang.t("news"),
        items = emptyList(),
        selected = 0,
        onSelect = {},
        repeat = { engine.reloadNews() },
        showRepeat = false,
        showList = false,
        engine = engine
    ) {
        val item = state.news
        if (item != null) {
            Text(item.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (item.date.isNotBlank()) Text(item.date, color = Muted, fontSize = 11.sp)
            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(item.text, color = TextMain, fontSize = 13.sp)
            }
        } else {
            Text(newsStatusText(state.newsStatus), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun UpdatesPage(state: UiState, engine: Engine) {
    var index by remember { mutableIntStateOf(0) }
    val items = state.notes
    val safe = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    SplitList(
        title = Lang.t("updates_title"),
        items = if (items.isEmpty()) listOf(Lang.t("updates_empty")) else items.map { it.title },
        selected = safe,
        onSelect = { index = it },
        repeat = { engine.reloadNotes() },
        showRepeat = true,
        showList = true,
        scrollBody = true,
        engine = engine
    ) {
        val note = items.getOrNull(safe)
        Text(note?.title ?: Lang.t("updates_empty"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(note?.text ?: Lang.t("updates_hint"), color = TextMain, fontSize = 13.sp)
    }
}

@Composable
private fun SplitList(
    title: String,
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    repeat: () -> Unit,
    showRepeat: Boolean,
    showList: Boolean,
    scrollBody: Boolean = false,
    engine: Engine,
    body: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.fillMaxSize().clip(CardShape).background(Tc(0xE60E1830)).border(1.dp, Line, CardShape).padding(12.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showList) {
                    Column(Modifier.wrapContentWidth().widthIn(max = 220.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items.forEachIndexed { i, name ->
                            val on = i == selected
                            Text(
                                name,
                                color = TextMain,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.wrapContentWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) BlueDeep else Tc(0x66101A2C)).clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
                if (scrollBody) {
                    ScrollPane(Modifier.weight(1f).fillMaxHeight()) { body() }
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) { body() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (showRepeat) {
                    Button(onClick = repeat, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text(Lang.t("repeat")) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = { engine.open(Screen.HOME) }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Tc(0xFF243656), contentColor = TextMain)) { Text(Lang.t("close")) }
            }
        }
    }
}

@Composable
private fun AccountPage(state: UiState, engine: Engine, context: Context) {
    val tick = state.accountTick
    val current = engine.accounts.active()
    val working = engine.working.collectAsState().value
    var form by remember { mutableStateOf<String?>(null) }
    var nick by remember { mutableStateOf("") }
    var elyId by remember { mutableStateOf(engine.cfg.elyClientId) }
    var elyRedirect by remember { mutableStateOf(engine.cfg.elyRedirectUri) }
    var elyBackend by remember { mutableStateOf(engine.cfg.elyBackendUrl) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        engine.importSkinFrom(uri) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Column(
            Modifier.fillMaxSize().clip(CardShape).background(Tc(0xE60E1830)).border(1.dp, Line, CardShape).padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CWLauncher", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Профиль", color = Muted, fontSize = 11.sp)
                }
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Tc(0xFF243656)).clickable { engine.open(Screen.HOME) }, contentAlignment = Alignment.Center) {
                    Text("×", color = TextMain, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileCard("Текущий аккаунт") {
                        if (current == null) {
                            Text("Аккаунтов нет", color = TextMain, fontWeight = FontWeight.Bold)
                            Text("Создайте профиль, чтобы устанавливать версии и запускать игру.", color = Muted, fontSize = 11.sp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Face(current, 52.dp, tick)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(current.username.ifBlank { "—" }, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(if (current.type == AccountType.ELY) "Аккаунт Ely.by" else "Оффлайн", color = Muted, fontSize = 11.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MiniPill("Изменить имя") {
                                            if (current.type == AccountType.ELY) form = "ely-name" else {
                                                nick = current.username
                                                form = "rename"
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text("●  Активный", color = Green, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    ProfileCard("Информация об аккаунте") {
                        InfoLine("ID аккаунта", if (current == null) "—" else shortAccountId(current), Blue)
                        InfoLine("Платформа", if (current == null) "—" else if (current.type == AccountType.ELY) "Ely.by" else "Оффлайн", TextMain)
                        InfoLine("Дата создания", formatCreated(current?.createdAt ?: 0L), TextMain)
                        InfoLine("Статус", if (current == null) "—" else "Активный", if (current == null) Muted else Green)
                    }
                    ProfileCard("Настройки аккаунта") {
                        ActionLine("Сменить аватарку", "Голова скина этого аккаунта. Если файла нет, остаётся значок профиля.") {
                            if (current == null) form = "need" else pick.launch(arrayOf("image/png", "image/*", "application/octet-stream"))
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileCard("Управление аккаунтами") {
                        ActionLine("Создать новый аккаунт", "Добавьте аккаунт Ely.by или оффлайн-ник.") {
                            nick = ""
                            form = "create"
                        }
                        Spacer(Modifier.height(6.dp))
                        ActionLine("Удалить аккаунт", "Удалить выбранный аккаунт из лаунчера.") {
                            if (current != null) form = "delete"
                        }
                    }
                    ProfileCard("Вход через Ely.by") {
                        Text("Ely.by", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Войдите через Ely.by. Пароль вводится только на сайте, лаунчер его не видит.", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { engine.beginEly { openLink(context, it) } },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)
                        ) { Text("Войти через Ely.by", fontSize = 12.sp) }
                        Spacer(Modifier.height(4.dp))
                        MiniPill("Параметры входа") {
                            elyId = engine.cfg.elyClientId
                            elyRedirect = engine.cfg.elyRedirectUri
                            elyBackend = engine.cfg.elyBackendUrl
                            form = "ely"
                        }
                    }
                    ProfileCard("Список аккаунтов") {
                        if (engine.accounts.items.isEmpty()) {
                            Text("Список пуст", color = Muted, fontSize = 12.sp)
                        }
                        engine.accounts.items.forEach { account ->
                            val on = account.id == current?.id
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, if (on) Blue else Tc(0xFF1E5AA8), RoundedCornerShape(8.dp)).clickable { engine.selectAccount(account.id) }.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Face(account, 32.dp, tick)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(account.username.ifBlank { "—" }, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(if (account.type == AccountType.ELY) "Ely.by" else "Оффлайн", color = Muted, fontSize = 11.sp)
                                }
                                if (on) Text("●", color = Green, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Аватарка — голова скина этого аккаунта. Этот скин уходит в игру.", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                MiniPill("Назад") { engine.open(Screen.HOME) }
            }
        }
        when (form) {
            "create" -> FormBox("Новый аккаунт", { form = null }) {
                Field("Ник", nick) { nick = it }
                Text("Добавьте аккаунт Ely.by или оффлайн-ник.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Войти через Ely.by") {
                        form = null
                        engine.beginEly { openLink(context, it) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        engine.addOffline(nick) { error ->
                            if (error == null) form = null else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        }
                    }, enabled = working == 0, colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text(if (working == 0) "Создать оффлайн" else "Выполняется…") }
                }
            }
            "rename" -> FormBox("Изменить имя", { form = null }) {
                Field("Ник", nick) { nick = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Отмена") { form = null }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        current?.let { acc ->
                            engine.renameOffline(acc.id, nick) { error ->
                                if (error == null) form = null else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, enabled = working == 0, colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text(if (working == 0) "Сохранить" else "Выполняется…") }
                }
            }
            "delete" -> FormBox("Удалить аккаунт", { form = null }) {
                Text("Удалить аккаунт ${current?.username.orEmpty()}?", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Отмена") { form = null }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        current?.let { acc -> engine.removeAccount(acc.id) { form = null } }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("Удалить аккаунт") }
                }
            }
            "ely" -> FormBox("Параметры входа", { form = null }) {
                Field("Client id Ely.by", elyId) { elyId = it }
                Field("Redirect URI", elyRedirect) { elyRedirect = it }
                Field("Сервер CW (https)", elyBackend) { elyBackend = it }
                Text("Войдите через Ely.by. Пароль вводится только на сайте, лаунчер его не видит.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Отмена") { form = null }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        engine.saveSettings {
                            elyClientId = elyId.trim()
                            elyRedirectUri = elyRedirect.trim()
                            elyBackendUrl = elyBackend.trim()
                        }
                        form = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text("Сохранить") }
                }
            }
            "ely-name" -> FormBox("Изменить имя", { form = null }) {
                Text("Имя аккаунта Ely.by задаётся на сайте Ely.by.", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiniPill("Закрыть") { form = null }
                }
            }
            "need" -> FormBox("Профиль", { form = null }) {
                Text("Сначала создайте профиль. Без аккаунта установка и запуск недоступны.", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiniPill("Закрыть") { form = null }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
    Glass {
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionLine(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Tc(0xFF1E5AA8), RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(hint, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Blue, fontSize = 18.sp)
    }
}

@Composable
private fun MiniPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Tc(0xFF243656)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text, color = TextMain, fontSize = 12.sp) }
}

@Composable
private fun FormBox(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    CenterDialog(onClose) {
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** "Update the launcher": postponing is possible only after a 5 second countdown. */
@Composable
private fun LauncherUpdateDialog(remote: String, engine: Engine, context: Context) {
    var seconds by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (seconds > 0) {
            kotlinx.coroutines.delay(1000)
            seconds--
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .clip(CardShape)
                .background(Panel2)
                .border(1.dp, Line, CardShape)
                .padding(14.dp)
        ) {
            Text("Обновить лаунчер", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("Вышла новая версия лаунчера: $remote. Установлена: ${CwLog.VERSION}.", color = TextMain, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { engine.postponeLauncherUpdate() }, enabled = seconds == 0) {
                    Text(if (seconds > 0) "Обновить позже ($seconds)" else "Обновить позже", color = Muted)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    engine.openLauncherUpdate { url ->
                        if (url != null) openLink(context, url)
                        else Toast.makeText(context, "Отсутствует на сервере новая версия лаунчера, обратитесь в техподдержку.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Обновить сейчас", color = Blue) }
            }
        }
    }
}

/** A window in the middle of the screen, above the page, whatever the scroll position of the page is. */
@Composable
private fun CenterDialog(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = 340.dp)
                .clip(CardShape)
                .background(Panel2)
                .border(1.dp, Line, CardShape)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            content = content
        )
    }
}

@Composable
private fun Face(account: Account, size: androidx.compose.ui.unit.Dp, tick: Int) {
    val face = remember(account.id, account.uuid, tick) { skinHead(account.id) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Tc(0xFF10233F)).border(1.dp, Tc(0xFF4C8DFF), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (face != null) {
            Image(face, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        } else {
            Glyph("user", Tc(0xFFD6E2F5), Modifier.size(size / 2))
        }
    }
}

private fun skinHead(accountId: String): androidx.compose.ui.graphics.ImageBitmap? {
    val dir = File(AppPaths.skins, accountId)
    val file = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.maxByOrNull { it.lastModified() } ?: return null
    val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val scale = src.width / 64
    if (scale < 1 || src.width != scale * 64 || src.height < scale * 32) return src.asImageBitmap()
    val cell = 8 * scale
    val face = Bitmap.createBitmap(src, cell, cell, cell, cell).copy(Bitmap.Config.ARGB_8888, true)
    if (src.height >= scale * 64) {
        val hat = Bitmap.createBitmap(src, 40 * scale, cell, cell, cell)
        Canvas(face).drawBitmap(hat, 0f, 0f, null)
    }
    return face.asImageBitmap()
}

private fun shortAccountId(account: Account): String {
    val hex = account.shortUuid().uppercase()
    return "#" + hex.take(6)
}

private fun formatCreated(millis: Long): String {
    if (millis <= 0L) return "—"
    return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
}

@Composable
private fun VersionPopup(state: UiState, engine: Engine) {
    val ids = remember(state.releases) {
        val list = state.releases.toMutableList()
        if (!list.contains("1.20.1")) list.add(0, "1.20.1")
        list
    }
    Box(Modifier.fillMaxSize().background(Tc(0x8806101C)).clickable { engine.open(Screen.HOME) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(12.dp))
                .background(Tc(0xF0101C33))
                .border(1.dp, Tc(0xFF3D7AD4), RoundedCornerShape(12.dp))
                .clickable { }
                .padding(10.dp)
        ) {
            Text(
                if (state.releases.isEmpty()) "Загрузка списка версий…"
                else "Сервер Common World ставит 1.20.1 Fabric и моды. Остальные строки — обычный Minecraft.",
                color = Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VersionRow(
                    title = "Сервер Common World",
                    installed = state.installedVersions.contains("1.20.1"),
                    fabric = null,
                    onFabric = {},
                    onClick = { engine.selectCommonWorld() }
                )
                ids.forEach { id ->
                    val fabric = fabricChecked(id, state, engine)
                    VersionRow(
                        title = "Minecraft $id",
                        installed = state.installedVersions.contains(id),
                        fabric = fabric,
                        onFabric = { engine.toggleFabric(id) },
                        onClick = { engine.selectRelease(id, fabric) }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { engine.open(Screen.HOME) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tc(0xFF243656), contentColor = TextMain)
                ) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun VersionRow(title: String, installed: Boolean, fabric: Boolean?, onFabric: () -> Unit, onClick: () -> Unit) {
    val fill = if (installed) Tc(0xFF2F6FE0) else Tc(0xFF173056)
    val line = if (installed) Tc(0xFF8EB4FF) else Tc(0xFF2A4E78)
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp)).background(fill).border(1.dp, line, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (fabric != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { onFabric() }.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(16.dp).border(1.dp, if (fabric) Green else Tc(0xFF8EA6CC), RoundedCornerShape(3.dp)).background(Tc(0xFF0B1C36)),
                    contentAlignment = Alignment.Center
                ) {
                    if (fabric) Text("✓", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                Text("Fabric", color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if (installed) "установлена" else "не установлена", color = Color.White, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilesPage(title: String, state: UiState, engine: Engine) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var uploadTarget by remember { mutableStateOf<File?>(null) }
    val buildName = if (engine.cfg.developerMode) ru.cw.launcher.engine.Instances.safeName(engine.cfg.devInstance) else ""
    val buildDir = if (buildName.isEmpty()) null else ru.cw.launcher.engine.Instances.dir(engine.cfg.minecraftVersion, buildName).takeIf { it.isDirectory }
    var inBuild by remember { mutableStateOf(true) }
    val root = remember(engine.cfg.minecraftVersion, title, inBuild, buildDir?.absolutePath) {
        if (title == "Моды") AppPaths.mods(engine.cfg.minecraftVersion)
        else if (buildDir != null && inBuild) buildDir
        else AppPaths.game(engine.cfg.minecraftVersion)
    }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) uploadTarget?.let { target ->
            Thread {
                var added = 0
                for (uri in uris) {
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                        val dest = uniqueFile(target, safeFileName(name))
                        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                        added++
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Загружено файлов: $added из ${uris.size}", Toast.LENGTH_SHORT).show()
                    refresh++
                }
            }.start()
        }
    }
    var current by remember(root.absolutePath) { mutableStateOf(root) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Имя") }
    var selected by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var editor by remember { mutableStateOf<String?>(null) }
    var editorValue by remember { mutableStateOf("") }
    var clipboard by remember { mutableStateOf<File?>(null) }
    var cut by remember { mutableStateOf(false) }
    val canonicalRoot = remember(root.absolutePath) { runCatching { root.canonicalFile }.getOrElse { root.absoluteFile } }

    fun inside(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        path == canonicalRoot.path || path.startsWith(canonicalRoot.path + File.separator)
    }.getOrDefault(false)
    fun reload() { refresh++ }
    fun open(file: File) {
        if (!inside(file)) return
        if (file.isDirectory) current = file else viewing = file
    }
    fun share(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Отправить файл"))
        }.onFailure { Toast.makeText(context, "Не удалось экспортировать: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    viewing?.let { file ->
        FileViewer(file, onBack = { viewing = null }, onShare = { share(file) })
        return
    }
    BackHandler(enabled = current != canonicalRoot) { current.parentFile?.takeIf(::inside)?.let { current = it } }
    Page("Файлы Minecraft", engine) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GhostButton("Назад", wide = false) { if (current != canonicalRoot) current.parentFile?.takeIf(::inside)?.let { current = it } else engine.open(Screen.HOME) }
            GhostButton("Обновить", wide = false) { reload() }
            GhostButton("Новая папка", wide = false) { editorValue = "Новая папка"; editor = "mkdir" }
            GhostButton("Загрузить", wide = false) {
                uploadTarget = current
                pickUpload.launch(arrayOf("*/*"))
            }
            if (buildDir != null && title != "Моды") GhostButton(if (inBuild) "Корень: сборка «$buildName»" else "Корень: основная папка", wide = false) { inBuild = !inBuild }
            if (clipboard != null) GhostButton(if (cut) "Переместить сюда" else "Копировать сюда") {
                val source = clipboard ?: return@GhostButton
                val target = uniqueFile(current, source.name)
                runCatching {
                    if (cut) {
                        if (!source.renameTo(target)) { copyFileTree(source, target); source.deleteRecursively() }
                    } else copyFileTree(source, target)
                    clipboard = null; reload()
                }.onFailure { Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(current.relativeToOrSelf(canonicalRoot).invariantSeparatorsPath.ifBlank { "/" }, color = Tc(0xFF79B7FF), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth()) {
            Field("Поиск", query) { query = it }
            GhostButton("Сортировка: $sort", wide = false) {
                sort = when (sort) { "Имя" -> "Тип"; "Тип" -> "Размер"; "Размер" -> "Дата"; else -> "Имя" }
            }
        }
        Spacer(Modifier.height(2.dp))
        val marker = refresh
        val files = remember(current.absolutePath, marker, query, sort) {
            val base = current.listFiles()?.filter { query.isBlank() || it.name.contains(query, true) }.orEmpty()
            when (sort) {
                "Тип" -> base.sortedWith(compareBy<File>({ !it.isDirectory }, { it.extension.lowercase() }, { it.name.lowercase() }))
                "Размер" -> base.sortedWith(compareBy<File>({ !it.isDirectory }, { if (it.isDirectory) 0 else it.length() }))
                "Дата" -> base.sortedWith(compareBy<File>({ !it.isDirectory }, { -it.lastModified() }))
                else -> base.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            }
        }
        if (files.isEmpty()) Text("Папка пуста", color = Muted)
        files.forEach { file ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                    .background(Tc(0x99203350)).clickable { open(file) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (file.isDirectory) "📁" else fileIcon(file), fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (file.isDirectory) "Папка · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified()))}"
                        else "${file.extension.ifBlank { "файл" }.uppercase()} · ${formatBytes(file.length())} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified()))}",
                        color = Muted, fontSize = 10.sp
                    )
                }
                Text("⋮", color = Tc(0xFF79B7FF), fontSize = 22.sp, modifier = Modifier.clickable { selected = file }.padding(6.dp))
            }
        }

        selected?.let { file ->
            Spacer(Modifier.height(8.dp))
            CenterDialog({ selected = null }) {
                Text(file.name, color = TextMain, fontWeight = FontWeight.Bold)
                Text(file.absolutePath, color = Muted, fontSize = 10.sp)
                if (file.isFile && isTextFile(file)) {
                    val preview = remember(file.absolutePath, file.lastModified()) { readTextPreview(file) }
                    Spacer(Modifier.height(5.dp)); Text(preview, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                } else if (file.isFile && file.extension.lowercase() in setOf("zip", "jar")) {
                    val preview = remember(file.absolutePath, file.lastModified()) { archivePreview(file) }
                    Spacer(Modifier.height(5.dp)); Text(preview, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                } else if (file.isFile && file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
                    remember(file.absolutePath, file.lastModified()) { BitmapFactory.decodeFile(file.absolutePath) }?.let {
                        Image(it.asImageBitmap(), file.name, Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GhostButton("Копировать", wide = false) { clipboard = file; cut = false; selected = null }
                    GhostButton("Переместить", wide = false) { clipboard = file; cut = true; selected = null }
                    GhostButton("Переименовать", wide = false) { editorValue = file.name; editor = "rename" }
                    if (file.isFile) GhostButton("Отправить", wide = false) { share(file) }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GhostButton("Копировать путь", wide = false) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Путь Minecraft", file.absolutePath))
                    }
                    if (!isProtectedGamePath(canonicalRoot, file)) GhostButton("Удалить", wide = false) { editor = "delete" }
                    GhostButton("Закрыть", wide = false) { selected = null }
                }
            }
        }

        editor?.let { action ->
            Spacer(Modifier.height(8.dp))
            CenterDialog({ editor = null }) {
                Text(when (action) { "mkdir" -> "Создать папку"; "rename" -> "Переименовать"; else -> "Подтвердите удаление" }, color = TextMain, fontWeight = FontWeight.Bold)
                if (action != "delete") Field("Название", editorValue) { editorValue = it }
                if (action == "delete") Text("Удалить ${selected?.name}? Действие нельзя отменить.", color = Tc(0xFFFFA7A7))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton("Отмена", wide = false) { editor = null }
                    GhostButton(if (action == "delete") "Удалить" else "Сохранить", wide = false) {
                        runCatching {
                            when (action) {
                                "mkdir" -> {
                                    val target = File(current, safeFileName(editorValue)); require(inside(target));
                                    if (!target.mkdir()) error("Папка не создана")
                                }
                                "rename" -> {
                                    val source = selected ?: error("Файл не выбран")
                                    val target = File(source.parentFile, safeFileName(editorValue)); require(inside(target));
                                    if (!source.renameTo(target)) error("Не удалось переименовать")
                                    selected = target
                                }
                                "delete" -> {
                                    val target = selected ?: error("Файл не выбран")
                                    if (isProtectedGamePath(canonicalRoot, target)) error("Системный каталог игры защищён")
                                    if (!target.deleteRecursively()) error("Не удалось удалить")
                                    selected = null
                                }
                            }
                            editor = null; reload()
                        }.onFailure { Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileViewer(file: File, onBack: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var page by remember(file.absolutePath) { mutableIntStateOf(0) }
    val pageSize = 256 * 1024
    val pages = ((file.length() + pageSize - 1) / pageSize).coerceAtLeast(1).toInt()
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(file.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(file.parent.orEmpty(), color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GhostButton("Назад", wide = false) { onBack() }
            GhostButton("Обновить", wide = false) { refresh++ }
            GhostButton("Отправить", wide = false) { onShare() }
            GhostButton("Копировать путь", wide = false) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Путь Minecraft", file.absolutePath))
                Toast.makeText(context, "Путь скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        Spacer(Modifier.height(10.dp))
        if (isTextFile(file)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { Field("Поиск в тексте", search) { search = it } }
                if (pages > 1) {
                    GhostButton("◀", wide = false) { if (page > 0) page-- }
                    Text("${page + 1}/$pages", color = Muted, fontSize = 11.sp)
                    GhostButton("▶", wide = false) { if (page + 1 < pages) page++ }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Tc(0xE60E1830)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(10.dp)
        ) {
            val marker = refresh
            when {
                isTextFile(file) -> {
                    val raw = remember(file.absolutePath, file.lastModified(), page, marker) { readTextPage(file, page, pageSize) }
                    val shown = remember(raw, search) {
                        if (search.isBlank()) raw else raw.lineSequence().filter { it.contains(search, true) }.joinToString("\n")
                            .ifBlank { "Совпадений на этой странице нет" }
                    }
                    Text(shown, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                }
                file.extension.lowercase() in setOf("zip", "jar") -> Text(
                    remember(file.absolutePath, file.lastModified(), marker) { archivePreview(file) },
                    color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                )
                file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") -> {
                    remember(file.absolutePath, file.lastModified(), marker) { BitmapFactory.decodeFile(file.absolutePath) }?.let {
                        Image(it.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } ?: Text("Изображение не удалось открыть", color = TextMain)
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Сведения о файле", color = TextMain, fontWeight = FontWeight.Bold)
                    Text("Путь: ${file.absolutePath}", color = Muted)
                    Text("Размер: ${formatBytes(file.length())}", color = Muted)
                    Text("Изменён: ${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))}", color = Muted)
                    Text("Этот тип нельзя безопасно просмотреть внутри лаунчера.", color = TextMain)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("${file.extension.ifBlank { "файл" }.uppercase()} · ${formatBytes(file.length())}", color = Muted, fontSize = 10.sp)
    }
}

private fun safeFileName(value: String): String {
    val name = value.trim()
    require(name.isNotBlank() && name !in setOf(".", "..") && !name.contains('/') && !name.contains('\\')) { "Недопустимое имя" }
    return name
}

private fun uniqueFile(parent: File, name: String): File {
    var target = File(parent, name)
    var index = 1
    while (target.exists()) { target = File(parent, "${name.substringBeforeLast('.', name)} ($index)${name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }}"); index++ }
    return target
}

private fun copyFileTree(source: File, target: File) {
    if (source.isDirectory) {
        if (!target.mkdirs() && !target.isDirectory) error("Не удалось создать ${target.name}")
        source.listFiles()?.forEach { copyFileTree(it, File(target, it.name)) }
    } else source.copyTo(target, overwrite = false)
}

private fun isProtectedGamePath(root: File, file: File): Boolean = file == root ||
    (file.parentFile == root && file.name.lowercase() in setOf("assets", "libraries", "versions"))

private fun isTextFile(file: File): Boolean = file.extension.lowercase() in setOf("txt", "log", "json", "properties", "cfg", "toml", "yml", "yaml")

private fun readTextPreview(file: File): String = runCatching {
    val limit = 256 * 1024
    val bytes = file.inputStream().use { input ->
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val count = input.read(buffer, total, limit - total)
            if (count <= 0) break
            total += count
        }
        buffer.copyOf(total)
    }
    bytes.toString(Charsets.UTF_8) + if (file.length() > limit) "\n\n… показаны первые 256 КБ" else ""
}.getOrElse { "Не удалось прочитать: ${it.message}" }

private fun readTextPage(file: File, page: Int, pageSize: Int): String = runCatching {
    file.inputStream().use { input ->
        var remaining = page.toLong() * pageSize
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
        val buffer = ByteArray(pageSize)
        var total = 0
        while (total < pageSize) {
            val count = input.read(buffer, total, pageSize - total)
            if (count <= 0) break
            total += count
        }
        buffer.copyOf(total).toString(Charsets.UTF_8)
    }
}.getOrElse { "Не удалось прочитать: ${it.message}" }

private fun archivePreview(file: File): String = runCatching {
    ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().take(200).map { (if (it.isDirectory) "📁 " else "") + it.name }.toList()
        entries.joinToString("\n") + if (zip.size() > entries.size) "\n… ещё ${zip.size() - entries.size}" else ""
    }
}.getOrElse { "Архив не читается: ${it.message}" }

private fun fileIcon(file: File): String = when (file.extension.lowercase()) {
    "jar" -> "☕"; "zip" -> "🗜"; "png", "jpg", "jpeg", "webp" -> "🖼"; "log", "txt", "json", "cfg", "properties" -> "📄"; else -> "📦"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkinPage(engine: Engine) {
    val state by engine.state.collectAsState()
    var message by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Skins.Item?>(null) }
    val working = engine.working.collectAsState().value
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) engine.importSkinFrom(uri) { message = it }
    }
    val skins = remember(state.accountTick, message) { engine.skinItems() }
    val active = skins.firstOrNull { it.active }
    val bitmap = remember(active?.file?.absolutePath, active?.file?.lastModified(), message) {
        active?.file?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    Page("Скины", engine) {
        Text("Аккаунт: ${state.accountName}", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "Добавьте любое количество PNG-скинов в профиль и нажмите на карточку, чтобы выбрать. Выбранный скин передаётся игре при запуске, как в ПК-лаунчере.",
            color = Muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glass(Modifier.width(190.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Сейчас в игре", color = Muted, fontSize = 11.sp)
                    if (bitmap != null && active != null) {
                        SkinFigure(bitmap, Skins.slim(active.file), Modifier.size(width = 120.dp, height = 190.dp))
                        Text(active.name, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Box(Modifier.size(width = 120.dp, height = 190.dp), contentAlignment = Alignment.Center) {
                            Text("Стандартный скин", color = Muted, fontSize = 12.sp)
                        }
                    }
                    GhostButton(if (working == 0) "Добавить скин (PNG)" else "Выполняется…") {
                        pick.launch(arrayOf("image/png", "image/*", "application/octet-stream"))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Скины этого профиля (${skins.size})", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                if (skins.isEmpty()) {
                    Text("Пока нет ни одного скина. Нажмите «Добавить скин».", color = Muted, fontSize = 12.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    skins.forEach { skin ->
                        val thumb = remember(skin.file.absolutePath, skin.file.lastModified()) { BitmapFactory.decodeFile(skin.file.absolutePath) }
                        Column(
                            Modifier.width(112.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (skin.active) BlueDeep.copy(alpha = 0.35f) else Tc(0x66101A2C))
                                .border(if (skin.active) 2.dp else 1.dp, if (skin.active) Blue else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                .clickable { if (!skin.active) engine.selectSkin(skin.id) { message = it } }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (thumb != null) SkinFigure(thumb, Skins.slim(skin.file), Modifier.size(width = 56.dp, height = 90.dp))
                            Text((if (skin.active) "✓ " else "") + skin.name, color = if (skin.active) Color.White else TextMain, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Удалить", color = Red, fontSize = 11.sp, modifier = Modifier.clickable { pendingDelete = skin }.padding(4.dp))
                        }
                    }
                }
            }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextMain, fontSize = 13.sp)
        }
    }
    pendingDelete?.let { skin ->
        Notice(
            "Удалить скин «${skin.name}»?",
            "Удалить",
            "Отмена",
            onConfirm = {
                pendingDelete = null
                engine.removeSkin(skin.id) { message = it }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Front view of a Minecraft skin texture: head, body, arms and legs with their outer layers. */
@Composable
private fun SkinFigure(bitmap: android.graphics.Bitmap, slim: Boolean, modifier: Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier) {
        val k = (bitmap.width / 64).coerceAtLeast(1)
        val modern = bitmap.height >= 64 * k
        val unit = size.height / 32f
        val ox = (size.width - 16 * unit) / 2f
        val arm = if (slim) 3 else 4
        fun part(sx: Int, sy: Int, w: Int, h: Int, dx: Float, dy: Float) {
            drawImage(
                image,
                androidx.compose.ui.unit.IntOffset(sx * k, sy * k),
                androidx.compose.ui.unit.IntSize(w * k, h * k),
                androidx.compose.ui.unit.IntOffset((ox + dx * unit).toInt(), (dy * unit).toInt()),
                androidx.compose.ui.unit.IntSize((w * unit).toInt() + 1, (h * unit).toInt() + 1),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
            )
        }
        part(8, 8, 8, 8, 4f, 0f)
        part(20, 20, 8, 12, 4f, 8f)
        part(44, 20, arm, 12, 4f - arm, 8f)
        if (modern) part(36, 52, arm, 12, 12f, 8f) else part(44, 20, arm, 12, 12f, 8f)
        part(4, 20, 4, 12, 4f, 20f)
        if (modern) part(20, 52, 4, 12, 8f, 20f) else part(4, 20, 4, 12, 8f, 20f)
        part(40, 8, 8, 8, 4f, 0f)
        if (modern) {
            part(20, 36, 8, 12, 4f, 8f)
            part(44, 36, arm, 12, 4f - arm, 8f)
            part(52, 52, arm, 12, 12f, 8f)
            part(4, 36, 4, 12, 4f, 20f)
            part(4, 52, 4, 12, 8f, 20f)
        }
    }
}

@Composable
private fun LogPage(state: UiState, engine: Engine) {
    Page("Логи", engine) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Обновить") { engine.reloadLog() }
            GhostButton("Сохранить логи") { engine.exportLogs() }
        }
        Spacer(Modifier.height(8.dp))
        Text(state.logText.ifBlank { "Журнал пуст" }, color = TextMain, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Overlay(title: String, subtitle: String, engine: Engine, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Tc(0xD2122A4E)).border(1.dp, Tc(0xFF4C8DFF), RoundedCornerShape(12.dp)).clickable { engine.open(Screen.HOME) }, contentAlignment = Alignment.Center) {
                Text("×", color = TextMain, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun Page(title: String, engine: Engine, content: @Composable () -> Unit) {
    Overlay("CWLauncher", title, engine, content)
}

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.clip(CardShape).background(Panel).glassTint(CardShape).padding(10.dp),
        content = content
    )
}

@Composable
private fun OptionCard(
    title: String,
    subtitle: String,
    value: Boolean?,
    onToggle: ((Boolean) -> Unit)?,
    stretch: Boolean = true,
    extra: @Composable (() -> Unit)? = null
) {
    Glass(Modifier.fillMaxWidth().then(if (stretch) Modifier.fillMaxHeight() else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            if (value != null && onToggle != null) {
                Switch(value, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Blue, uncheckedTrackColor = Tc(0xFF243044)))
            }
        }
        if (extra != null) {
            Spacer(Modifier.height(6.dp))
            extra()
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String, badIsRed: Boolean = false) {
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "✕", color = if (ok) Green else if (badIsRed) Red else Amber, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconButton(kind: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Tc(0xCC15243C)).border(1.dp, Tc(0x553B82F6), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Glyph(kind, Color.White, Modifier.size(16.dp)) }
}

@Composable
private fun ActionTile(kind: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(12.dp)).background(Tc(0xCC15243C)).border(1.dp, Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Glyph(kind, TextMain, Modifier.size(16.dp))
        Text(label, color = TextMain, fontSize = 10.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun GhostButton(text: String, icon: String? = null, wide: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = if (wide) Modifier.fillMaxWidth().height(36.dp) else Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tc(0xCC1A2C4A), contentColor = TextMain)
    ) {
        if (icon != null) {
            Glyph(icon, TextMain, Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (label == "Ширина" || label == "Высота" || label.startsWith("Лимит")) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
            focusedBorderColor = Blue,
            unfocusedBorderColor = Line,
            focusedLabelColor = Muted,
            unfocusedLabelColor = Muted,
            cursorColor = TextMain,
            focusedContainerColor = Field,
            unfocusedContainerColor = Field
        )
    )
}

@Composable
private fun Notice(
    text: String,
    confirm: String,
    dismiss: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    alt: String? = null,
    onAlt: () -> Unit = {}
) {
    run {
        CenterDialog(onDismiss) {
            Text(text, color = TextMain, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onConfirm) { Text(confirm, color = if (alt != null) Tc(0xFFE0524D) else Blue) }
                if (alt != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onAlt) { Text(alt, color = Blue) }
                }
                if (dismiss != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text(dismiss, color = Muted) }
                }
            }
        }
    }
}

@Composable
private fun Glyph(kind: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        when (kind) {
            "play" -> drawPath(Path().apply {
                moveTo(w * 0.28f, h * 0.16f)
                lineTo(w * 0.82f, h * 0.5f)
                lineTo(w * 0.28f, h * 0.84f)
                close()
            }, tint)
            "chevron" -> drawPath(Path().apply {
                moveTo(w * 0.32f, h * 0.22f)
                lineTo(w * 0.68f, h * 0.5f)
                lineTo(w * 0.32f, h * 0.78f)
            }, tint, style = stroke)
            "send" -> drawPath(Path().apply {
                moveTo(w * 0.12f, h * 0.48f)
                lineTo(w * 0.88f, h * 0.16f)
                lineTo(w * 0.62f, h * 0.88f)
                lineTo(w * 0.48f, h * 0.54f)
                close()
            }, tint)
            "site" -> drawCircle(tint, radius = w * 0.28f, style = stroke)
            "chat" -> drawCircle(tint, radius = w * 0.12f, center = p(0.35f, 0.45f))
            "user" -> {
                drawCircle(tint, radius = w * 0.16f, center = p(0.5f, 0.34f))
                drawPath(Path().apply {
                    moveTo(w * 0.22f, h * 0.82f)
                    quadraticTo(w * 0.5f, h * 0.52f, w * 0.78f, h * 0.82f)
                }, tint, style = stroke)
            }
            "folder" -> drawPath(Path().apply {
                moveTo(w * 0.12f, h * 0.32f)
                lineTo(w * 0.38f, h * 0.32f)
                lineTo(w * 0.48f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.78f)
                lineTo(w * 0.12f, h * 0.78f)
                close()
            }, tint, style = stroke)
            "box" -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.12f)
                lineTo(w * 0.88f, h * 0.32f)
                lineTo(w * 0.88f, h * 0.7f)
                lineTo(w * 0.5f, h * 0.9f)
                lineTo(w * 0.12f, h * 0.7f)
                lineTo(w * 0.12f, h * 0.32f)
                close()
            }, tint, style = stroke)
            "skin" -> {
                drawRoundRect(tint, topLeft = p(0.28f, 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f), style = stroke)
                drawPath(Path().apply {
                    moveTo(w * 0.18f, h * 0.9f)
                    lineTo(w * 0.28f, h * 0.52f)
                    lineTo(w * 0.72f, h * 0.52f)
                    lineTo(w * 0.82f, h * 0.9f)
                }, tint, style = stroke)
            }
            "info" -> {
                drawCircle(tint, radius = w * 0.38f, style = stroke)
                drawLine(tint, p(0.5f, 0.42f), p(0.5f, 0.72f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.06f, center = p(0.5f, 0.28f))
            }
            "news" -> drawPath(Path().apply {
                moveTo(w * 0.2f, h * 0.16f)
                lineTo(w * 0.8f, h * 0.16f)
                lineTo(w * 0.8f, h * 0.84f)
                lineTo(w * 0.2f, h * 0.84f)
                close()
            }, tint, style = stroke)
            "gear" -> drawCircle(tint, radius = w * 0.22f, style = stroke)
            "mountain" -> drawPath(Path().apply {
                moveTo(w * 0.08f, h * 0.8f)
                lineTo(w * 0.38f, h * 0.22f)
                lineTo(w * 0.58f, h * 0.55f)
                lineTo(w * 0.72f, h * 0.36f)
                lineTo(w * 0.92f, h * 0.8f)
                close()
            }, tint)
            "cube" -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.1f)
                lineTo(w * 0.9f, h * 0.32f)
                lineTo(w * 0.5f, h * 0.54f)
                lineTo(w * 0.1f, h * 0.32f)
                close()
                moveTo(w * 0.5f, h * 0.54f)
                lineTo(w * 0.5f, h * 0.9f)
                lineTo(w * 0.1f, h * 0.68f)
                lineTo(w * 0.1f, h * 0.32f)
                moveTo(w * 0.5f, h * 0.54f)
                lineTo(w * 0.9f, h * 0.32f)
                lineTo(w * 0.9f, h * 0.68f)
                lineTo(w * 0.5f, h * 0.9f)
            }, tint, style = stroke)
            "copy" -> drawPath(Path().apply {
                moveTo(w * 0.32f, h * 0.22f)
                lineTo(w * 0.78f, h * 0.22f)
                lineTo(w * 0.78f, h * 0.7f)
                lineTo(w * 0.32f, h * 0.7f)
                close()
                moveTo(w * 0.22f, h * 0.34f)
                lineTo(w * 0.22f, h * 0.82f)
                lineTo(w * 0.66f, h * 0.82f)
            }, tint, style = stroke)
        }
    }
}

@Composable
private fun rememberClock(): String {
    var value by remember { mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    LaunchedEffect(Unit) {
        while (true) {
            value = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            delay(20_000)
        }
    }
    return value
}

@Composable
private fun rememberFps(enabled: Boolean): Int {
    var fps by remember { mutableIntStateOf(0) }
    // withFrameNanos keeps Compose drawing every frame, so it only runs while the counter is shown.
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        var frames = 0
        var window = System.currentTimeMillis()
        while (true) {
            withFrameNanos { }
            frames++
            val now = System.currentTimeMillis()
            if (now - window >= 1000) {
                fps = frames
                frames = 0
                window = now
            }
        }
    }
    return fps
}

private fun fabricChecked(id: String, state: UiState, engine: Engine): Boolean {
    if (engine.cfg.fabricVersions.containsKey(id)) return state.fabricOn.contains(id)
    return id == "1.20.1" && engine.cfg.commonWorld
}

private val languageOptions = listOf(
    "" to "",
    "ru_ru" to "Русский",
    "en_us" to "English",
    "uk_ua" to "Українська",
    "be_by" to "Беларуская",
    "kk_kz" to "Қазақша",
    "de_de" to "Deutsch",
    "fr_fr" to "Français",
    "es_es" to "Español",
    "es_mx" to "Español (México)",
    "pt_br" to "Português (Brasil)",
    "pt_pt" to "Português",
    "pl_pl" to "Polski",
    "it_it" to "Italiano",
    "tr_tr" to "Türkçe",
    "nl_nl" to "Nederlands",
    "sv_se" to "Svenska",
    "fi_fi" to "Suomi",
    "cs_cz" to "Čeština",
    "sk_sk" to "Slovenčina",
    "hu_hu" to "Magyar",
    "ro_ro" to "Română",
    "bg_bg" to "Български",
    "da_dk" to "Dansk",
    "nb_no" to "Norsk",
    "el_gr" to "Ελληνικά",
    "ja_jp" to "日本語",
    "ko_kr" to "한국어",
    "zh_cn" to "简体中文",
    "zh_tw" to "繁體中文",
    "id_id" to "Bahasa Indonesia",
    "ar_sa" to "العربية"
)

private fun languageName(code: String): String {
    if (code.isBlank()) return Lang.t("lang_sys")
    return languageOptions.firstOrNull { it.first == code }?.second ?: Lang.t("lang_sys")
}

private fun List<Pair<String, String>>.labelOf(key: String): String = firstOrNull { it.first == key }?.second ?: first().second

@Composable
private fun Split(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** One tile per colour theme, so a theme is chosen with a single tap. */
@Composable
private fun ThemePicker(selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pal.themes.forEach { theme ->
            val on = theme.id == selected
            val tileHue = Pal.hueOf(theme.id)
            val accent = Color.hsv(tileHue, 0.72f, 0.96f)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) accent.copy(alpha = 0.22f) else Color(0x33000000))
                    .border(if (on) 2.dp else 1.dp, if (on) accent else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    .clickable { onPick(theme.id) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, Color.hsv(tileHue, 0.85f, 0.55f)))),
                    contentAlignment = Alignment.Center
                ) {
                    if (on) Text("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Text(theme.title, color = if (on) Color.White else TextMain, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Choice(value: String, options: List<String>, onPick: (Int) -> Unit) {
    val index = options.indexOf(value).let { if (it < 0) 0 else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { onPick(if (index == 0) options.lastIndex else index - 1) }.padding(6.dp))
        Text(value, color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("›", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { onPick(if (index == options.lastIndex) 0 else index + 1) }.padding(6.dp))
    }
}

@Composable
private fun BackgroundRow(name: String, selected: Boolean, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) BlueDeep else Tc(0x66101A2C))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onClick)
        )
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            Text(Lang.t("del_bg"), color = Tc(0xFFFF8D8D), fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDelete))
        }
    }
}

@Composable
private fun MinecraftMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun face(block: Path.() -> Unit) = Path().apply(block)
        drawPath(face {
            moveTo(w * 0.08f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.92f)
            lineTo(w * 0.08f, h * 0.70f)
            close()
        }, Tc(0xFF6B4423))
        drawPath(face {
            moveTo(w * 0.92f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.92f)
            lineTo(w * 0.92f, h * 0.70f)
            close()
        }, Tc(0xFF8D5A32))
        drawPath(face {
            moveTo(w * 0.08f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.42f)
            close()
        }, Tc(0xFF3C8A28))
        drawPath(face {
            moveTo(w * 0.92f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.64f)
            lineTo(w * 0.92f, h * 0.42f)
            close()
        }, Tc(0xFF4E9A34))
        drawPath(face {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.92f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.08f, h * 0.30f)
            close()
        }, Tc(0xFF5CB336))
        drawPath(face {
            moveTo(w * 0.30f, h * 0.22f)
            lineTo(w * 0.46f, h * 0.14f)
            lineTo(w * 0.54f, h * 0.22f)
            lineTo(w * 0.38f, h * 0.30f)
            close()
        }, Tc(0xFF3E8C28))
        drawPath(face {
            moveTo(w * 0.58f, h * 0.28f)
            lineTo(w * 0.74f, h * 0.20f)
            lineTo(w * 0.80f, h * 0.26f)
            lineTo(w * 0.64f, h * 0.34f)
            close()
        }, Tc(0xFF2F7420))
    }
}

@Composable
private fun ScrollPane(modifier: Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    var viewH by remember { mutableIntStateOf(1) }
    Row(modifier) {
        Column(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewH = it.height }.verticalScroll(scroll).padding(8.dp)
        ) { content() }
        DragScroll(scroll, viewH, Modifier.fillMaxHeight().width(16.dp).padding(vertical = 4.dp, horizontal = 3.dp))
    }
}

@Composable
private fun DragScroll(scroll: androidx.compose.foundation.ScrollState, viewH: Int, modifier: Modifier) {
    val max = scroll.maxValue
    val contentH = (viewH + max).coerceAtLeast(1)
    val thumbFrac = (viewH.toFloat() / contentH).coerceIn(0.18f, 1f)
    Box(
        modifier.pointerInput(max, viewH) {
            detectDragGestures { change, drag ->
                change.consume()
                if (max <= 0) return@detectDragGestures
                val travel = (size.height * (1f - thumbFrac)).coerceAtLeast(1f)
                scroll.dispatchRawDelta(drag.y / travel * max)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val track = size.height
            val thumbH = (track * thumbFrac).coerceAtLeast(22.dp.toPx()).coerceAtMost(track)
            val travel = (track - thumbH).coerceAtLeast(0f)
            val y = if (max <= 0) 0f else travel * (scroll.value.toFloat() / max.toFloat())
            drawRoundRect(Tc(0x332F6FE0), cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()))
            drawRoundRect(
                Tc(0xFF4C8DFF),
                topLeft = Offset(0f, y),
                size = Size(size.width, thumbH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
        }
    }
}

private fun backgroundDir(): File {
    val dir = File(AppPaths.root, "backgrounds")
    dir.mkdirs()
    return dir
}

private fun backgroundFiles(): List<File> {
    val dir = backgroundDir()
    val saved = dir.listFiles()?.filter { it.isFile && isPicture(it) }?.sortedBy { it.name.lowercase() }.orEmpty()
    val legacy = File(AppPaths.root, "background.jpg")
    return if (legacy.isFile && saved.none { it.absolutePath == legacy.absolutePath }) listOf(legacy) + saved else saved
}

private fun isPicture(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp"
}

private fun copyBackground(context: Context, uri: android.net.Uri): File? {
    val dir = backgroundDir()
    var n = 1
    var dest = File(dir, "bg-$n.jpg")
    while (dest.exists()) {
        n++
        dest = File(dir, "bg-$n.jpg")
    }
    return try {
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        if (!dest.isFile || dest.length() < 32) {
            dest.delete()
            null
        } else dest
    } catch (_: Exception) {
        dest.delete()
        null
    }
}

private fun loadBackground(path: String): BitmapPainter? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (sample < 16 && (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080)) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return BitmapPainter(bmp.asImageBitmap())
}

private fun folderBytes(dir: File): Long {
    if (!dir.exists()) return 0
    var total = 0L
    dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
    return total
}

private fun human(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f МБ", mb)
    return String.format(java.util.Locale.US, "%.2f ГБ", mb / 1024.0)
}

private fun readyCaption(state: UiState): String = when (state.phase) {
    Phase.READY -> Lang.t("ready")
    Phase.OFFLINE -> Lang.t("offline_go")
    Phase.NOT_INSTALLED -> state.detail
    Phase.UPDATE_AVAILABLE -> state.detail
    Phase.ERROR -> state.detail
    Phase.RUNNING -> Lang.t("running")
    Phase.STARTING -> state.detail.ifBlank { Lang.t("starting") }
    else -> state.detail.ifBlank { Lang.t("checking") }
}

private fun buttonLabel(phase: Phase, busy: Boolean, detail: String = ""): String = when {
    busy -> Lang.t("cancel")
    phase == Phase.READY || phase == Phase.OFFLINE -> Lang.t("launch")
    phase == Phase.NOT_INSTALLED -> Lang.t("install")
    phase == Phase.UPDATE_AVAILABLE -> Lang.t("update_btn")
    phase == Phase.ERROR && (detail.startsWith("Не удалось") || detail.startsWith("Игра закрылась")) -> Lang.t("retry")
    phase == Phase.ERROR -> Lang.t("check")
    phase == Phase.CHECKING -> Lang.t("checking")
    phase == Phase.STARTING -> Lang.t("starting")
    phase == Phase.RUNNING -> Lang.t("running")
    else -> Lang.t("go")
}

@Composable
private fun TransferBar(state: UiState) {
    val total = state.xferTotal.coerceAtLeast(1L)
    val done = state.xferDone.coerceIn(0L, total)
    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
    val left = (state.xferTotal - state.xferDone).coerceAtLeast(0)
    Text(state.xferTitle.ifBlank { state.progressText }, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(3.dp))
    LinearProgressIndicator(
        progress = { done.toFloat() / total.toFloat() },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)),
        color = Blue,
        trackColor = Field,
        drawStopIndicator = {}
    )
    Text("$pct%", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text("${formatBytes(done)} / ${formatBytes(state.xferTotal)}", color = Muted, fontSize = 11.sp, maxLines = 1)
    Text("Осталось: ${formatBytes(left)}", color = Muted, fontSize = 11.sp, maxLines = 1)
    Text(
        if (state.xferSpeed > 0L) "Скорость: ${formatSpeed(state.xferSpeed)}" else "Определение скорости...",
        color = Muted,
        fontSize = 11.sp,
        maxLines = 1
    )
    if (state.xferSpeed > 0L) {
        Text("Примерное время: ${formatEta(left / state.xferSpeed)}", color = Muted, fontSize = 11.sp, maxLines = 1)
    }
    if (state.xferExtra.isNotBlank()) {
        Text(state.xferExtra, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun buttonColor(phase: Phase, busy: Boolean): Color = when {
    busy -> Tc(0xFF3D4C63)
    phase == Phase.ERROR -> Tc(0xFFE0524D)
    else -> BlueDeep
}

private fun statusTone(phase: Phase): Color = when (phase) {
    Phase.READY -> Green
    Phase.ERROR -> Red
    Phase.UPDATE_AVAILABLE, Phase.OFFLINE -> Amber
    else -> Muted
}

/**
 * Developer mode: own builds (separate game folders with their own mods, worlds and configs) and
 * a temporary nick. Libraries and assets are shared with the main folder.
 */
@Composable
private fun DevBuildsCard(engine: Engine, lookTick: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var copyMain by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf(engine.cfg.devNick) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingMod by remember { mutableStateOf<java.io.File?>(null) }
    val builds = remember(lookTick, refresh) { engine.devBuilds() }
    val current = engine.cfg.devInstance
    var modsNote by remember { mutableStateOf("") }
    val pickMods = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) engine.importBuildMods(uris) { modsNote = it; refresh++ }
    }
    val buildMods = remember(lookTick, refresh, current) { engine.buildMods() }
    var modsOpen by remember { mutableStateOf(false) }
    if (modsOpen && current.isNotEmpty()) {
        OptionCard("Моды сборки «$current» (${buildMods.size})", "Новая версия мода заменяет старую с тем же названием. В свои сборки нельзя заходить на сервер Common World.", null, null, stretch = false) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GhostButton("‹ Назад к сборкам") { modsOpen = false }
                GhostButton("Загрузить моды (.jar)") { pickMods.launch(arrayOf("*/*")) }
                if (modsNote.isNotBlank()) Text(modsNote, color = TextMain, fontSize = 12.sp)
                buildMods.forEach { jar ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(jar.name, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Убрать", color = Red, fontSize = 12.sp, modifier = Modifier.clickable { pendingMod = jar }.padding(8.dp))
                    }
                }
            }
        }
        pendingMod?.let { jar ->
            Notice(
                "Удалить мод «${jar.name}» из сборки?",
                "Удалить",
                "Отмена",
                onConfirm = {
                    pendingMod = null
                    engine.deleteBuildMod(jar) { refresh++ }
                },
                onDismiss = { pendingMod = null }
            )
        }
        return
    }
    OptionCard("Режим разработчика: свои сборки", "Отдельная папка игры: свои моды, миры и конфиги. Версия, библиотеки и сервер общие.", null, null, stretch = false) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BuildRow("Основная сборка", current.isEmpty(), null) { engine.selectBuild("") }
            builds.forEach { build ->
                BuildRow(build, current == build, { pendingDelete = build }) { engine.selectBuild(build) }
            }
            if (current.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                GhostButton("Моды сборки (${buildMods.size}): просмотреть и изменить") { modsOpen = true }
            }
            Spacer(Modifier.height(4.dp))
            Field("Имя новой сборки", name) { name = it; error = "" }
            Toggle("Скопировать моды и конфиги из основной", copyMain) { copyMain = it }
            GhostButton("Создать сборку") {
                engine.createBuild(name, copyMain) { problem ->
                    if (problem == null) {
                        name = ""
                        error = ""
                        refresh++
                    } else {
                        error = problem
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Field("Временный ник (пусто — свой аккаунт)", nick) { nick = it }
            Text("Ник 3–16 символов: латиница, цифры и _. Вход как офлайн-игрок, аккаунт не меняется.", color = Muted, fontSize = 11.sp)
            GhostButton("Применить ник") {
                val clean = nick.trim()
                if (clean.isEmpty() || ru.cw.launcher.engine.Account.validNick(clean)) {
                    engine.setTempNick(clean)
                    Toast.makeText(context, if (clean.isEmpty()) "Ник сброшен" else "Временный ник: $clean", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Ник: 3–16 символов, латиница, цифры и _", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    pendingDelete?.let { build ->
        Notice(
            "Удалить сборку «$build» вместе с её модами и мирами?",
            "Удалить",
            "Отмена",
            onConfirm = {
                pendingDelete = null
                engine.deleteBuild(build) { refresh++ }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun BuildRow(title: String, selected: Boolean, onDelete: (() -> Unit)?, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) BlueDeep else Tc(0x66101A2C))
            .border(1.dp, if (selected) Blue else Color(0x22FFFFFF), RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "✓" else "○", color = if (selected) Color.White else Muted, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onDelete != null) {
            Text("Удалить", color = Red, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDelete).padding(6.dp))
        }
    }
}

/**
 * Card outline. A solid style keeps the plain line; a dynamic style draws a gradient from the
 * left, middle and right colours of the picture, laid across the whole screen so neighbouring
 * cards continue each other, plus a faint tint of the same colours.
 */
private fun Modifier.glassTint(shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    val dyn = Pal.dyn
    if (dyn == null) {
        if (Pal.border > 0) border(Pal.border.dp, Line, shape) else this
    } else {
        val density = LocalDensity.current.density
        val screen = LocalConfiguration.current
        val screenW = (screen.screenWidthDp * density).coerceAtLeast(1f)
        val screenH = (screen.screenHeightDp * density).coerceAtLeast(1f)
        var box by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        // Colours of the picture along the card's own row, so the outline follows the picture.
        val stops = remember(box, dyn) {
            if (box.width <= 0f) emptyList() else (0..6).map { i ->
                val f = i / 6f
                f to Pal.dynAt((box.left + box.width * f) / screenW, box.center.y / screenH)
            }
        }
        val line = if (stops.isEmpty()) androidx.compose.ui.graphics.SolidColor(Line) else
            androidx.compose.ui.graphics.Brush.horizontalGradient(*stops.map { it.first to it.second.copy(alpha = 0.95f) }.toTypedArray(), startX = 0f, endX = box.width)
        val tint = if (stops.isEmpty()) androidx.compose.ui.graphics.SolidColor(Color.Transparent) else
            androidx.compose.ui.graphics.Brush.horizontalGradient(*stops.map { it.first to it.second.copy(alpha = 0.18f) }.toTypedArray(), startX = 0f, endX = box.width)
        onGloballyPositioned {
            val p = it.positionInWindow()
            box = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
        }
            .background(tint, shape)
            .border((Pal.border + 1).dp, line, shape)
    }
}

/** "Style from picture": solid or dynamic style from the current background, and the saved styles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StylesCard(engine: Engine, lookTick: Int) {
    var contrast by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<SavedStyle?>(null) }
    val working = engine.working.collectAsState().value
    val styles = remember(lookTick, refresh) { engine.styleList() }
    OptionCard("Стиль по картинке", "Лаунчер смотрит на текущий фон и сам создаёт новый стиль, а не берёт готовый. Он сохраняется в «Мои стили».", null, null, stretch = false) {
        Toggle("Противоположные цвета (иначе подобные)", contrast) { contrast = it }
        Spacer(Modifier.height(4.dp))
        GhostButton(if (working == 0) "Подобрать однотонный стиль" else "Выполняется…") {
            engine.makeStyle(false, contrast) { note = it; refresh++ }
        }
        Text("Один оттенок на весь интерфейс: обводка, полосы загрузки и кнопки одного цвета.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        GhostButton(if (working == 0) "Подобрать динамичный стиль" else "Выполняется…") {
            engine.makeStyle(true, contrast) { note = it; refresh++ }
        }
        Text("Обводка и подсветка карточек берут настоящий цвет картинки в своём месте экрана: где фон белый — белая, где чёрный — чёрная. С «противоположными» всё наоборот.", color = Muted, fontSize = 11.sp)
        if (note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(note, color = TextMain, fontSize = 12.sp)
        }
        if (styles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Мои стили (${styles.size}) — нажмите, чтобы выбрать; удержите, чтобы удалить", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            styles.forEach { style ->
                val on = engine.cfg.uiPalette == "custom" && engine.cfg.uiCustomHue == style.hues[1] && engine.cfg.backgroundPath == style.background
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) BlueDeep.copy(alpha = 0.35f) else Tc(0x66101A2C))
                        .border(if (on) 2.dp else 1.dp, if (on) Blue else Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { engine.applyStyle(style.id) }, onLongClick = { pendingDelete = style })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(width = 54.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(style.swatch().map { Color(it) })
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text((if (on) "✓ " else "") + style.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    pendingDelete?.let { style ->
        Notice(
            "Удалить стиль «${style.name}»?",
            "Удалить",
            "Отмена",
            onConfirm = {
                pendingDelete = null
                engine.deleteStyle(style.id) { refresh++ }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** One tile per design of the game start-up screen. */
@Composable
private fun BootStylePicker(selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BootStyles.items.forEach { item ->
            val on = item.id == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) BlueDeep.copy(alpha = 0.35f) else Color(0x33000000))
                    .border(if (on) 2.dp else 1.dp, if (on) Blue else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    .clickable { onPick(item.id) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(item.glyph, color = if (on) Color.White else Muted, fontSize = 24.sp)
                Text(item.title, color = if (on) Color.White else TextMain, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Shown when "Launch" is pressed in developer mode: the main build or one of the own builds. */
@Composable
private fun BuildChooser(engine: Engine, builds: List<String>, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tc(0xAA06101C)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(16.dp))
                .background(Tc(0xF2101B2E)).border(1.dp, Blue, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Что запустить?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Основная сборка или одна из ваших сборок разработчика.", color = Muted, fontSize = 12.sp)
            val current = engine.cfg.devInstance
            BuildRow("Основная сборка", current.isEmpty(), null) {
                engine.selectBuild("")
                onClose()
                engine.onMain()
            }
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                builds.forEach { build ->
                    BuildRow(build, current == build, null) {
                        engine.selectBuild(build)
                        onClose()
                        engine.onMain()
                    }
                }
            }
            GhostButton("Отмена") { onClose() }
        }
    }
}

private val controlPalette = listOf(
    0xFF14233D, 0xFF000000, 0xFF2A2A2A, 0xFFFFFFFF, 0xFF4E8CFF, 0xFF22C55E, 0xFFEF4444, 0xFFF59E0B, 0xFFA855F7, 0xFFEC4899, 0xFF06B6D4, 0xFFFACC15
).map { it.toInt() }

/** Settings tab "Controls": three layouts, the in-game switch button and the look of the buttons. */
@Composable
private fun ControlsTab(engine: Engine, context: Context) {
    var active by remember { mutableIntStateOf(ru.cw.launcher.ui.controls.ControlsConfig.activeProfile()) }
    var switchOn by remember { mutableStateOf(ru.cw.launcher.ui.controls.ControlsConfig.switchEnabled()) }
    val style = ru.cw.launcher.ui.controls.ControlStyle
    var tick by remember { mutableIntStateOf(0) }
    remember {
        style.load()
        0
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionCard("Раскладки управления", "Три разных вида кнопок. Выберите, какой использовать, и измените его.", null, null, stretch = false) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until ru.cw.launcher.ui.controls.ControlsConfig.PROFILES) {
                    val on = active == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (on) BlueDeep.copy(alpha = 0.35f) else Color(0x33000000))
                            .border(if (on) 2.dp else 1.dp, if (on) Blue else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                            .clickable {
                                active = i
                                ru.cw.launcher.ui.controls.ControlsConfig.setActive(i)
                            }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Вид ${i + 1}", color = if (on) Color.White else TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(if (on) "используется" else "выбрать", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GhostButton("Редактировать управление (вид ${active + 1})") {
                context.startActivity(Intent(context, ControlsEditorActivity::class.java))
            }
        }
        OptionCard("Переключение видов в игре", "Отдельная кнопка «Вид» на экране игры по кругу меняет вид 1, 2, 3. Её можно двигать в редакторе.", switchOn, {
            switchOn = it
            ru.cw.launcher.ui.controls.ControlsConfig.setSwitchEnabled(it)
        }, stretch = false)
        OptionCard("Стиль кнопок", "Форма и цвета кнопки в покое и при нажатии.", null, null, stretch = false) {
            fun changed() {
                style.save()
                tick++
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StylePreview(false, tick)
                StylePreview(true, tick)
                Text("в покое и нажата", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Круг", "Квадрат", "Шестиугольник").forEachIndexed { i, name ->
                    val on = style.shape == i
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (on) BlueDeep.copy(alpha = 0.35f) else Color(0x33000000))
                            .border(if (on) 2.dp else 1.dp, if (on) Blue else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                            .clickable {
                                style.shape = i
                                changed()
                            }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(name, color = if (on) Color.White else TextMain, fontSize = 12.sp) }
                }
            }
            Toggle("Цвета как в теме лаунчера", style.useTheme) {
                style.useTheme = it
                changed()
            }
            if (!style.useTheme) {
                ColorLine("Кнопка в покое", style.idle) {
                    style.idle = it
                    changed()
                }
                ColorLine("Кнопка нажата", style.pressed) {
                    style.pressed = it
                    changed()
                }
                ColorLine("Обводка", style.stroke) {
                    style.stroke = it
                    changed()
                }
                ColorLine("Текст", style.text) {
                    style.text = it
                    changed()
                }
            }
            Text("Толщина обводки: ${"%.1f".format(style.strokeDp)} dp", color = TextMain, fontSize = 12.sp)
            Slider(
                style.strokeDp,
                {
                    style.strokeDp = Math.round(it * 2f) / 2f
                    tick++
                },
                onValueChangeFinished = { changed() },
                valueRange = 0f..5f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue)
            )
            GhostButton("Сбросить стиль кнопок") {
                style.reset()
                changed()
            }
        }
    }
}

@Composable
private fun ColorLine(title: String, value: Int, onPick: (Int) -> Unit) {
    Text(title, color = TextMain, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        controlPalette.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(c))
                    .border(if (c == value) 3.dp else 1.dp, if (c == value) Color.White else Color(0x66FFFFFF), androidx.compose.foundation.shape.CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

/** A sample of the on-screen button in its current style. */
@Composable
private fun StylePreview(pressed: Boolean, tick: Int) {
    val st = ru.cw.launcher.ui.controls.ControlStyle
    val fill = if (st.useTheme) (if (pressed) Tc(0xFF4E8CFF) else Tc(0xFF14233D)) else Color(if (pressed) st.pressed else st.idle)
    val edge = if (st.useTheme) Tc(0xFF9CC0FF) else Color(st.stroke)
    Canvas(Modifier.size(54.dp)) {
        if (tick < 0) return@Canvas
        val r = size.minDimension / 2f - 3.dp.toPx()
        val c = center
        val path = androidx.compose.ui.graphics.Path()
        when (st.shape) {
            1 -> path.addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - r, c.y - r, c.x + r, c.y + r, androidx.compose.ui.geometry.CornerRadius(r * 0.3f)))
            2 -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i - 90.0)
                    val x = c.x + (r * Math.cos(a)).toFloat()
                    val y = c.y + (r * Math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            else -> path.addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
        }
        drawPath(path, fill)
        if (st.strokeDp > 0f) drawPath(path, edge, style = androidx.compose.ui.graphics.drawscope.Stroke(st.strokeDp.dp.toPx()))
    }
}
