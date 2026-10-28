package com.example.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun GameScreen(modifier: Modifier = Modifier) {
    // SharedPreferences to persist records, coins, skins, and settings
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("retro_aventura_scores", android.content.Context.MODE_PRIVATE) }

    // Core game state
    var gameState by remember { mutableStateOf(GameState.MENU) }
    val engine = remember {
        GameEngine().apply {
            val savedHighScore = prefs.getInt("high_score", 0)
            playerHighscore = savedHighScore
            playerCoins = if (prefs.contains("accumulated_coins")) {
                prefs.getInt("accumulated_coins", 0)
            } else {
                savedHighScore
            }
            val savedSkinId = prefs.getString("equipped_skin_id", "ciber") ?: "ciber"
            equipSkin(savedSkinId)
        }
    }
    val soundManager = remember { RetroSoundManager(context) }
    val musicManager = remember { RetroMusicManager(context) }

    // Active navigation tab
    var activeTab by remember { mutableStateOf("Jugar") }

    // Saved skins persistence - read & write
    var unlockedSkins by remember {
        mutableStateOf(
            prefs.getStringSet("unlocked_skins", setOf("ciber"))?.toSet() ?: setOf("ciber")
        )
    }

    // Persisted slider & audio settings
    var gravityFactor by remember { mutableFloatStateOf(prefs.getFloat("pref_gravity_factor", 1.0f)) }
    var speedFactor by remember { mutableFloatStateOf(prefs.getFloat("pref_speed_factor", 1.0f)) }
    var jumpFactor by remember { mutableFloatStateOf(prefs.getFloat("pref_jump_factor", 1.0f)) }
    var soundEffectsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_sfx_enabled", true)) }
    var soundVolume by remember { mutableFloatStateOf(prefs.getFloat("pref_sfx_volume", 0.85f)) }
    var musicEnabled by remember { mutableStateOf(prefs.getBoolean("pref_music_enabled", true)) }
    var musicVolume by remember { mutableFloatStateOf(prefs.getFloat("pref_music_volume", 0.65f)) }
    var selectedMusicTheme by remember {
        val savedThemeId = prefs.getString("pref_music_theme", MusicTrackTheme.AUTO_LEVEL.id)
        val initialTheme = MusicTrackTheme.entries.find { it.id == savedThemeId } ?: MusicTrackTheme.AUTO_LEVEL
        mutableStateOf(initialTheme)
    }
    var activeTrackName by remember { mutableStateOf(musicManager.activeTrackDisplayName) }
    var fpsOverlayEnabled by remember { mutableStateOf(prefs.getBoolean("pref_fps_overlay", true)) }
    var currentFps by remember { mutableIntStateOf(60) }

    // Control Android Back button: pause during gameplay, return to Menu from overlays, or return to "Jugar" tab
    BackHandler(enabled = activeTab != "Jugar" || gameState != GameState.MENU) {
        when {
            activeTab != "Jugar" -> {
                activeTab = "Jugar"
            }
            gameState == GameState.PLAYING -> {
                engine.releaseLeft()
                engine.releaseRight()
                gameState = GameState.PAUSED
            }
            gameState == GameState.PAUSED || gameState == GameState.GAME_OVER || gameState == GameState.VICTORY -> {
                gameState = GameState.MENU
            }
        }
    }

    // Pause game and MediaPlayer when the app goes to background (ON_PAUSE / ON_STOP)
    DisposableEffect(lifecycleOwner, musicManager, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    engine.releaseLeft()
                    engine.releaseRight()
                    if (gameState == GameState.PLAYING) {
                        gameState = GameState.PAUSED
                    }
                    musicManager.onAppBackgrounded()
                }
                Lifecycle.Event.ON_RESUME -> {
                    musicManager.onAppForegrounded()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Bind GameEngine sound events to low-latency SoundPool manager and release audio resources on dispose
    DisposableEffect(soundManager, musicManager, engine) {
        engine.onSoundEffect = { effect ->
            soundManager.play(effect)
        }
        onDispose {
            engine.onSoundEffect = null
            soundManager.release()
            musicManager.release()
        }
    }

    LaunchedEffect(soundEffectsEnabled, soundVolume) {
        soundManager.isEnabled = soundEffectsEnabled
        soundManager.volume = soundVolume
        prefs.edit()
            .putBoolean("pref_sfx_enabled", soundEffectsEnabled)
            .putFloat("pref_sfx_volume", soundVolume)
            .apply()
    }

    LaunchedEffect(musicEnabled, musicVolume, selectedMusicTheme) {
        musicManager.isEnabled = musicEnabled
        musicManager.volume = musicVolume
        if (musicManager.selectedTheme != selectedMusicTheme) {
            musicManager.selectTheme(selectedMusicTheme)
        }
        activeTrackName = musicManager.activeTrackDisplayName
        prefs.edit()
            .putBoolean("pref_music_enabled", musicEnabled)
            .putFloat("pref_music_volume", musicVolume)
            .putString("pref_music_theme", selectedMusicTheme.id)
            .apply()
    }

    // Automatically synchronize background music with game state and active level
    LaunchedEffect(gameState, engine.currentLevelIndex, engine.currentLevelType, selectedMusicTheme) {
        musicManager.syncWithGameContext(gameState, engine.currentLevelType)
        activeTrackName = musicManager.activeTrackDisplayName
    }

    // Persist high score updates (never decreases)
    LaunchedEffect(engine.playerHighscore) {
        prefs.edit().putInt("high_score", engine.playerHighscore).apply()
    }

    // Persist accumulated coins wallet
    LaunchedEffect(engine.playerCoins) {
        prefs.edit().putInt("accumulated_coins", engine.playerCoins).apply()
    }

    // Persist equipped skin ID
    LaunchedEffect(engine.activeSkinId) {
        prefs.edit().putString("equipped_skin_id", engine.activeSkinId).apply()
    }

    // Persist FPS overlay toggle
    LaunchedEffect(fpsOverlayEnabled) {
        prefs.edit().putBoolean("pref_fps_overlay", fpsOverlayEnabled).apply()
    }

    // Apply and persist physics factor adjustments
    LaunchedEffect(gravityFactor, speedFactor, jumpFactor) {
        engine.gravity = 0.8f * gravityFactor
        engine.walkSpeed = 7.5f * speedFactor
        engine.jumpPower = -17f * jumpFactor
        prefs.edit()
            .putFloat("pref_gravity_factor", gravityFactor)
            .putFloat("pref_speed_factor", speedFactor)
            .putFloat("pref_jump_factor", jumpFactor)
            .apply()
    }

    // Active pulse/bobbing for animations
    val infiniteTransition = rememberInfiniteTransition(label = "bob")
    val bannerScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo"
    )

    // Frame-rate independent Game Loop using real elapsed delta time
    LaunchedEffect(gameState) {
        if (gameState == GameState.PLAYING) {
            var lastTimeNanos = System.nanoTime()
            while (gameState == GameState.PLAYING) {
                delay(16L)
                val nowNanos = System.nanoTime()
                val rawDeltaSec = (nowNanos - lastTimeNanos) / 1_000_000_000f
                lastTimeNanos = nowNanos
                val deltaSeconds = if (rawDeltaSec <= 0.0001f) (1f / 60f) else rawDeltaSec.coerceIn(1f / 240f, 0.1f)
                currentFps = (1f / deltaSeconds).toInt().coerceIn(1, 240)
                engine.updatePhysics(deltaSeconds)

                // Switch gameover/victory states
                if (engine.playerLives <= 0) {
                    gameState = GameState.GAME_OVER
                } else if (engine.playerX >= engine.levelGoalX && engine.currentLevelIndex >= 2) {
                    gameState = GameState.VICTORY
                }
            }
        }
    }

    val isPlayingOrOverlay = activeTab == "Jugar" && gameState != GameState.MENU

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (isPlayingOrOverlay) Color(0xFF1A1C1E) else Color(0xFFFEF7FF))
    ) {
        // 1. App Bar Header Section - only show if NOT actively playing/overlay
        if (!isPlayingOrOverlay) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (activeTab != "Jugar") {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { activeTab = "Jugar" }
                                .background(Color(0xFFE8DEF8).copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "◀", color = Color(0xFF1D1B20), fontSize = 12.sp)
                        }
                    }

                    Column {
                        Text(
                            text = "Aventura Retro",
                            color = Color(0xFF1D1B20),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (activeTab == "Jugar") {
                                "${engine.currentLevelType.displayName} - Nivel ${engine.currentLevelIndex + 1}"
                            } else {
                                activeTab
                            },
                            color = Color(0xFF49454F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Right side: Active music badge/toggle + Coins badge + High score badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (musicEnabled) Color(0xFFE8DEF8) else Color(0xFFE0E0E0)
                            )
                            .clickable { musicEnabled = !musicEnabled }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("header_music_toggle")
                    ) {
                        Text(
                            text = if (musicEnabled) "🎵" else "🔇",
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (musicEnabled) activeTrackName else "Música OFF",
                            color = Color(0xFF1D1B20),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    // Spendable Coins badge
                    Text(
                        text = "🪙 ${engine.playerCoins}",
                        color = Color(0xFF5D4037),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(Color(0xFFFFD54F).copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("header_coins_badge")
                    )

                    // Show high score dynamic badge in App bar
                    Text(
                        text = "🏆 ${String.format("%06d", engine.playerHighscore)}",
                        color = Color(0xFF7A5901),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(Color(0xFFFFEA00).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // 2. Central Viewport Frame Container (Full-bleed widescreen edge-to-edge)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1A1C1E))
        ) {
            when (activeTab) {
                "Jugar" -> {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Main Render Canvas filling the entire widescreen display
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("game_canvas")
                        ) {
                            val gameScale = (size.height / engine.virtualViewportHeight).coerceAtLeast(0.5f)
                            engine.viewportWidth = size.width / gameScale

                            // Apply Screen Shake transformation Matrix
                            var shakeOffsetX = 0f
                            var shakeOffsetY = 0f
                            if (engine.screenShakeTimer > 0) {
                                shakeOffsetX = (Random.nextFloat() - 0.5f) * engine.screenShakeIntensity * gameScale
                                shakeOffsetY = (Random.nextFloat() - 0.5f) * engine.screenShakeIntensity * gameScale
                            }

                            drawIntoCanvas { canvas ->
                                canvas.save()
                                canvas.translate(shakeOffsetX, shakeOffsetY)

                                // Draw Parallax / Space background across full widescreen
                                drawParallaxBackground(engine, size, gameScale)

                                // Camera coordinate scroll transform (both X and Y for closer, smooth view)
                                canvas.translate(
                                    -engine.cameraX * gameScale,
                                    -engine.cameraY * gameScale
                                )

                                // Draw LEVEL platforms
                                for (platform in engine.platforms) {
                                    drawGamePlatform(platform, gameScale)
                                }

                                // Draw Collectibles
                                for (item in engine.collectibles) {
                                    if (!item.isCollected) {
                                        drawCollectible(item, gameScale)
                                    }
                                }

                                // Draw Enemies
                                for (enemy in engine.enemies) {
                                    if (!enemy.isDead) {
                                        drawEnemy(enemy, gameScale)
                                    }
                                }

                                // Draw Level Exit Goal flag
                                drawGoalFlag(engine.levelGoalX, gameScale)

                                // Draw Particles Sparkles
                                for (p in engine.particles) {
                                    drawParticle(p, gameScale)
                                }

                                // Draw Player
                                drawPlayer(engine, gameScale)

                                // Draw Active floating popups
                                for (t in engine.floatingTexts) {
                                    drawFloatingText(t, gameScale)
                                }

                                canvas.restore()
                            }
                        }

                        // Compact, unobtrusive top-edge HUD overlay when playing
                        if (gameState == GameState.PLAYING) {
                            HUDView(
                                engine = engine,
                                fpsOverlayEnabled = fpsOverlayEnabled,
                                currentFps = currentFps,
                                musicEnabled = musicEnabled,
                                onToggleMusic = { musicEnabled = !musicEnabled },
                                onPause = { gameState = GameState.PAUSED }
                            )
                        }

                        // Tactile controls (Translucent lower-corner buttons) when playing
                        if (gameState == GameState.PLAYING) {
                            GameControlsView(
                                engine = engine,
                                gameState = gameState,
                                onPause = { gameState = GameState.PAUSED }
                            )
                        }

                        // GAME MODAL WINDOW OVERLAYS
                        when (gameState) {
                            GameState.MENU -> {
                                MainMenuScreen(
                                    engine = engine,
                                    logoScale = bannerScale,
                                    onPlay = {
                                        engine.restartGame()
                                        gameState = GameState.PLAYING
                                    }
                                )
                            }
                            GameState.PAUSED -> {
                                PauseMenuScreen(
                                    onResume = { gameState = GameState.PLAYING },
                                    onRestart = {
                                        engine.restartGame()
                                        gameState = GameState.PLAYING
                                    },
                                    onExit = { gameState = GameState.MENU }
                                )
                            }
                            GameState.GAME_OVER -> {
                                GameOverScreen(
                                    score = engine.playerScore,
                                    highScore = engine.playerHighscore,
                                    onRestart = {
                                        engine.restartGame()
                                        gameState = GameState.PLAYING
                                    },
                                    onExit = { gameState = GameState.MENU }
                                )
                            }
                            GameState.VICTORY -> {
                                VictoryScreen(
                                    score = engine.playerScore,
                                    highScore = engine.playerHighscore,
                                    onRestart = {
                                        gameState = GameState.MENU
                                    }
                                )
                            }
                            else -> {}
                        }
                    }
                }
                "Niveles" -> {
                    LevelsTabScreen(engine = engine, onSelectLevel = {
                        soundManager.play(RetroSoundEffect.COIN)
                        activeTab = "Jugar"
                        gameState = GameState.PLAYING
                    })
                }
                "Tienda" -> {
                    SkinsShopTabScreen(
                        engine = engine,
                        unlockedSkins = unlockedSkins,
                        onSkinUnlocked = { skinId, price ->
                            // Validate balance FIRST using separate accumulated coins wallet (never touching highScore)
                            if (!unlockedSkins.contains(skinId) && engine.playerCoins >= price) {
                                engine.playerCoins -= price
                                prefs.edit().putInt("accumulated_coins", engine.playerCoins).apply()

                                val updated = unlockedSkins.toMutableSet().apply { add(skinId) }
                                unlockedSkins = updated
                                prefs.edit().putStringSet("unlocked_skins", updated).apply()

                                engine.equipSkin(skinId)
                                prefs.edit().putString("equipped_skin_id", skinId).apply()
                                soundManager.play(RetroSoundEffect.POWERUP)
                            }
                        }
                    )
                }
                "Ajustes" -> {
                    SettingsTabScreen(
                        gravityFactor = gravityFactor,
                        onGravityChange = { gravityFactor = it },
                        speedFactor = speedFactor,
                        onSpeedChange = { speedFactor = it },
                        jumpFactor = jumpFactor,
                        onJumpChange = { jumpFactor = it },
                        soundEffectsEnabled = soundEffectsEnabled,
                        onSoundToggle = {
                            soundEffectsEnabled = it
                            if (it) {
                                soundManager.isEnabled = true
                                soundManager.play(RetroSoundEffect.COIN)
                            }
                        },
                        soundVolume = soundVolume,
                        onSoundVolumeChange = { soundVolume = it },
                        onTestSound = { effect -> soundManager.play(effect) },
                        musicEnabled = musicEnabled,
                        onMusicToggle = { musicEnabled = it },
                        musicVolume = musicVolume,
                        onMusicVolumeChange = { musicVolume = it },
                        selectedMusicTheme = selectedMusicTheme,
                        activeTrackName = activeTrackName,
                        onSelectMusicTheme = { theme ->
                            selectedMusicTheme = theme
                            musicManager.selectTheme(theme)
                            activeTrackName = musicManager.activeTrackDisplayName
                        },
                        fpsOverlayEnabled = fpsOverlayEnabled,
                        onFpsToggle = { fpsOverlayEnabled = it }
                    )
                }
            }
        }

        // 3. Navigation Bar Theme (Material 3 Style) - only show if NOT playing/overlay
        if (!isPlayingOrOverlay) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(Color(0xFFF3EDF7))
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf(
                    Triple("Jugar", "▶", "Jugar"),
                    Triple("Niveles", "🗺", "Niveles"),
                    Triple("Tienda", "🛍", "Tienda"),
                    Triple("Ajustes", "⚙", "Ajustes")
                )
                
                tabs.forEach { (tabName, tabIcon, label) ->
                    val isSelected = activeTab == tabName
                    
                    Column(
                        modifier = Modifier
                            .clickable { activeTab = tabName }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(26.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFFE8DEF8) else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tabIcon,
                                color = if (isSelected) Color(0xFF1D1B20) else Color(0xFF49454F),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF1D1B20) else Color(0xFF49454F),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// CANVAS DRAWING CODE (GRAPHICS ENGINE)
// --------------------------------------------------------------------------

fun DrawScope.drawParallaxBackground(engine: GameEngine, containerSize: Size, scale: Float) {
    val levelType = engine.currentLevelType
    val camX = engine.cameraX
    val camY = engine.cameraY
    val w = containerSize.width
    val h = containerSize.height

    when (levelType) {
        LevelType.VALLE -> {
            // LAYER 0: Deep Twilight-to-Sunset Sky Gradient + Retro Segmented Sun (0.03x parallax)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A0B3B),
                        Color(0xFF4A1964),
                        Color(0xFFC84B61),
                        Color(0xFFFF8C61),
                        Color(0xFFFFD082)
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // Subtle upper-sky stars (0.02x parallax)
            val starShiftX = -camX * 0.02f * scale
            val starShiftY = -camY * 0.01f * scale
            val starRng = Random(101)
            for (i in 0..28) {
                val sx = ((starRng.nextFloat() * 2400f * scale + starShiftX) % (w + 200f * scale) + (w + 200f * scale)) % (w + 200f * scale) - 100f * scale
                val sy = (starRng.nextFloat() * h * 0.42f + starShiftY).coerceAtLeast(4f)
                val radius = (1.0f + (i % 3) * 0.7f) * scale
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f + (i % 4) * 0.12f),
                    radius = radius,
                    center = Offset(sx, sy)
                )
            }

            // Retro Synth Sun with outer atmospheric glow
            val sunX = w * 0.72f - camX * 0.03f * scale
            val sunY = h * 0.34f - (camY - 100f) * 0.04f * scale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x55FFEA79), Color(0x22FF6B6B), Color.Transparent),
                    center = Offset(sunX, sunY),
                    radius = 125f * scale
                ),
                radius = 125f * scale,
                center = Offset(sunX, sunY)
            )
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF394), Color(0xFFFF6E6C)),
                    startY = sunY - 58f * scale,
                    endY = sunY + 58f * scale
                ),
                radius = 58f * scale,
                center = Offset(sunX, sunY)
            )

            // LAYER 1: Distant Clouds & Floating Sky Islands (0.07x X, 0.04x Y parallax)
            val layer1X = -camX * 0.07f * scale
            val layer1Y = -(camY - 120f) * 0.04f * scale
            for (i in 0..10) {
                val cx = layer1X + (i * 360f + 80f) * scale
                val cy = (95f + (i % 3) * 32f) * scale + layer1Y
                // Cloud puff group
                drawRoundRect(
                    color = Color(0x30FFF0E5),
                    topLeft = Offset(cx - 55f * scale, cy),
                    size = Size(135f * scale, 24f * scale),
                    cornerRadius = CornerRadius(12f * scale, 12f * scale)
                )
                drawCircle(Color(0x36FFF0E5), 26f * scale, Offset(cx - 12f * scale, cy - 4f * scale))
                drawCircle(Color(0x40FFF0E5), 34f * scale, Offset(cx + 22f * scale, cy - 10f * scale))
            }

            // LAYER 2: Far Jagged Purple-Magenta Mountain Peaks (0.15x X, 0.08x Y parallax)
            val farMountX = -camX * 0.15f * scale
            val farMountY = -(camY - 140f) * 0.08f * scale
            val farMountBaseY = h + 40f * scale
            val farMountPath = Path().apply {
                moveTo(farMountX - 300f * scale, farMountBaseY)
                for (i in -1..14) {
                    val baseX = farMountX + (i * 320f) * scale
                    val peakH = (210f + (i * 53 % 70)) * scale
                    lineTo(baseX + 140f * scale, h - peakH + farMountY)
                    lineTo(baseX + 320f * scale, h - 85f * scale + farMountY)
                }
                lineTo(farMountX + 5000f * scale, farMountBaseY)
                close()
            }
            drawPath(
                path = farMountPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF4B215F), Color(0xFF2B133D)),
                    startY = h * 0.35f,
                    endY = h
                )
            )

            // LAYER 3: Midground Emerald Hills & Glowing Crystal Spires (0.28x X, 0.14x Y parallax)
            val midHillX = -camX * 0.28f * scale
            val midHillY = -(camY - 140f) * 0.14f * scale
            val midHillPath = Path().apply {
                moveTo(midHillX - 300f * scale, farMountBaseY)
                for (i in -1..18) {
                    val bx = midHillX + (i * 260f) * scale
                    val hillTop = h - (135f + (i % 3) * 28f) * scale + midHillY
                    lineTo(bx + 85f * scale, hillTop)
                    lineTo(bx + 175f * scale, hillTop + 12f * scale)
                    lineTo(bx + 260f * scale, h - 65f * scale + midHillY)
                }
                lineTo(midHillX + 5200f * scale, farMountBaseY)
                close()
            }
            drawPath(
                path = midHillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF1F5E5B), Color(0xFF103338)),
                    startY = h * 0.55f,
                    endY = h
                )
            )

            // Crystal spires protruding along midground ridges
            for (i in 0..16) {
                val spireX = midHillX + (i * 280f + 115f) * scale
                val spireBaseY = h - (110f + (i % 3) * 20f) * scale + midHillY
                val spireH = (38f + (i % 2) * 18f) * scale
                val crystalPath = Path().apply {
                    moveTo(spireX - 10f * scale, spireBaseY + 15f * scale)
                    lineTo(spireX, spireBaseY - spireH)
                    lineTo(spireX + 10f * scale, spireBaseY + 15f * scale)
                    close()
                }
                drawPath(
                    crystalPath,
                    if (i % 2 == 0) Color(0x6600F0FF) else Color(0x66FF4081)
                )
            }

            // LAYER 4: Near Silhouette Pine Forest Ridge (0.46x X, 0.22x Y parallax)
            val nearForestX = -camX * 0.46f * scale
            val nearForestY = -(camY - 140f) * 0.22f * scale
            drawRect(
                color = Color(0xFF0A2226),
                topLeft = Offset(0f, h - 38f * scale + nearForestY),
                size = Size(w, 90f * scale)
            )
            for (i in -2..38) {
                val tx = nearForestX + (i * 95f) * scale
                if (tx < -80f * scale || tx > w + 80f * scale) continue
                val treeH = (58f + (i * 17 % 32)) * scale
                val baseTreeY = h - 32f * scale + nearForestY
                val treePath = Path().apply {
                    moveTo(tx - 24f * scale, baseTreeY)
                    lineTo(tx, baseTreeY - treeH)
                    lineTo(tx + 24f * scale, baseTreeY)
                    close()
                }
                drawPath(treePath, Color(0xFF0D2B30))
            }
        }

        LevelType.NEON -> {
            // LAYER 0: Deep Cyber-Indigo Sky Gradient + Wireframe Synth Moon (0.03x parallax)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF040014),
                        Color(0xFF140538),
                        Color(0xFF3E066E),
                        Color(0xFF8A0E8E),
                        Color(0xFFD81B8A)
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // Digital Cyber Moon with scanlines
            val moonX = w * 0.76f - camX * 0.03f * scale
            val moonY = h * 0.28f - (camY - 120f) * 0.04f * scale
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xCC00F0FF), Color(0xAAFF007F)),
                    startY = moonY - 52f * scale,
                    endY = moonY + 52f * scale
                ),
                radius = 52f * scale,
                center = Offset(moonX, moonY)
            )
            for (line in -3..3) {
                val ly = moonY + line * 11f * scale
                drawLine(
                    color = Color(0x99140538),
                    start = Offset(moonX - 50f * scale, ly),
                    end = Offset(moonX + 50f * scale, ly),
                    strokeWidth = 2.5f * scale
                )
            }

            // LAYER 1: Distant Megastructure Arcologies & Sky Traffic Lanes (0.09x X, 0.05x Y parallax)
            val farArcX = -camX * 0.09f * scale
            val farArcY = -(camY - 140f) * 0.05f * scale
            for (i in 0..16) {
                val ax = farArcX + (i * 260f) * scale
                if (ax < -180f * scale || ax > w + 180f * scale) continue
                val ah = (220f + (i * 37 % 90)) * scale
                val aw = 135f * scale
                val ay = h - ah + farArcY
                drawRect(
                    color = Color(0x550A0220),
                    topLeft = Offset(ax, ay),
                    size = Size(aw, ah + 60f * scale)
                )
                // Spire antenna
                drawLine(
                    color = Color(0x55FF007F),
                    start = Offset(ax + aw * 0.5f, ay),
                    end = Offset(ax + aw * 0.5f, ay - 35f * scale),
                    strokeWidth = 2f * scale
                )
            }

            // Sky traffic light streaks
            for (lane in 0..2) {
                val laneY = (95f + lane * 48f) * scale + farArcY
                for (car in 0..12) {
                    val carX = farArcX * (1.2f + lane * 0.3f) + (car * 290f + lane * 90f) * scale
                    if (carX < -60f * scale || carX > w + 60f * scale) continue
                    val streakColor = if ((car + lane) % 2 == 0) Color(0x9900F0FF) else Color(0x99FF2A85)
                    drawRoundRect(
                        color = streakColor,
                        topLeft = Offset(carX, laneY),
                        size = Size(22f * scale, 3f * scale),
                        cornerRadius = CornerRadius(2f * scale, 2f * scale)
                    )
                }
            }

            // LAYER 2: Mid-Far Cyberpunk Skyline with Window Grids (0.20x X, 0.10x Y parallax)
            val midCityX = -camX * 0.20f * scale
            val midCityY = -(camY - 140f) * 0.10f * scale
            for (i in 0..24) {
                val bx = midCityX + (i * 165f) * scale
                if (bx < -140f * scale || bx > w + 140f * scale) continue
                val bw = (95f + (i % 3) * 22f) * scale
                val bh = (155f + (i * 43 % 95)) * scale
                val by = h - bh + midCityY

                drawRect(
                    color = Color(0xCC0D0428),
                    topLeft = Offset(bx, by),
                    size = Size(bw, bh + 60f * scale)
                )
                drawRect(
                    color = Color(0x4500F0FF),
                    topLeft = Offset(bx, by),
                    size = Size(bw, bh + 60f * scale),
                    style = Stroke(width = 1.4f * scale)
                )

                // Lit neon window strips
                val stripColor = if (i % 2 == 0) Color(0x5500F0FF) else Color(0x55FF007F)
                for (row in 1..4) {
                    val wy = by + row * 24f * scale
                    if (wy < h) {
                        drawRect(
                            color = stripColor,
                            topLeft = Offset(bx + 12f * scale, wy),
                            size = Size(bw - 24f * scale, 4f * scale)
                        )
                    }
                }
            }

            // LAYER 3: Near Cyber Towers & Neon Hologram Signs (0.36x X, 0.18x Y parallax)
            val nearCityX = -camX * 0.36f * scale
            val nearCityY = -(camY - 140f) * 0.18f * scale
            for (i in 0..26) {
                val nx = nearCityX + (i * 210f + 40f) * scale
                if (nx < -140f * scale || nx > w + 140f * scale) continue
                val nw = 82f * scale
                val nh = (95f + (i * 29 % 65)) * scale
                val ny = h - nh + nearCityY

                drawRect(
                    color = Color(0xEE07021A),
                    topLeft = Offset(nx, ny),
                    size = Size(nw, nh + 60f * scale)
                )
                val neonBorder = if (i % 2 == 0) Color(0x99FF007F) else Color(0x9900F0FF)
                drawRect(
                    color = neonBorder,
                    topLeft = Offset(nx, ny),
                    size = Size(nw, nh + 60f * scale),
                    style = Stroke(width = 1.8f * scale)
                )
            }

            // LAYER 4: Foreground Synthwave Horizon Grid & Energy Conduits (0.55x X, 0.25x Y parallax)
            val gridShiftX = -camX * 0.55f * scale
            val gridBaseY = h - 26f * scale - (camY - 140f) * 0.25f * scale
            drawRect(
                color = Color(0xDD050114),
                topLeft = Offset(0f, gridBaseY),
                size = Size(w, 80f * scale)
            )
            drawLine(
                color = Color(0xCC00F0FF),
                start = Offset(0f, gridBaseY),
                end = Offset(w, gridBaseY),
                strokeWidth = 2f * scale
            )
            for (g in -4..55) {
                val gx = gridShiftX + g * 85f * scale
                if (gx < -100f * scale || gx > w + 100f * scale) continue
                drawLine(
                    color = Color(0x55FF007F),
                    start = Offset(gx, gridBaseY),
                    end = Offset(gx - 28f * scale, gridBaseY + 45f * scale),
                    strokeWidth = 1.5f * scale
                )
            }
        }

        LevelType.ESPACIO -> {
            // LAYER 0: Deep Cosmic Void & Multi-Cloud Nebula (0.03x parallax)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF04010A),
                        Color(0xFF0D0422),
                        Color(0xFF1B0938),
                        Color(0xFF090318)
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // Nebula clouds shifting gently
            val nebShiftX = -camX * 0.04f * scale
            val nebShiftY = -(camY - 120f) * 0.03f * scale
            for (n in 0..5) {
                val nx = nebShiftX + (260f + n * 480f) * scale
                val ny = (120f + (n % 2) * 110f) * scale + nebShiftY
                val nebColor = when (n % 3) {
                    0 -> Color(0x389D4EDD)
                    1 -> Color(0x3000BBF9)
                    else -> Color(0x32F15BB5)
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(nebColor, Color.Transparent),
                        center = Offset(nx, ny),
                        radius = 190f * scale
                    ),
                    radius = 190f * scale,
                    center = Offset(nx, ny)
                )
            }

            // LAYER 1: 3-Depth Parallax Starfield (0.05x, 0.12x, 0.22x X/Y parallax)
            val starLayers = listOf(
                Triple(0.05f, 1.1f, 0.45f),
                Triple(0.12f, 1.8f, 0.72f),
                Triple(0.22f, 2.6f, 0.95f)
            )
            starLayers.forEachIndexed { layerIdx, (pFactor, baseRadius, alpha) ->
                val rng = Random(42 + layerIdx * 97)
                val sxShift = -camX * pFactor * scale
                val syShift = -(camY - 120f) * (pFactor * 0.6f) * scale
                val spanW = w + 240f * scale
                val spanH = h + 120f * scale
                for (i in 0..32) {
                    val rawX = rng.nextFloat() * 2600f * scale + sxShift
                    val rawY = rng.nextFloat() * 900f * scale + syShift
                    val wrappedX = ((rawX % spanW) + spanW) % spanW - 120f * scale
                    val wrappedY = ((rawY % spanH) + spanH) % spanH - 60f * scale
                    val starColor = when ((i + layerIdx) % 4) {
                        0 -> Color(0xFFB8F2FF)
                        1 -> Color(0xFFFFD6FF)
                        else -> Color.White
                    }
                    drawCircle(
                        color = starColor.copy(alpha = alpha),
                        radius = baseRadius * scale,
                        center = Offset(wrappedX, wrappedY)
                    )
                }
            }

            // LAYER 2: Ringed Gas Giant & Cratered Companion Moon (0.09x X, 0.05x Y parallax)
            val planetShiftX = -camX * 0.09f * scale
            val planetShiftY = -(camY - 120f) * 0.05f * scale
            val planetCenter = Offset(planetShiftX + 640f * scale, 145f * scale + planetShiftY)
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF9D4EDD), Color(0xFF5A189A), Color(0xFF240046)),
                    start = Offset(planetCenter.x - 65f * scale, planetCenter.y - 65f * scale),
                    end = Offset(planetCenter.x + 65f * scale, planetCenter.y + 65f * scale)
                ),
                radius = 68f * scale,
                center = planetCenter
            )
            drawOval(
                color = Color(0x99E0AAFF),
                topLeft = Offset(planetCenter.x - 115f * scale, planetCenter.y - 12f * scale),
                size = Size(230f * scale, 26f * scale),
                style = Stroke(width = 5f * scale)
            )

            // Secondary cyan moon further along the level
            val moonCenter = Offset(planetShiftX + 1280f * scale, 105f * scale + planetShiftY)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF72EFDD), Color(0xFF0077B6)),
                    center = moonCenter,
                    radius = 34f * scale
                ),
                radius = 34f * scale,
                center = moonCenter
            )

            // LAYER 3: Distant Orbital Station Ring & Solar Wings (0.24x X, 0.12x Y parallax)
            val stationShiftX = -camX * 0.24f * scale
            val stationShiftY = -(camY - 140f) * 0.12f * scale
            for (s in 0..8) {
                val stX = stationShiftX + (s * 460f + 220f) * scale
                if (stX < -180f * scale || stX > w + 180f * scale) continue
                val stY = h - (140f + (s % 2) * 55f) * scale + stationShiftY
                // Central orbital hub + solar panels
                drawRect(
                    color = Color(0x88161B33),
                    topLeft = Offset(stX - 65f * scale, stY - 8f * scale),
                    size = Size(130f * scale, 16f * scale)
                )
                drawRect(
                    color = Color(0x5500F0FF),
                    topLeft = Offset(stX - 65f * scale, stY - 8f * scale),
                    size = Size(130f * scale, 16f * scale),
                    style = Stroke(width = 1.2f * scale)
                )
                drawCircle(
                    color = Color(0xAA1F2747),
                    radius = 22f * scale,
                    center = Offset(stX, stY)
                )
                drawCircle(
                    color = Color(0xCC00F0FF),
                    radius = 4f * scale,
                    center = Offset(stX, stY)
                )
            }

            // LAYER 4: Near Asteroid Belt & Space Horizon Ridge (0.45x X, 0.22x Y parallax)
            val astShiftX = -camX * 0.45f * scale
            val astShiftY = -(camY - 140f) * 0.22f * scale
            for (a in 0..24) {
                val ax = astShiftX + (a * 195f + 60f) * scale
                if (ax < -90f * scale || ax > w + 90f * scale) continue
                val ay = h - (45f + (a * 31 % 95)) * scale + astShiftY
                val ar = (14f + (a % 4) * 6f) * scale
                drawCircle(
                    color = Color(0xCC1E1633),
                    radius = ar,
                    center = Offset(ax, ay)
                )
                drawCircle(
                    color = Color(0x559D4EDD),
                    radius = ar,
                    center = Offset(ax, ay),
                    style = Stroke(width = 1.5f * scale)
                )
            }
        }
    }
}

fun DrawScope.drawGamePlatform(platform: Platform, scale: Float) {
    val r = platform.rect
    val scaledRect = Rect(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale)

    if (platform.isSpiky) {
        // Draw toxic spikes
        val spikeColor = platform.color
        val width = scaledRect.right - scaledRect.left
        val spikeWidth = 20f * scale
        val spikeHeight = 15f * scale
        val count = (width / spikeWidth).toInt()

        // Base bed of hazard
        drawRect(
            color = Color(0xFF32041A),
            topLeft = Offset(scaledRect.left, scaledRect.top + 6f * scale),
            size = Size(width, scaledRect.bottom - scaledRect.top - 6f * scale)
        )

        // Triangle points
        for (i in 0..count) {
            val sx = scaledRect.left + i * spikeWidth
            val path = Path().apply {
                moveTo(sx, scaledRect.top + spikeHeight)
                lineTo(sx + spikeWidth / 2f, scaledRect.top)
                lineTo(sx + spikeWidth, scaledRect.top + spikeHeight)
                close()
            }
            drawPath(path, spikeColor)
            // Outline safety glow
            drawPath(path, Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.2f * scale))
        }
    } else {
        // Draw elegant rounded glowing platform
        drawRoundRect(
            color = platform.color,
            topLeft = Offset(scaledRect.left, scaledRect.top),
            size = Size(scaledRect.width, scaledRect.height),
            cornerRadius = CornerRadius(5f * scale, 5f * scale)
        )
        // Accent edge shine line (top part)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = Offset(scaledRect.left + 2f * scale, scaledRect.top),
            size = Size(scaledRect.width - 4f * scale, 3f * scale),
            cornerRadius = CornerRadius(1f * scale, 1f * scale)
        )
        // Bottom shade
        drawRect(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(scaledRect.left, scaledRect.bottom - 6f * scale),
            size = Size(scaledRect.width, 6f * scale)
        )
    }
}

fun DrawScope.drawCollectible(collectible: Collectible, scale: Float) {
    val cx = collectible.x * scale
    val cy = collectible.y * scale
    val radius = collectible.radius * scale

    when (collectible.type) {
        CollectibleType.COIN -> {
            // Golden Rotating Coin
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFF176), Color(0xFFFBC02D)),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
            // Border outline
            drawCircle(
                color = Color(0xFFFFD700),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 2f * scale)
            )
            // Inner symbol detail
            drawCircle(
                color = Color(0xFFFFB300),
                radius = radius * 0.45f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f * scale)
            )
        }
        CollectibleType.DIAMOND -> {
            // Neon Cyan Diamond
            val p = Path().apply {
                moveTo(cx, cy - radius) // top
                lineTo(cx + radius, cy) // right
                lineTo(cx, cy + radius) // bottom
                lineTo(cx - radius, cy) // left
                close()
            }
            drawPath(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFE0F7FA), Color(0xFF00E5FF)),
                    startY = cy - radius,
                    endY = cy + radius
                ),
                path = p
            )
            drawPath(
                color = Color.White,
                path = p,
                style = Stroke(width = 2f * scale)
            )
            // Reflection line
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(cx, cy - radius * 0.6f),
                end = Offset(cx, cy + radius * 0.6f),
                strokeWidth = 1.5f * scale
            )
        }
        CollectibleType.HEART -> {
            // Juicy Healing Red Heart
            val path = Path().apply {
                // Heart shape path centered on cx, cy
                val leftControlX = cx - radius * 1.5f
                val rightControlX = cx + radius * 1.5f
                moveTo(cx, cy - radius * 0.4f)
                cubicTo(cx - radius * 0.7f, cy - radius * 1.5f, leftControlX, cy - radius * 0.5f, cx, cy + radius * 1.1f)
                cubicTo(rightControlX, cy - radius * 0.5f, cx + radius * 0.7f, cy - radius * 1.5f, cx, cy - radius * 0.4f)
            }
            drawPath(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFF5252), Color(0xFFFF1744)),
                    startY = cy - radius,
                    endY = cy + radius
                ),
                path = path
            )
            drawPath(
                color = Color.White,
                path = path,
                style = Stroke(width = 1.8f * scale)
            )
        }
        CollectibleType.GRAVITY_BOOTS -> {
            // High jump glowing boots symbol
            drawRoundRect(
                color = Color(0xFFFFEA00),
                topLeft = Offset(cx - radius * 1.2f, cy - radius * 0.8f),
                size = Size(radius * 2.4f, radius * 1.6f),
                cornerRadius = CornerRadius(4f * scale, 4f * scale)
            )
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(cx - radius * 1.2f, cy - radius * 0.8f),
                size = Size(radius * 2.4f, radius * 1.6f),
                cornerRadius = CornerRadius(4f * scale, 4f * scale),
                style = Stroke(width = 1.5f * scale)
            )
            // Accent arrow pointing up
            val arrowPath = Path().apply {
                moveTo(cx, cy - radius * 0.5f)
                lineTo(cx - radius * 0.5f, cy + radius * 0.1f)
                lineTo(cx - radius * 0.2f, cy + radius * 0.1f)
                lineTo(cx - radius * 0.2f, cy + radius * 0.5f)
                lineTo(cx + radius * 0.2f, cy + radius * 0.5f)
                lineTo(cx + radius * 0.2f, cy + radius * 0.1f)
                lineTo(cx + radius * 0.5f, cy + radius * 0.1f)
                close()
            }
            drawPath(path = arrowPath, color = Color.Black)
        }
    }
}

fun DrawScope.drawEnemy(enemy: Enemy, scale: Float) {
    val ex = enemy.x * scale
    val ey = enemy.y * scale
    val ew = enemy.width * scale
    val eh = enemy.height * scale

    when (enemy.type) {
        EnemyType.GREEN_SLIME -> {
            // Bouncing slime body
            val slimePath = Path().apply {
                moveTo(ex, ey + eh) // bottom-left
                cubicTo(ex, ey + eh * 0.2f, ex + ew, ey + eh * 0.2f, ex + ew, ey + eh) // upper dome
                lineTo(ex, ey + eh)
                close()
            }
            drawPath(slimePath, enemy.color)
            drawPath(slimePath, Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.5f * scale))
            
            // Funny angry big eyes
            drawCircle(Color.White, 4f * scale, Offset(ex + ew * 0.35f, ey + eh * 0.55f))
            drawCircle(Color.White, 4f * scale, Offset(ex + ew * 0.65f, ey + eh * 0.55f))
            drawCircle(Color.Black, 2f * scale, Offset(ex + ew * 0.38f, ey + eh * 0.58f))
            drawCircle(Color.Black, 2f * scale, Offset(ex + ew * 0.62f, ey + eh * 0.58f))
        }
        EnemyType.CYAN_TURRET -> {
            // Mechanical hover droid robot
            drawRoundRect(
                color = enemy.color,
                topLeft = Offset(ex, ey),
                size = Size(ew, eh * 0.7f),
                cornerRadius = CornerRadius(6f * scale, 6f * scale)
            )
            // Cyber eye
            drawRect(
                color = Color.Black,
                topLeft = Offset(ex + ew * 0.15f, ey + eh * 0.2f),
                size = Size(ew * 0.7f, eh * 0.3f)
            )
            // Red laser lens glowing center
            drawCircle(
                color = Color.Red,
                radius = 3f * scale,
                center = Offset(ex + ew * 0.5f, ey + eh * 0.35f)
            )
            // Hover repulsor flame
            val flamePath = Path().apply {
                moveTo(ex + ew * 0.3f, ey + eh * 0.7f)
                lineTo(ex + ew * 0.5f, ey + eh)
                lineTo(ex + ew * 0.7f, ey + eh * 0.7f)
                close()
            }
            drawPath(flamePath, Color(0xFFFFBF00))
        }
        EnemyType.RED_DROID -> {
            // Fast laser droid
            drawCircle(
                color = enemy.color,
                radius = ew / 2f,
                center = Offset(ex + ew/2f, ey + eh/2f)
            )
            drawCircle(
                color = Color.White,
                radius = ew / 2f,
                center = Offset(ex + ew/2f, ey + eh/2f),
                style = Stroke(width = 1.8f * scale)
            )
            // Spikes on droid
            drawCircle(Color.Black, 4f * scale, Offset(ex + ew * 0.3f, ey + eh * 0.4f))
            drawCircle(Color.Black, 4f * scale, Offset(ex + ew * 0.7f, ey + eh * 0.4f))
            drawCircle(Color(0xFFFF1744), 2f * scale, Offset(ex + ew * 0.3f, ey + eh * 0.4f))
            drawCircle(Color(0xFFFF1744), 2f * scale, Offset(ex + ew * 0.7f, ey + eh * 0.4f))
        }
    }
}

fun DrawScope.drawGoalFlag(flagX: Float, scale: Float) {
    val fx = flagX * scale
    val fy = 450f * scale // level ground approx
    
    // Draw flag poll
    drawLine(
        color = Color(0xFFECEFF1),
        start = Offset(fx, fy),
        end = Offset(fx, fy - 180f * scale),
        strokeWidth = 3.5f * scale
    )
    
    // Golden star crown at top
    drawCircle(Color(0xFFFFEB3B), 10f * scale, Offset(fx, fy - 185f * scale))
    
    // Vibrant flashing neon cyan/pink waving triangle flag
    val flagTriangle = Path().apply {
        moveTo(fx, fy - 170f * scale)
        lineTo(fx + 60f * scale, fy - 140f * scale)
        lineTo(fx, fy - 110f * scale)
        close()
    }
    drawPath(
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF39FF14), Color(0xFF00F0FF))
        ),
        path = flagTriangle
    )
}

fun DrawScope.drawParticle(particle: Particle, scale: Float) {
    val px = particle.x * scale
    val py = particle.y * scale
    val size = particle.size * scale
    val alpha = particle.currentLife.toFloat() / particle.maxLife.toFloat()
    
    drawCircle(
        color = particle.color.copy(alpha = alpha),
        radius = size,
        center = Offset(px, py)
    )
}

fun DrawScope.drawPlayer(engine: GameEngine, scale: Float) {
    val px = engine.playerX * scale
    val py = engine.playerY * scale
    val pw = engine.playerWidth * scale
    val ph = engine.playerHeight * scale

    // Damage flashing
    var playerColor = engine.activeSkinColor
    if (engine.invincibilityFrames > 0) {
        // rapid blink color red/white
        if (engine.invincibilityFrames % 6 >= 3) {
            playerColor = Color(0xFFFF007F) // striking neon magenta
        }
    }
    
    // Jetpack Booster trail behind player if jump active or jumping
    if (!engine.isGrounded) {
        val firePath = Path().apply {
            moveTo(px + pw * 0.2f, py + ph * 0.9f)
            lineTo(px + pw * 0.5f, py + ph + 22f * scale)
            lineTo(px + pw * 0.8f, py + ph * 0.9f)
            close()
        }
        drawPath(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFFFEB3B), Color(0xFFFF5722), Color(0x00FF5722))
            ),
            path = firePath
        )
    }

    // Main Astronaut Cosmic suit body
    drawRoundRect(
        color = playerColor,
        topLeft = Offset(px, py),
        size = Size(pw, ph * 0.85f),
        cornerRadius = CornerRadius(10f * scale, 10f * scale)
    )

    // Astronaut dark blue glowing dome visor
    drawRoundRect(
        color = Color(0xFF0B1B3D),
        topLeft = Offset(px + pw * 0.15f, py + ph * 0.12f),
        size = Size(pw * 0.7f, ph * 0.35f),
        cornerRadius = CornerRadius(6f * scale, 6f * scale)
    )
    
    // Visor reflection shine
    drawRoundRect(
        color = Color.White.copy(alpha = 0.6f),
        topLeft = Offset(px + pw * 0.22f, py + ph * 0.16f),
        size = Size(pw * 0.25f, ph * 0.08f),
        cornerRadius = CornerRadius(2f * scale, 2f * scale)
    )

    // Active high jump boots visual addition (Super gravity shoes)
    if (engine.tempGravityBootsTimer > 0) {
        // Draw golden yellow stripes at their feet
        drawRect(
            color = Color(0xFFFFEB3B),
            topLeft = Offset(px, py + ph * 0.75f),
            size = Size(pw, ph * 0.18f)
        )
        // White energy outline
        drawRect(
            color = Color.White,
            topLeft = Offset(px, py + ph * 0.75f),
            size = Size(pw, ph * 0.18f),
            style = Stroke(width = 1.5f * scale)
        )
    } else {
        // Normal shoes
        drawRect(
            color = Color(0xFFECEFF1),
            topLeft = Offset(px, py + ph * 0.80f),
            size = Size(pw * 0.44f, ph * 0.12f)
        )
        drawRect(
            color = Color(0xFFECEFF1),
            topLeft = Offset(px + pw * 0.56f, py + ph * 0.80f),
            size = Size(pw * 0.44f, ph * 0.12f)
        )
    }

    // Oxygen life system pack (Backpack) drawn on the correct side based on locomotion index
    val packX = if (engine.isMovingLeft) px + pw * 0.85f else px - pw * 0.2f
    drawRoundRect(
        color = Color(0xFF78909C),
        topLeft = Offset(packX, py + ph * 0.25f),
        size = Size(pw * 0.3f, ph * 0.45f),
        cornerRadius = CornerRadius(3f * scale, 3f * scale)
    )
}

fun DrawScope.drawFloatingText(floatingText: FloatingText, scale: Float) {
    val paint = Paint().asFrameworkPaint().apply {
        color = floatingText.color.toArgb()
        textSize = 14f * scale
        isFakeBoldText = true
        alpha = ((floatingText.currentLife.toFloat() / floatingText.maxLife.toFloat()) * 255).toInt().coerceIn(0, 255)
    }
    
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(
            floatingText.text,
            floatingText.x * scale - 25 * scale,
            floatingText.y * scale,
            paint
        )
    }
}


// --------------------------------------------------------------------------
// COMPOSER UI LAYOVER MODULES
// --------------------------------------------------------------------------

@Composable
fun HUDView(
    engine: GameEngine,
    fpsOverlayEnabled: Boolean = false,
    currentFps: Int = 60,
    musicEnabled: Boolean = true,
    onToggleMusic: () -> Unit = {},
    onPause: () -> Unit = {}
) {
    // Ultra-slim, single-row top bar hugged tightly to the top edge, well clear of gameplay
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 14.dp, end = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Compact Health Hearts + inline power-up indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0x66000000), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "VIDAS ",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                for (i in 1..5) {
                    Text(
                        text = if (i <= engine.playerLives) "❤️" else "🖤",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 0.5.dp)
                    )
                }
            }

            if (engine.tempGravityBootsTimer > 0) {
                Row(
                    modifier = Modifier
                        .background(Color(0xCCFFEA00), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "👟 ${engine.tempGravityBootsTimer / 60}s",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Center: Slim single-line progress indicator
        val progress = (engine.playerX / engine.levelGoalX).coerceIn(0f, 1f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(Color(0x66000000), RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = engine.currentLevelType.displayName,
                color = Color(0xFF00F0FF),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .width(72.dp)
                    .height(5.dp)
                    .clip(CircleShape),
                color = Color(0xFF39FF14),
                trackColor = Color(0x33FFFFFF)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Right: Compact single-line Coins + Score + FPS + Pause button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (fpsOverlayEnabled) {
                Text(
                    text = "$currentFps FPS",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(Color(0x55000000), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0x66000000), RoundedCornerShape(50))
                    .border(1.dp, Color(0xFFFFD700).copy(alpha = 0.25f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "🪙 ${engine.playerCoins}",
                    color = Color(0xFFFFEA00),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0x66000000), RoundedCornerShape(50))
                    .border(1.dp, Color(0xFF00FFFF).copy(alpha = 0.2f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "PTS ",
                    color = Color(0xFF00F0FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = String.format("%06d", engine.playerScore),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("score_text")
                )
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .border(
                        1.dp,
                        if (musicEnabled) Color(0xFF00F0FF).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.25f),
                        CircleShape
                    )
                    .clickable { onToggleMusic() }
                    .testTag("hud_music_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (musicEnabled) "🎵" else "🔇",
                    color = Color.White,
                    fontSize = 10.sp
                )
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { onPause() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⏸",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun GameControlsView(engine: GameEngine, gameState: GameState, onPause: () -> Unit) {
    // Ergonomic thumb zone: inset comfortably from corners without intruding into the center
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 32.dp, start = 64.dp, end = 64.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // LEFT DPAD SYSTEM
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Left button
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            if (engine.isMovingLeft) Color(0x45FFFFFF) else Color(0x18FFFFFF)
                        )
                        .border(
                            1.5.dp,
                            if (engine.isMovingLeft) Color.White else Color.White.copy(alpha = 0.22f),
                            CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    engine.pressLeft()
                                    tryAwaitRelease()
                                    engine.releaseLeft()
                                }
                            )
                        }
                        .testTag("left_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◀",
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Right button
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            if (engine.isMovingRight) Color(0x45FFFFFF) else Color(0x18FFFFFF)
                        )
                        .border(
                            1.5.dp,
                            if (engine.isMovingRight) Color.White else Color.White.copy(alpha = 0.22f),
                            CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    engine.pressRight()
                                    tryAwaitRelease()
                                    engine.releaseRight()
                                }
                            )
                        }
                        .testTag("right_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "▶",
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // RIGHT PRIMARY JUMP ACTION
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0x26D0BCFF))
                    .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { engine.jump() }
                    .testTag("jump_button"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "↑",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "SALTAR",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}


// --- SCREEN OVERLAYS ---

@Composable
fun MainMenuScreen(engine: GameEngine, logoScale: Float, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFC11092B), Color(0xFE04020A)),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Branding and Floating Icon
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .scale(logoScale)
                        .background(Color(0x22FF007F), CircleShape)
                        .border(1.dp, Color(0xFFFF007F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🛸", fontSize = 32.sp)
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "AVENTURA",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                )
                Text(
                    text = "COSMICA RETRO",
                    color = Color(0xFF00F0FF),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Record badge
                Text(
                    text = "RÉCORD MÁXIMO: ${String.format("%06d", engine.playerHighscore)} PTOS",
                    color = Color(0xFFFFEA00),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Right Column: Level details and Big Play
            Column(
                modifier = Modifier.weight(1.2f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "SELECCIONAR NIVEL:",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LevelType.values().forEachIndexed { index, level ->
                        val isSelected = engine.currentLevelIndex == index
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    1.dp, 
                                    if (isSelected) Color(0xFFFF007F) else Color.White.copy(alpha = 0.1f), 
                                    RoundedCornerShape(8.dp)
                                )
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { engine.loadLevel(index) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF22113A) else Color(0xFF0A0718)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "NIVEL ${index + 1}",
                                    color = if (isSelected) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.5f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = level.displayName.split(" ")[0],
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("play_game_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "play",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AL ATAQUE",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PauseMenuScreen(onResume: () -> Unit, onRestart: () -> Unit, onExit: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD04020B)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(280.dp)
                .shadow(16.dp, RoundedCornerShape(24.dp))
                .border(2.dp, Color(0xFF00F0FF), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0924))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "JUEGO EN PAUSA",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("REANUDAR", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x66FFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("REINICIAR", color = Color.White)
                }

                OutlinedButton(
                    onClick = onExit,
                    border = BorderStroke(1.dp, Color(0xFFFF007F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("MENU PRINCIPAL", color = Color(0xFFFF007F))
                }
            }
        }
    }
}

@Composable
fun GameOverScreen(score: Int, highScore: Int, onRestart: () -> Unit, onExit: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF014020F)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Text stats
            Column(
                modifier = Modifier.weight(1.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "💀 FIN DEL JUEGO 💀",
                    color = Color(0xFFFF007F),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "Te quedaste sin vidas en el espacio sideral.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "PUNTAJE FINAL:  ${String.format("%06d ptos", score)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (score >= highScore && score > 0) {
                    Text(
                        text = "🏆 ¡NUEVO RÉCORD MÁXIMO! 🏆",
                        color = Color(0xFFFFEA00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "RECORD ACTUAL: ${String.format("%06d", highScore)}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Right Column: Controls
            Column(
                modifier = Modifier.weight(0.9f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(44.dp)
                        .testTag("restart_game_button")
                ) {
                    Icon(Icons.Default.Refresh, "retry", tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("REINTENTAR NIVEL", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                ) {
                    Text("MENU PRINCIPAL", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun VictoryScreen(score: Int, highScore: Int, onRestart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Victory textual details
            Column(
                modifier = Modifier.weight(1.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "👑 ¡VICTORIA TOTAL! 👑",
                    color = Color(0xFF39FF14),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "¡Increíble! Superaste los tres niveles y colonizaste todas las galaxias.",
                    color = Color.White,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "PUNTAJE ABSOLUTO:",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = String.format("%06d ptos", score),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }

            // Right Column: Return actions
            Column(
                modifier = Modifier.weight(0.9f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(46.dp)
                ) {
                    Text("VOLVER AL MENÚ", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// MULTI-TAB EXPANSIONS (PROFESSIONAL POLISH)
// --------------------------------------------------------------------------

@Composable
fun LevelsTabScreen(engine: GameEngine, onSelectLevel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120E22))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "GALAXIA DE NIVELES",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        
        Text(
            text = "Selecciona un nivel para comenzar tu exploración sideral y batir récords:",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val levelNames = listOf(
                Triple("Valle de Cristales", "Nivel inicial con plataformas flotantes y slimes saltarines.", "🟢 DIFICULTAD: FÁCIL"),
                Triple("Tempestad de Neón", "Furia de tormenta estelar con plataformas móviles y torretas láser.", "🟡 DIFICULTAD: MEDIA"),
                Triple("Centro Espacial", "Gravedad cero, múltiples androides patrulla y saltos peligrosos.", "🔴 DIFICULTAD: DIFÍCIL")
            )
            
            levelNames.forEachIndexed { index, data ->
                val (name, desc, difficulty) = data
                val isSelected = engine.currentLevelIndex == index
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            2.dp,
                            if (isSelected) Color(0xFFD0BCFF) else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            engine.loadLevel(index)
                            onSelectLevel()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF261D45) else Color(0xFF0F0B1E)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Nivel ${index + 1}: $name",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isSelected) {
                                    Text(
                                        text = " (ACTUAL)",
                                        color = Color(0xFF00F0FF),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = desc,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = difficulty,
                                color = if (index == 0) Color(0xFF4CAF50) else if (index == 1) Color(0xFFFF9800) else Color(0xFFFF5252),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color(0xFFD0BCFF) else Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🚀", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkinsShopTabScreen(
    engine: GameEngine,
    unlockedSkins: Set<String>,
    onSkinUnlocked: (String, Int) -> Unit
) {
    val skins = remember { AVAILABLE_SKINS }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF140C26))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Accumulated Coins wallet indicator (separate from high score!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(Color(0xFF22143B), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MONEDAS ACUMULADAS",
                    color = Color(0xFF00F0FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Récord Máximo intacto: 🏆 ${engine.playerHighscore} PTOS",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            }
            Text(
                text = "🪙 ${engine.playerCoins}",
                color = Color(0xFFFFEB3B),
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("shop_coins_balance")
            )
        }

        Text(
            text = "TIENDA DE TRAJES COSMÉTICOS",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            skins.forEach { skin ->
                val isUnlocked = unlockedSkins.contains(skin.id)
                val isEquipped = engine.activeSkinId == skin.id

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEquipped) Color(0xFF321A4B) else Color(0xFF180E2E)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Emoji avatar view
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(skin.color.copy(alpha = 0.25f))
                                .border(2.dp, skin.color, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = skin.emoji, fontSize = 24.sp)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skin.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = skin.description,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isUnlocked) {
                            Button(
                                onClick = {
                                    engine.equipSkin(skin.id)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isEquipped) Color(0xFF39FF14) else Color(0x33FFFFFF)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("equip_skin_${skin.id}")
                            ) {
                                Text(
                                    text = if (isEquipped) "EQUIPADO" else "EQUIPAR",
                                    color = if (isEquipped) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            val canAfford = engine.playerCoins >= skin.cost
                            Button(
                                onClick = {
                                    if (canAfford) {
                                        onSkinUnlocked(skin.id, skin.cost)
                                    }
                                },
                                enabled = canAfford,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFC107),
                                    disabledContainerColor = Color(0x33FFFFFF)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("buy_skin_${skin.id}")
                            ) {
                                Text(
                                    text = "🪙 ${skin.cost}",
                                    color = if (canAfford) Color.Black else Color.White.copy(alpha = 0.4f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTabScreen(
    gravityFactor: Float,
    onGravityChange: (Float) -> Unit,
    speedFactor: Float,
    onSpeedChange: (Float) -> Unit,
    jumpFactor: Float,
    onJumpChange: (Float) -> Unit,
    soundEffectsEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    soundVolume: Float = 0.85f,
    onSoundVolumeChange: (Float) -> Unit = {},
    onTestSound: (RetroSoundEffect) -> Unit = {},
    musicEnabled: Boolean = true,
    onMusicToggle: (Boolean) -> Unit = {},
    musicVolume: Float = 0.65f,
    onMusicVolumeChange: (Float) -> Unit = {},
    selectedMusicTheme: MusicTrackTheme = MusicTrackTheme.AUTO_LEVEL,
    activeTrackName: String = "Obertura Galáctica",
    onSelectMusicTheme: (MusicTrackTheme) -> Unit = {},
    fpsOverlayEnabled: Boolean,
    onFpsToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C071C))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "🎵 MÚSICA DE FONDO DEL JUEGO",
            color = Color(0xFF00F0FF),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // MUSIC CONTROLS CARD
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1038)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF00F0FF).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Music Toggle + Active Track Display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Música de Fondo Dinámica",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (musicEnabled) "Sonando: $activeTrackName" else "Música en silencio",
                            color = if (musicEnabled) Color(0xFF39FF14) else Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = musicEnabled,
                        onCheckedChange = onMusicToggle,
                        modifier = Modifier.testTag("music_enable_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00F0FF),
                            checkedTrackColor = Color(0x4400F0FF)
                        )
                    )
                }

                if (musicEnabled) {
                    // Music Volume Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Volumen de la Música", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${(musicVolume * 100).toInt()}%", color = Color(0xFF00F0FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = musicVolume,
                            onValueChange = onMusicVolumeChange,
                            valueRange = 0.05f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00F0FF),
                                activeTrackColor = Color(0xFF00F0FF)
                            )
                        )
                    }
                }

                // Built-in Level Soundtracks Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SELECCIONAR TEMA MUSICAL:",
                        color = Color(0xFFD0BCFF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    val themeRows = MusicTrackTheme.entries.chunked(3)
                    themeRows.forEach { rowThemes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowThemes.forEach { theme ->
                                val isSelected = selectedMusicTheme == theme
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) Color(0xFF321D63) else Color(0xFF110A26)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF00F0FF) else Color.White.copy(alpha = 0.15f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { onSelectMusicTheme(theme) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = theme.title,
                                            color = if (isSelected) Color(0xFF00F0FF) else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = theme.subtitle,
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 8.sp,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "PARÁMETROS DE FÍSICA & EFECTOS SFX",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF160E30)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Gravity slider adjustment
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Gravedad de Caída", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(String.format("%.1fx", gravityFactor), color = Color(0xFF00F0FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = gravityFactor,
                        onValueChange = onGravityChange,
                        valueRange = 0.5f..1.8f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFD0BCFF),
                            activeTrackColor = Color(0xFFD0BCFF)
                        )
                    )
                }

                // Speed slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Velocidad Horizontal", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(String.format("%.1fx", speedFactor), color = Color(0xFF00F0FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = speedFactor,
                        onValueChange = onSpeedChange,
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFD0BCFF),
                            activeTrackColor = Color(0xFFD0BCFF)
                        )
                    )
                }

                // Jump power
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Empuje de Jets / Salto", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(String.format("%.1fx", jumpFactor), color = Color(0xFF00F0FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = jumpFactor,
                        onValueChange = onJumpChange,
                        valueRange = 0.6f..1.6f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFD0BCFF),
                            activeTrackColor = Color(0xFFD0BCFF)
                        )
                    )
                }

                // Sound Effect toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Efectos de Audio Retro (SoundPool)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Switch(
                        checked = soundEffectsEnabled,
                        onCheckedChange = onSoundToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF39FF14),
                            checkedTrackColor = Color(0x4439FF14)
                        )
                    )
                }

                if (soundEffectsEnabled) {
                    // SoundPool Volume Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Volumen de Efectos Retro", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${(soundVolume * 100).toInt()}%", color = Color(0xFF39FF14), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = soundVolume,
                            onValueChange = onSoundVolumeChange,
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF39FF14),
                                activeTrackColor = Color(0xFF39FF14)
                            )
                        )
                    }

                    // Interactive SoundPool Test Bank
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "PROBAR EFECTOS RETRO (BAJA LATENCIA)",
                            color = Color(0xFF00F0FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val testButtons = listOf(
                                "🚀 Salto" to RetroSoundEffect.JUMP,
                                "🪙 Moneda" to RetroSoundEffect.COIN,
                                "💎 Diamante" to RetroSoundEffect.DIAMOND,
                                "💥 Colisión" to RetroSoundEffect.COLLISION
                            )
                            testButtons.forEach { (label, effect) ->
                                OutlinedButton(
                                    onClick = { onTestSound(effect) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.45f)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // FPS display switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Superposición de FPS", color = Color.White, fontSize = 12.sp)
                    Switch(
                        checked = fpsOverlayEnabled,
                        onCheckedChange = onFpsToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF39FF14),
                            checkedTrackColor = Color(0x4439FF14)
                        )
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "🕹 GUÍA DE CONTROLES RÁPIDOS 🕹\nUtiliza los botones táctiles en pantalla para mover el personaje a la izquierda y derecha. Presiona SALTAR (admite doble salto!) para rebasar los picos de energía y las patrullas enemigas.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
