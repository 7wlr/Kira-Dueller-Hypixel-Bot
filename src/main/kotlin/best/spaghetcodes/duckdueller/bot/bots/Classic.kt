package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.DuckDueller
import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.features.Bow
import best.spaghetcodes.duckdueller.bot.features.MovePriority
import best.spaghetcodes.duckdueller.bot.features.Rod
import best.spaghetcodes.duckdueller.bot.player.Combat
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.bot.player.Movement
import best.spaghetcodes.duckdueller.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class Classic : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String = "Classic"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.classic_duel_wins",
                "losses" to "player.stats.Duels.classic_duel_losses",
                "ws" to "player.stats.Duels.current_classic_winstreak",
            )
        )
    }

    private val jumpDistanceThreshold = 5.0f

    // --- Strafe state ---
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var stagnantSince = 0L
    private var cornerBreakUntil = 0L

    // --- Rod control ---
    private var rodLockUntil = 0L
    private var lastRodUse = 0L
    private var prevDistance = -1f

    // --- Parade épée / états associés ---
    private var gameStartAt = 0L
    private var lastSwordBlock = 0L
    private var oppStillSince = 0L
    private var holdBlockUntil = 0L

    // --- Anti jump après avoir été touché ---
    private var noJumpUntil = 0L
    private var lastHurtTime = 0

    var shotsFired = 0
    var maxArrows = 5

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))

        prevDistance = -1f
        lastRodUse = 0L
        rodLockUntil = 0L
        lastStrafeSwitch = 0L
        stagnantSince = 0L
        cornerBreakUntil = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

        Mouse.rClickUp()               // jamais bloqué d’entrée
        gameStartAt = System.currentTimeMillis()
        lastSwordBlock = 0L
        oppStillSince = 0L
        holdBlockUntil = 0L

        noJumpUntil = 0L
        lastHurtTime = 0

        // Tir d’ouverture (full charge via Bow.kt) si aucune action en cours
        TimeUtils.setTimeout({
            val opp = opponent()
            if (opp != null && shotsFired < maxArrows && !Mouse.isUsingProjectile()) {
                val d = EntityUtils.getDistanceNoY(mc.thePlayer, opp)
                useBow(d) { shotsFired++ }
            }
        }, RandomUtils.randomIntInRange(300, 500))
    }

    override fun onGameEnd() {
        shotsFired = 0
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout(fun () {
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    var tapping = false

    override fun onAttack() {
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3f) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) {
                    // W-Tap long après hit à la rod (affiché)
                    ChatUtils.info("W-Tap 300")
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () { tapping = false }, 300)
                }
                // pas de block-hit à l’épée — l’attaque est gérée par ton autre mod
            }
        } else {
            // Petit W-Tap pour maintenir la pression (affiché)
            ChatUtils.info("W-Tap 100")
            Combat.wTap(100)
            tapping = true
            TimeUtils.setTimeout(fun () { tapping = false }, 100)
        }
        if (combo >= 3) Movement.clearLeftRight()
    }

    override fun onTick() {
        var needJump = false
        if (mc.thePlayer != null) {
            if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) != Blocks.air && mc.thePlayer.onGround) {
                needJump = true
                Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
            }
        }
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) Movement.startSprinting()

            val opp = opponent()!!
            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opp)
            val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)
            val now = System.currentTimeMillis()

            Mouse.startTracking()
            Mouse.stopLeftAC()

            // --- Anti jump après coup : si on vient d’être touché, bloque le jump un court instant
            val ht = mc.thePlayer.hurtTime
            if (ht > 0 && lastHurtTime == 0) {
                noJumpUntil = now + RandomUtils.randomIntInRange(360, 520)
            }
            lastHurtTime = ht

            // --- Détection "immobile" de l’adversaire (vitesse lissée)
            val oppSpeed = kotlin.math.abs(opp.motionX) + kotlin.math.abs(opp.motionZ)
            val isStill = oppSpeed < 0.035
            if (isStill) {
                if (oppStillSince == 0L) oppStillSince = now
            } else {
                oppStillSince = 0L
            }
            val stillMs = if (oppStillSince == 0L) 0 else (now - oppStillSince)

            // --- Parade épée : garder si l’autre est immobile, couper quand il bouge (mais pas 100%)
            val holdingSword = mc.thePlayer.heldItem != null &&
                mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")
            val oppHasBow = opp.heldItem != null &&
                opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val sinceStart = now - gameStartAt

            if (holdingSword) {
                // Si on bloque déjà : on continue tant que l’adversaire reste immobile,
                // sinon on relâche avec une proba (pour ne pas être 100% lisible).
                if (Mouse.rClickDown) {
                    val startedMoving = (oppSpeed > 0.07) || approaching
                    if (startedMoving) {
                        // 75% de chance d’arrêter immédiatement, 25% de tenir un peu pour "bait"
                        val stopNow = RandomUtils.randomIntInRange(0, 99) < 75
                        if (stopNow || now >= holdBlockUntil) {
                            Mouse.rClickUp()
                        }
                    } else if (now >= holdBlockUntil) {
                        // durée prévue écoulée : on lâche pour ne pas rester figé trop longtemps
                        Mouse.rClickUp()
                    }
                } else {
                    // Conditions d’entrée en block :
                    // - pas au tout début
                    // - assez loin
                    // - adversaire immobile depuis un petit délai
                    // - pas en projectile
                    // - si arc en main : plus prompt ; sinon, petite chance de "fake block"
                    val needStillMs = if (oppHasBow) RandomUtils.randomIntInRange(260, 420)
                                      else RandomUtils.randomIntInRange(380, 560)
                    val fakeBlockChance = if (oppHasBow) 100 else 25 // 100% si arc, sinon 25%
                    val allowFake = RandomUtils.randomIntInRange(0, 99) < fakeBlockChance

                    val readyToStartBlock =
                        sinceStart > 2000 &&
                        distance > 12f &&
                        stillMs >= needStillMs &&
                        !Mouse.isUsingProjectile() &&
                        (oppHasBow || allowFake) &&
                        (now - lastSwordBlock) > 900

                    if (readyToStartBlock) {
                        val dur = if (oppHasBow)
                            RandomUtils.randomIntInRange(780, 1050)
                        else
                            RandomUtils.randomIntInRange(650, 900)
                        holdBlockUntil = now + dur
                        lastSwordBlock = now
                        Mouse.rClick(dur)

                        // Évitement de flèches : injecter un strafe court et aléatoire
                        if (oppHasBow && distance > 10f) {
                            if (now - lastStrafeSwitch > 220) {
                                if (RandomUtils.randomIntInRange(0, 1) == 0) strafeDir = -strafeDir
                                lastStrafeSwitch = now
                            }
                        }
                    } else if (Mouse.rClickDown) {
                        // sécurité : ne jamais rester bloqué si les conditions ne sont plus vraies
                        Mouse.rClickUp()
                    }
                }
            }

            // Empêche de sauter pendant qu’on bloque, et pendant le no-jump post-hit
            val canJump = (now >= noJumpUntil) && !Mouse.rClickDown

            // Sauts “humains” (avec canJump)
            if (distance > jumpDistanceThreshold) {
                if (oppHasBow) {
                    if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) == Blocks.air) {
                        if (!EntityUtils.entityFacingAway(mc.thePlayer, opp) && !needJump) {
                            Movement.stopJumping()
                        } else {
                            if (canJump) Movement.startJumping() else Movement.stopJumping()
                        }
                    } else {
                        if (canJump) Movement.startJumping() else Movement.stopJumping()
                    }
                } else {
                    if (canJump) Movement.startJumping() else Movement.stopJumping()
                }
            } else if (!needJump) {
                Movement.stopJumping()
            }

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            if (distance < 1f || (distance < 2.7f && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping) {
                Movement.startForward()
            }

            if (!Mouse.isUsingProjectile() && now >= rodLockUntil && !Mouse.rClickDown) {
                if (distance < 1.5f && mc.thePlayer.heldItem != null &&
                    !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                    Inventory.setInvItem("sword")
                }
            }

            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

                // Cooldown global rod
                val minCd = if (distance < 5.3f) 650 else 900
                val cdOK = (now - lastRodUse) >= minCd

                // --- (A) Anti-bow mid-range: casse le "rod → arrow" à 5–6 blocs ---
                if (cdOK &&
                    oppHasBow &&
                    distance in 4.8f..7.2f &&
                    !EntityUtils.entityFacingAway(mc.thePlayer, opp) &&
                    now >= rodLockUntil) {

                    val lockMs = if (distance < 6f)
                        RandomUtils.randomIntInRange(300, 360)
                    else
                        RandomUtils.randomIntInRange(340, 420)
                    rodLockUntil = now + lockMs
                    lastRodUse = now
                    useRod()

                // --- (B) Anti-rod mid-range classique (approche & face & combo faible) ---
                } else if (cdOK &&
                           distance in 3.4f..5.2f &&
                           !EntityUtils.entityFacingAway(mc.thePlayer, opp) &&
                           approaching &&
                           combo <= 1 &&
                           now >= rodLockUntil) {

                    val lockMs = when {
                        distance < 4.0f -> RandomUtils.randomIntInRange(260, 320)
                        distance < 4.6f -> RandomUtils.randomIntInRange(300, 360)
                        else            -> RandomUtils.randomIntInRange(340, 420)
                    }
                    rodLockUntil = now + lockMs
                    lastRodUse = now
                    useRod()

                // --- (C) Fenêtre 5.7..6.5 resserrée (approche & face & combo faible) ---
                } else if (cdOK &&
                           distance in 5.7f..6.5f &&
                           !EntityUtils.entityFacingAway(mc.thePlayer, opp) &&
                           approaching &&
                           combo <= 1 &&
                           now >= rodLockUntil) {

                    val lockMs = if (distance < 6.1f)
                        RandomUtils.randomIntInRange(320, 380)
                    else
                        RandomUtils.randomIntInRange(360, 440)
                    rodLockUntil = now + lockMs
                    lastRodUse = now
                    useRod()

                // --- (D) Bow windows (safe shots) ---
                } else if ((EntityUtils.entityFacingAway(mc.thePlayer, opp) && distance in 3.5f..30f) ||
                           (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(mc.thePlayer, opp))) {
                    if (distance > 10f && shotsFired < maxArrows) {
                        clear = true
                        useBow(distance) { shotsFired++ }
                    } else {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    }
                }
            }

            if (combo >= 3 && distance >= 3.2f && mc.thePlayer.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
            }

            // --- Anti-corner & strafe ---
            val blockAheadClose = WorldUtils.blockInFront(mc.thePlayer, 1.2f, 1.0f) != Blocks.air
            val deltaDist = if (prevDistance > 0f) kotlin.math.abs(distance - prevDistance) else 999f
            val cornerLikely = (distance < 3.8f && (blockAheadClose || deltaDist < 0.02f))

            if (cornerLikely && now >= cornerBreakUntil) {
                strafeDir = -strafeDir
                cornerBreakUntil = now + RandomUtils.randomIntInRange(280, 420)
                if (mc.thePlayer.onGround) {
                    Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
                }
            }

            if (!clear) {
                if (EntityUtils.entityFacingAway(mc.thePlayer, opp)) {
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                } else {
                    val rotations = EntityUtils.getRotations(opp, mc.thePlayer, false)
                    if (rotations != null && now - lastStrafeSwitch > 350) {
                        val preferSide = if (rotations[0] < 0) +1 else -1
                        if (preferSide != strafeDir) {
                            strafeDir = preferSide
                            lastStrafeSwitch = now
                        }
                    }
                    if (distance in 1.8f..3.6f) {
                        if (deltaDist < 0.03f) {
                            if (stagnantSince == 0L) stagnantSince = now
                            else if (now - stagnantSince > 550 && now - lastStrafeSwitch > 300) {
                                strafeDir = -strafeDir
                                lastStrafeSwitch = now
                                stagnantSince = 0L
                            }
                        } else stagnantSince = 0L
                    } else stagnantSince = 0L

                    if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(950, 1200)) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                    }

                    val weight = if (now < cornerBreakUntil) 8 else if (distance < 4f) 7 else 5
                    if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight

                    // quand l’adversaire tient un arc, on autorise un strafe plus "aléatoire"
                    randomStrafe = (distance in 8.0f..15.0f) || (oppHasBow && distance > 8.0f)
                }
            }

            handle(clear, randomStrafe, movePriority)
            prevDistance = distance
        }
    }
}
