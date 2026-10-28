package com.example.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.random.Random

enum class GameState {
    MENU,
    PLAYING,
    PAUSED,
    GAME_OVER,
    VICTORY
}

enum class LevelType(val displayName: String, val description: String) {
    VALLE("VALLE ESMERALDA", "Nivel 1 • Pradera Soleada"),
    NEON("TEMPESTAD DE NEÓN", "Nivel 2 • Ciber-Desafío"),
    ESPACIO("CENTRO ESPACIAL", "Nivel 3 • Gravedad Cero")
}

enum class CollectibleType {
    COIN,
    DIAMOND,
    HEART,
    GRAVITY_BOOTS
}

enum class EnemyType {
    GREEN_SLIME,
    CYAN_TURRET,
    RED_DROID
}

data class Platform(
    val rect: Rect,
    val color: Color,
    val isSpiky: Boolean = false,
    val movesHorizontally: Boolean = false,
    val movesVertically: Boolean = false,
    val travelRange: Float = 150f,
    val speed: Float = 2f,
    val originalRect: Rect = rect // To calculate movement relative to start
) {
    var currentOffset = 0f
    var direction = 1f

    fun update(): Platform {
        if (!movesHorizontally && !movesVertically) return this
        val nextOffset = currentOffset + speed * direction
        val newDirection = if (abs(nextOffset) >= travelRange) -direction else direction
        
        val newRect = if (movesHorizontally) {
            Rect(
                left = originalRect.left + nextOffset,
                top = originalRect.top,
                right = originalRect.right + nextOffset,
                bottom = originalRect.bottom
            )
        } else {
            Rect(
                left = originalRect.left,
                top = originalRect.top + nextOffset,
                right = originalRect.right,
                bottom = originalRect.bottom + nextOffset
            )
        }
        
        val updated = this.copy(rect = newRect)
        updated.currentOffset = nextOffset
        updated.direction = newDirection
        return updated
    }
}

data class Collectible(
    val id: Int,
    val x: Float,
    val y: Float,
    val type: CollectibleType,
    val radius: Float = 12f,
    var isCollected: Boolean = false
) {
    fun getBounds(): Rect {
        return Rect(x - radius, y - radius, x + radius, y + radius)
    }
}

data class Enemy(
    val id: Int,
    val type: EnemyType,
    var x: Float,
    var y: Float,
    val width: Float = 40f,
    val height: Float = 30f,
    var vx: Float = 2.5f,
    val minX: Float,
    val maxX: Float,
    val color: Color,
    var isDead: Boolean = false
) {
    fun update() {
        if (isDead) return
        x += vx
        if (x <= minX) {
            x = minX
            vx = -vx
        } else if (x >= maxX - width) {
            x = maxX - width
            vx = -vx
        }
    }

    fun getBounds(): Rect {
        return Rect(x, y, x + width, y + height)
    }
}

data class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val size: Float,
    val maxLife: Int,
    var currentLife: Int = maxLife
) {
    fun update(): Particle? {
        if (currentLife <= 0) return null
        return this.copy(
            x = x + vx,
            y = y + vy + 0.1f, // slight gravity
            currentLife = currentLife - 1
        )
    }
}

data class FloatingText(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Color,
    val maxLife: Int = 45,
    var currentLife: Int = maxLife
) {
    fun update(): FloatingText? {
        if (currentLife <= 0) return null
        return this.copy(
            y = y - 1.5f, // floats upwards
            currentLife = currentLife - 1
        )
    }
}

class GameEngine {
    // Engine constants
    var gravity = 0.8f
    var jumpPower = -17f
    var walkSpeed = 7.5f
    val maxLevelWidth = 4500f
    val virtualHeight = 600f
    val virtualViewportHeight = 450f
    var viewportWidth = 960f

    // Inputs
    var isMovingLeft = false
    var isMovingRight = false

    // Player State
    var playerX by mutableStateOf(100f)
    var playerY by mutableStateOf(400f)
    var playerVx = 0f
    var playerVy = 0f
    val playerWidth = 36f
    val playerHeight = 46f
    var playerLives by mutableStateOf(3)
    var playerScore by mutableStateOf(0)
    var playerHighscore by mutableStateOf(0)
    var activeSkinColor by mutableStateOf(Color(0xFF00FFFF))
    var activeSkinName by mutableStateOf("Astronauta Ciber")
    var doubleJumpAvailable by mutableStateOf(true)
    var isGrounded by mutableStateOf(false)
    var invincibilityFrames by mutableStateOf(0)

    // Powers
    var tempGravityBootsTimer by mutableStateOf(0) // Timer for high jump mode
    
    // Level State
    var currentLevelIndex by mutableStateOf(0)
    var currentLevelType by mutableStateOf(LevelType.VALLE)
    var platforms = mutableListOf<Platform>()
    var collectibles = mutableListOf<Collectible>()
    var enemies = mutableListOf<Enemy>()
    
    // Aesthetic feeds
    var particles = mutableListOf<Particle>()
    var floatingTexts = mutableListOf<FloatingText>()
    var cameraX by mutableStateOf(0f)
    var cameraY by mutableStateOf(150f)
    var screenShakeTimer = 0
    var screenShakeIntensity = 0f

    // Goal line
    var levelGoalX = 4200f

    // Low-latency retro sound effect callback
    var onSoundEffect: ((RetroSoundEffect) -> Unit)? = null

    init {
        loadLevel(0)
    }

    fun loadLevel(index: Int) {
        currentLevelIndex = index
        currentLevelType = when (index) {
            0 -> LevelType.VALLE
            1 -> LevelType.NEON
            else -> LevelType.ESPACIO
        }

        // Reset player positions, velocities & dynamic states
        playerX = 100f
        playerY = 300f
        playerVx = 0f
        playerVy = 0f
        isGrounded = false
        doubleJumpAvailable = true
        invincibilityFrames = 0
        tempGravityBootsTimer = 0
        cameraX = 0f
        cameraY = 150f

        // Clear sub systems
        particles.clear()
        floatingTexts.clear()

        // Distribute levels
        setupLevelData(index)
    }

    private fun setupLevelData(index: Int) {
        platforms.clear()
        collectibles.clear()
        enemies.clear()

        when (index) {
            0 -> { // Valle Esmeralda (Green nature / gentle)
                levelGoalX = 4200f
                // Starter ground
                platforms.add(Platform(Rect(0f, 500f, 800f, 600f), Color(0xFF2E7D32))) // mosy grass green
                
                // Platforms
                platforms.add(Platform(Rect(300f, 380f, 480f, 410f), Color(0xFF4CAF50)))
                platforms.add(Platform(Rect(580f, 260f, 750f, 290f), Color(0xFF81C784)))
                
                // Gap platform
                platforms.add(Platform(Rect(900f, 480f, 1300f, 600f), Color(0xFF2E7D32)))
                platforms.add(Platform(Rect(1050f, 340f, 1180f, 370f), Color(0xFF4CAF50)))
                
                // Moving horizontally
                platforms.add(Platform(
                    rect = Rect(1450f, 400f, 1650f, 430f),
                    color = Color(0xFFFF9800),
                    movesHorizontally = true,
                    travelRange = 120f,
                    speed = 2.5f
                ))

                platforms.add(Platform(Rect(1900f, 480f, 2400f, 600f), Color(0xFF2E7D32)))
                // Some spikes in a pit
                platforms.add(Platform(Rect(2100f, 450f, 2200f, 480f), Color(0xFFD32F2F), isSpiky = true))

                // Vertical moving safety
                platforms.add(Platform(
                    rect = Rect(2550f, 420f, 2750f, 450f),
                    color = Color(0xFFFF9800),
                    movesVertically = true,
                    travelRange = 100f,
                    speed = 2f
                ))

                platforms.add(Platform(Rect(2900f, 360f, 3100f, 390f), Color(0xFF81C784)))
                platforms.add(Platform(Rect(3250f, 260f, 3450f, 290f), Color(0xFF4CAF50)))
                
                // Goal land
                platforms.add(Platform(Rect(3700f, 450f, 4500f, 600f), Color(0xFF2E7D32)))
                
                // Collectibles (Coins & Diamonds)
                addCoin(1, 350f, 330f)
                addCoin(2, 400f, 330f)
                addCoin(3, 450f, 330f)
                addCoin(4, 630f, 210f)
                addCoin(5, 700f, 210f)
                addCoin(6, 1110f, 290f)
                addCoin(7, 1550f, 340f) // hovering over moving platform
                addDiamond(8, 2150f, 260f) // diamond high up over spikes!
                addCoin(9, 3000f, 300f)
                addCoin(10, 3350f, 200f)
                addCoin(11, 3900f, 390f)
                addCoin(12, 4050f, 390f)
                addHeart(13, 2000f, 420f) // Bonus heart!

                // Enemies (Slimes)
                // Id, type, x, y, width, height, vx, minX, maxX, color
                enemies.add(Enemy(101, EnemyType.GREEN_SLIME, 500f, 465f, 40f, 35f, 2f, 100f, 750f, Color(0xFF64DD17)))
                enemies.add(Enemy(102, EnemyType.GREEN_SLIME, 1000f, 445f, 40f, 35f, 2.5f, 920f, 1260f, Color(0xFF64DD17)))
                enemies.add(Enemy(103, EnemyType.GREEN_SLIME, 2050f, 445f, 40f, 35f, 3f, 1910f, 2380f, Color(0xFF64DD17)))
                enemies.add(Enemy(104, EnemyType.GREEN_SLIME, 3850f, 415f, 40f, 35f, 2f, 3720f, 4150f, Color(0xFF64DD17)))
            }

            1 -> { // Tempestad de Neón (Cyan & Purple cyberpunk night)
                levelGoalX = 4300f
                // Neon levels are darker, more vertical gap jumps, spikes, and fast moving platforms
                // Spawn land
                platforms.add(Platform(Rect(0f, 480f, 600f, 600f), Color(0xFF1A1A3A))) // cyber grey-blue
                
                // Diagonal platforms
                platforms.add(Platform(Rect(350f, 360f, 500f, 385f), Color(0xFF00F0FF))) // Neon Cyan
                platforms.add(Platform(Rect(580f, 250f, 730f, 275f), Color(0xFFFF007F))) // Neon Pink
                
                // Deep spikes pit with tiny safety islands
                platforms.add(Platform(Rect(780f, 520f, 1500f, 600f), Color(0xFF9C27B0), isSpiky = true)) // Pink spikes bed
                platforms.add(Platform(Rect(900f, 400f, 1000f, 420f), Color(0xFF00F0FF)))
                platforms.add(Platform(Rect(1150f, 320f, 1250f, 340f), Color(0xFFFF007F)))
                platforms.add(Platform(Rect(1380f, 400f, 1480f, 420f), Color(0xFF00F0FF)))
                
                // Platform scrolling horizontally quickly
                platforms.add(Platform(
                    rect = Rect(1620f, 460f, 1780f, 485f),
                    color = Color(0xFFE040FB),
                    movesHorizontally = true,
                    travelRange = 180f,
                    speed = 4f
                ))
                
                // Safe block in center
                platforms.add(Platform(Rect(1950f, 420f, 2300f, 600f), Color(0xFF1A1A3A)))
                platforms.add(Platform(Rect(2050f, 280f, 2200f, 305f), Color(0xFF00F0FF)))
                
                // Double vertical elevators
                platforms.add(Platform(
                    rect = Rect(2420f, 400f, 2550f, 420f),
                    color = Color(0xFFFFEB3B),
                    movesVertically = true,
                    travelRange = 120f,
                    speed = 3f
                ))
                platforms.add(Platform(
                    rect = Rect(2700f, 250f, 2830f, 270f),
                    color = Color(0xFFFFEB3B),
                    movesVertically = true,
                    travelRange = 120f,
                    speed = -3f // opposite directions
                ))

                // Toxic hazard pit
                platforms.add(Platform(Rect(2900f, 520f, 3500f, 600f), Color(0xFFFF007F), isSpiky = true))
                platforms.add(Platform(Rect(3020f, 400f, 3140f, 420f), Color(0xFF00F0FF)))
                platforms.add(Platform(Rect(3260f, 290f, 3380f, 310f), Color(0xFFFF007F)))
                
                // Landing ground
                platforms.add(Platform(Rect(3650f, 460f, 4500f, 600f), Color(0xFF1A1A3A)))

                // Add Collectibles
                addCoin(201, 380f, 310f)
                addCoin(202, 450f, 310f)
                addCoin(203, 610f, 200f)
                addCoin(204, 680f, 200f)
                
                addDiamond(205, 950f, 350f)
                addDiamond(206, 1200f, 270f)
                addDiamond(207, 1430f, 350f)
                
                addGravityBoots(208, 2125f, 220f) // Boost powers up here!
                addCoin(209, 2125f, 160f)
                addCoin(210, 2125f, 100f) // Reachable with high jump boots!
                
                addDiamond(211, 3080f, 340f)
                addCoin(212, 3320f, 230f)
                addHeart(213, 3750f, 400f)

                // Enemies (Cyber turrets / droids)
                // They patrol much faster!
                enemies.add(Enemy(251, EnemyType.CYAN_TURRET, 250f, 440f, 42f, 40f, 3.5f, 50f, 580f, Color(0xFF00E5FF)))
                enemies.add(Enemy(252, EnemyType.CYAN_TURRET, 2000f, 380f, 42f, 40f, 3.0f, 1960f, 2280f, Color(0xFF00E5FF)))
                enemies.add(Enemy(253, EnemyType.CYAN_TURRET, 3800f, 420f, 42f, 40f, 4f, 3680f, 4400f, Color(0xFFFF1744)))
            }

            else -> { // Centro Espacial (Future space / galaxy core)
                levelGoalX = 4400f
                // Spawn pad
                platforms.add(Platform(Rect(0f, 460f, 450f, 600f), Color(0xFF263238))) // deep matte dark-grey
                
                // High distance floating pads
                platforms.add(Platform(Rect(550f, 410f, 700f, 430f), Color(0xFF18FFFF)))
                platforms.add(Platform(Rect(800f, 340f, 950f, 360f), Color(0xFFFFE082)))
                platforms.add(Platform(Rect(1050f, 270f, 1200f, 290f), Color(0xFF18FFFF)))
                
                // Falling trap bed (full spikes except moving platform)
                platforms.add(Platform(Rect(1250f, 530f, 2400f, 600f), Color(0xFFFF5252), isSpiky = true))
                
                // Moving horizontal linking platform
                platforms.add(Platform(
                    rect = Rect(1300f, 340f, 1500f, 360f),
                    color = Color(0xFFFFF176),
                    movesHorizontally = true,
                    travelRange = 150f,
                    speed = 3f
                ))
                
                // Floating high rock
                platforms.add(Platform(Rect(1900f, 300f, 2100f, 320f), Color(0xFF18FFFF)))
                
                // Another moving platform going up and down
                platforms.add(Platform(
                    rect = Rect(2160f, 420f, 2300f, 440f),
                    color = Color(0xFFFFF176),
                    movesVertically = true,
                    travelRange = 150f,
                    speed = 3.5f
                ))
                
                // Giant space station reactor core
                platforms.add(Platform(Rect(2480f, 450f, 3200f, 600f), Color(0xFF263238)))
                // Reactor beam center is hot spikes
                platforms.add(Platform(Rect(2750f, 420f, 2950f, 451f), Color(0xFFFF1744), isSpiky = true))
                
                // High floating islands
                platforms.add(Platform(Rect(2550f, 320f, 2700f, 340f), Color(0xFF311B92)))
                platforms.add(Platform(Rect(3000f, 320f, 3150f, 340f), Color(0xFF311B92)))

                // Long final jump sequence
                platforms.add(Platform(Rect(3300f, 400f, 3450f, 420f), Color(0xFF18FFFF)))
                platforms.add(Platform(Rect(3550f, 320f, 3700f, 340f), Color(0xFFFFF176)))
                
                // Safe dome at end
                platforms.add(Platform(Rect(3850f, 420f, 4600f, 600f), Color(0xFF263238)))

                // Add Collectibles
                addCoin(301, 620f, 360f)
                addCoin(302, 875f, 290f)
                addCoin(303, 1125f, 220f)
                
                addDiamond(304, 1400f, 260f)
                addDiamond(305, 2000f, 220f)
                
                addGravityBoots(306, 2625f, 260f) // Zero-gravity boots!
                addDiamond(307, 2850f, 150f) // Float way high over the reactor beam!
                addDiamond(308, 2850f, 100f)
                addDiamond(309, 3075f, 260f)
                
                addHeart(310, 3950f, 350f)
                addCoin(311, 4100f, 350f)
                addCoin(312, 4200f, 350f)

                // Space Droids
                enemies.add(Enemy(351, EnemyType.RED_DROID, 150f, 410f, 45f, 45f, 4.5f, 40f, 410f, Color(0xFFFF1744)))
                enemies.add(Enemy(352, EnemyType.RED_DROID, 2550f, 400f, 45f, 45f, 5f, 2490f, 2740f, Color(0xFFFF1744)))
                enemies.add(Enemy(353, EnemyType.RED_DROID, 3050f, 400f, 45f, 45f, 5.5f, 2960f, 3190f, Color(0xFFFF1744)))
                enemies.add(Enemy(354, EnemyType.RED_DROID, 4000f, 370f, 45f, 45f, 3f, 3870f, 4400f, Color(0xFFFFEB3B)))
            }
        }
    }

    private fun addCoin(id: Int, x: Float, y: Float) {
        collectibles.add(Collectible(id, x, y, CollectibleType.COIN))
    }

    private fun addDiamond(id: Int, x: Float, y: Float) {
        collectibles.add(Collectible(id, x, y, CollectibleType.DIAMOND))
    }

    private fun addHeart(id: Int, x: Float, y: Float) {
        collectibles.add(Collectible(id, x, y, CollectibleType.HEART))
    }

    private fun addGravityBoots(id: Int, x: Float, y: Float) {
        collectibles.add(Collectible(id, x, y, CollectibleType.GRAVITY_BOOTS))
    }

    // Input Actions
    fun pressLeft() {
        isMovingLeft = true
    }

    fun releaseLeft() {
        isMovingLeft = false
    }

    fun pressRight() {
        isMovingRight = true
    }

    fun releaseRight() {
        isMovingRight = false
    }

    fun jump() {
        if (isGrounded) {
            // Standard Jump
            val currentJumpPower = if (tempGravityBootsTimer > 0) jumpPower * 1.35f else jumpPower
            playerVy = currentJumpPower
            isGrounded = false
            doubleJumpAvailable = true
            spawnJumpParticles()
            addFloatingText("¡SALTO!", playerX + playerWidth / 2f, playerY, Color(0xFF00F0FF))
            onSoundEffect?.invoke(RetroSoundEffect.JUMP)
        } else if (doubleJumpAvailable) {
            // Double Jump
            val currentJumpPower = if (tempGravityBootsTimer > 0) jumpPower * 1.25f else jumpPower * 0.9f
            playerVy = currentJumpPower
            doubleJumpAvailable = false
            spawnJumpParticles()
            addFloatingText("¡DOBLE!", playerX + playerWidth / 2f, playerY, Color(0xFFFF007F))
            onSoundEffect?.invoke(RetroSoundEffect.DOUBLE_JUMP)
        }
    }

    // State Mutators
    fun restartGame() {
        playerLives = 3
        playerScore = 0
        isMovingLeft = false
        isMovingRight = false
        loadLevel(currentLevelIndex)
    }

    // Spawners
    fun spawnJumpParticles() {
        val count = 8
        for (i in 0 until count) {
            particles.add(
                Particle(
                    x = playerX + playerWidth / 2f,
                    y = playerY + playerHeight,
                    vx = (Random.nextFloat() - 0.5f) * 6f,
                    vy = (Random.nextFloat() - 0.2f) * -3f,
                    color = Color(0xAAFFFFFF),
                    size = Random.nextFloat() * 6f + 3f,
                    maxLife = 20 + Random.nextInt(15)
                )
            )
        }
    }

    fun spawnExplosionParticles(px: Float, py: Float, color: Color, count: Int = 12) {
        for (i in 0 until count) {
            particles.add(
                Particle(
                    x = px,
                    y = py,
                    vx = (Random.nextFloat() - 0.5f) * 12f,
                    vy = (Random.nextFloat() - 0.5f) * 12f,
                    color = color,
                    size = Random.nextFloat() * 8f + 4f,
                    maxLife = 30 + Random.nextInt(20)
                )
            )
        }
    }

    fun addFloatingText(text: String, x: Float, y: Float, color: Color) {
        floatingTexts.add(FloatingText(text, x, y - 20f, color))
    }

    fun triggerScreenShake(intensity: Float, duration: Int) {
        screenShakeIntensity = intensity
        screenShakeTimer = duration
    }

    // THE PHYSICS STEP RUNS CONTINUOUSLY IN THE GAME TICK
    fun updatePhysics() {
        if (screenShakeTimer > 0) {
            screenShakeTimer--
        }

        // Apply horizontal movement inputs
        var desiredVx = 0f
        if (isMovingLeft) desiredVx -= walkSpeed
        if (isMovingRight) desiredVx += walkSpeed
        playerVx = desiredVx

        // Apply Gravity
        playerVy += gravity

        // Update levels/platform animations
        for (i in platforms.indices) {
            platforms[i] = platforms[i].update()
        }

        // Update enemies
        for (enemy in enemies) {
            enemy.update()
        }

        // Keep track of moving platform helper offsets (if riding one)
        var ridingPlatform: Platform? = null

        // Update vertical position first for landing calculations
        val oldY = playerY
        playerY += playerVy

        // Collision logic
        isGrounded = false
        val playerRect = Rect(playerX, playerY, playerX + playerWidth, playerY + playerHeight)

        for (platform in platforms) {
            val pRect = platform.rect
            
            // Check vertical collision (landing on top)
            if (playerRect.overlaps(pRect)) {
                // If player was falling and is mostly above the platform top boundary
                val wasAbove = oldY + playerHeight - 2f <= pRect.top + abs(platform.speed * 2)
                if (playerVy > 0f && wasAbove) {
                    playerY = pRect.top - playerHeight
                    playerVy = 0f
                    isGrounded = true
                    doubleJumpAvailable = true
                    
                    // Ride horizontal/vertical assets
                    if (platform.movesHorizontally || platform.movesVertically) {
                        ridingPlatform = platform
                    }
                    
                    // Check if landed on spike platform
                    if (platform.isSpiky) {
                        playerHitByHazard()
                    }
                }
            }
        }

        // Update horizontal position next
        playerX += playerVx
        
        // Horizontal boundary checks
        if (playerX < 0) playerX = 0f
        if (playerX > maxLevelWidth - playerWidth) playerX = maxLevelWidth - playerWidth

        // Ride moving platform motion additions (horizontal)
        if (ridingPlatform != null) {
            if (ridingPlatform.movesHorizontally) {
                playerX += ridingPlatform.speed * ridingPlatform.direction
            }
            if (ridingPlatform.movesVertically) {
                playerY += ridingPlatform.speed * ridingPlatform.direction
            }
        }

        // Re-check side collisions with solid platforms (to stop walk-thru actions)
        val playerRHorizontal = Rect(playerX, playerY, playerX + playerWidth, playerY + playerHeight)
        for (platform in platforms) {
            if (platform.isSpiky) continue // Spikes shouldn't block laterally as physical walls
            val pRect = platform.rect
            if (playerRHorizontal.overlaps(pRect)) {
                // Determine collision depth from both sides
                val overlapLeft = (playerX + playerWidth) - pRect.left
                val overlapRight = pRect.right - playerX
                
                // If vertically embedded, push out horizontally
                if (playerY + playerHeight - 5f > pRect.top && playerY + 5f < pRect.bottom) {
                    if (overlapLeft < overlapRight) {
                        playerX -= overlapLeft
                    } else {
                        playerX += overlapRight
                    }
                    playerVx = 0f
                }
            }
        }

        // Spikes and Hazards side collisions
        val playerRAll = Rect(playerX, playerY, playerX + playerWidth, playerY + playerHeight)
        for (platform in platforms) {
            if (platform.isSpiky && playerRAll.overlaps(platform.rect)) {
                playerHitByHazard()
            }
        }

        // Handle active collectibles overlapping
        for (item in collectibles) {
            if (!item.isCollected && playerRAll.overlaps(item.getBounds())) {
                item.isCollected = true
                when (item.type) {
                    CollectibleType.COIN -> {
                        playerScore += 100
                        addFloatingText("+100", item.x, item.y, Color(0xFFFFFF00))
                        spawnExplosionParticles(item.x, item.y, Color(0xFFFFD700), 5)
                        onSoundEffect?.invoke(RetroSoundEffect.COIN)
                    }
                    CollectibleType.DIAMOND -> {
                        playerScore += 500
                        addFloatingText("+500 DIAMANTE", item.x, item.y, Color(0xFF00FFFF))
                        spawnExplosionParticles(item.x, item.y, Color(0xFF00E5FF), 8)
                        triggerScreenShake(4f, 5)
                        onSoundEffect?.invoke(RetroSoundEffect.DIAMOND)
                    }
                    CollectibleType.HEART -> {
                        if (playerLives < 5) {
                            playerLives++
                            addFloatingText("+1 VIDA ❤️", item.x, item.y, Color(0xFFFF2A6D))
                        } else {
                            playerScore += 1000
                            addFloatingText("+1000 MAX SALUD", item.x, item.y, Color(0xFFFF2A6D))
                        }
                        spawnExplosionParticles(item.x, item.y, Color(0xFFFF2A6D), 8)
                        onSoundEffect?.invoke(RetroSoundEffect.POWERUP)
                    }
                    CollectibleType.GRAVITY_BOOTS -> {
                        tempGravityBootsTimer = 350 // approx 6-7 seconds of high jump
                        addFloatingText("¡SUPERSALTO ACTIVO!", item.x, item.y, Color(0xFFFFF176))
                        spawnExplosionParticles(item.x, item.y, Color(0xFFFFEB3B), 10)
                        triggerScreenShake(5f, 8)
                        onSoundEffect?.invoke(RetroSoundEffect.POWERUP)
                    }
                }
                
                // Update highscore
                if (playerScore > playerHighscore) {
                    playerHighscore = playerScore
                }
            }
        }

        // Handle Active Power timers
        if (tempGravityBootsTimer > 0) {
            tempGravityBootsTimer--
            // Spawn little trail sparkles
            if (tempGravityBootsTimer % 4 == 0) {
                particles.add(
                    Particle(
                        x = playerX + playerWidth / 2f + (Random.nextFloat() - 0.5f) * playerWidth,
                        y = playerY + playerHeight - 10f,
                        vx = (Random.nextFloat() - 0.5f) * 2f,
                        vy = 1f,
                        color = Color(0xFFFFFF00),
                        size = Random.nextFloat() * 4f + 2f,
                        maxLife = 15
                    )
                )
            }
        }

        // Collision with Enemies
        for (enemy in enemies) {
            if (enemy.isDead) continue
            val eRect = enemy.getBounds()
            if (playerRAll.overlaps(eRect)) {
                // If player is falling downswards and lands on enemy's top head
                val playerBottomY = oldY + playerHeight
                val enemyTopLimitY = enemy.y + 12f
                if (playerVy > 0f && playerBottomY <= enemyTopLimitY) {
                    // STOMP DEFEATED!
                    enemy.isDead = true
                    playerVy = jumpPower * 0.75f // bounce up
                    playerScore += 300
                    addFloatingText("+300 ¡STOMP!", enemy.x + enemy.width / 2f, enemy.y, Color(0xFF39FF14))
                    spawnExplosionParticles(enemy.x + enemy.width / 2f, enemy.y + enemy.height / 2f, enemy.color, 15)
                    triggerScreenShake(6f, 10)
                    onSoundEffect?.invoke(RetroSoundEffect.STOMP)
                } else {
                    // PLAYER HIT!
                    playerHitByHazard()
                }
            }
        }

        // Fall into the bottom abyss check
        if (playerY > virtualHeight + 100f) {
            playerFallAbyss()
        }

        // Check level objective goal reach
        if (playerX >= levelGoalX) {
            levelComplete()
        }

        // Diminish invincibility frames
        if (invincibilityFrames > 0) {
            invincibilityFrames--
        }

        // Camera scroll logic centered on player X and Y
        val targetCamX = playerX - viewportWidth * 0.32f
        cameraX += (targetCamX - cameraX) * 0.12f
        val maxCameraLimit = (maxLevelWidth - viewportWidth).coerceAtLeast(0f)
        if (cameraX < 0f) cameraX = 0f
        if (cameraX > maxCameraLimit) cameraX = maxCameraLimit

        val maxCamY = virtualHeight - virtualViewportHeight // 150f aligns 600f world bottom to screen bottom
        val targetCamY = (playerY - 220f).coerceIn(-30f, maxCamY)
        cameraY += (targetCamY - cameraY) * 0.12f

        // Cycle aesthetic particles
        val nextParticles = mutableListOf<Particle>()
        for (p in particles) {
            val updated = p.update()
            if (updated != null) nextParticles.add(updated)
        }
        particles = nextParticles

        // Cycle floating texts
        val nextTexts = mutableListOf<FloatingText>()
        for (t in floatingTexts) {
            val updated = t.update()
            if (updated != null) nextTexts.add(updated)
        }
        floatingTexts = nextTexts
    }

    private fun playerHitByHazard() {
        if (invincibilityFrames > 0) return
        
        playerLives--
        triggerScreenShake(12f, 18)
        
        if (playerLives <= 0) {
            playerLives = 0
            spawnExplosionParticles(playerX + playerWidth/2, playerY + playerHeight/2, Color(0xFFFF007F), 25)
            addFloatingText("¡FIN DEL JUEGO!", playerX, playerY, Color(0xFFFF007F))
            onSoundEffect?.invoke(RetroSoundEffect.COLLISION)
            onSoundEffect?.invoke(RetroSoundEffect.GAME_OVER)
        } else {
            invincibilityFrames = 60 // 1 second of flashing immunity
            playerVy = -8f // small pushback bounce up
            playerVx = if (isMovingLeft) 5f else -5f
            addFloatingText("-1 VIDA", playerX, playerY - 30f, Color(0xFFFF0000))
            spawnExplosionParticles(playerX + playerWidth/2, playerY + playerHeight/2, Color(0xFFFF0000), 10)
            onSoundEffect?.invoke(RetroSoundEffect.COLLISION)
        }
    }

    private fun playerFallAbyss() {
        playerLives = 0
        triggerScreenShake(15f, 20)
        onSoundEffect?.invoke(RetroSoundEffect.GAME_OVER)
    }

    private fun levelComplete() {
        onSoundEffect?.invoke(RetroSoundEffect.LEVEL_COMPLETE)
        if (currentLevelIndex < 2) {
            // Unlocked next level automatically
            // Give victory splash but keep playing next
            addFloatingText("¡NIVEL COMPLETADO!", playerX, playerY, Color(0xFF39FF14))
            loadLevel(currentLevelIndex + 1)
        } else {
            // Beat the final level!
            // Change Game state to VICTORY
        }
    }
}
