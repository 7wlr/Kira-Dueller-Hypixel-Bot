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

    // --- Anti block start / cooldown block épée ---
    private var gameStartAt = 0L
    private var lastSwordBlock = 0L

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

            // --- Block épée utile (loin + immobile + arc), jamais au spawn, durée suffisante ---
            val holdingSword = mc.thePlayer.heldItem != null &&
                mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")
            val oppHasBow = opp.heldItem != null &&
                opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val oppSpeed = kotlin.math.abs(opp.motionX) + kotlin.math.abs(opp.motionZ)
            val farAndStill = (distance > 12.5f && oppSpeed < 0.04)   // seuil relevé
            val sinceStart = now - gameStartAt
            if (holdingSword) {
                val canHoldBlock = oppHasBow && farAndStill && sinceStart > 2000 &&
                    (now - lastSwordBlock) > 900 && !Mouse.isUsingProjectile()
                if (canHoldBlock) {
                    // block assez long pour encaisser le tir
                    Mouse.rClick(RandomUtils.randomIntInRange(650, 820))
                    lastSwordBlock = now
                } else if (Mouse.rClickDown) {
                    // on ne reste jamais bloqué si la condition n’est plus vraie
                    Mouse.rClickUp()
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

                    randomStrafe = distance in 8.0f..15.0f
                }
            }

            handle(clear, randomStrafe, movePriority)
            prevDistance = distance
        }
    }
}
