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
import kotlin.math.abs
import kotlin.math.max

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

    // ——— Tuning ———
    private val jumpDistanceThreshold = 5.0f
    private val noJumpCloseDist = 2.2f
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val openShotMinDist = 9.0f
    private val parryMinDist = 11.0f
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650L
    private val parryHoldMaxMs = 1050L
    private val parryStickMinMs = 1000L
    private val parryStickMaxMs = 1800L
    private val bowCancelApproachDist = 6.0f
    private val singleJumpMinDist = 2.8f

    // ➕ seuil “lent mais pas immobile” pendant le bandage d’arc
    private val bowSlowThreshold = 0.06      // ~ 0.06 bloc/tick (lent)
    private val bowSlowFramesNeeded = 3      // ~150 ms

    // ——— États ———
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var stagnantSince = 0L
    private var cornerBreakUntil = 0L

    private var rodLockUntil = 0L
    private var lastRodUse = 0L
    private var prevDistance = -1f

    private var gameStartAt = 0L
    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L

    private var noJumpUntil = 0L
    private var lastHurtTime = 0

    private var shotsFired = 0
    private val maxArrows = 5

    // Immobilité / lenteur adversaire
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    // Verrous arc / projectile
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L   // posé APRÈS useRod/useBow

    // Parade sticky post-tir
    private var parryFromBow = false
    private var parryExtendedUntil = 0L

    // ——— Lifecycle ———
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

        Mouse.rClickUp()
        gameStartAt = System.currentTimeMillis()
        lastSwordBlock = 0L
        holdBlockUntil = 0L

        noJumpUntil = 0L
        lastHurtTime = 0

        shotsFired = 0
        bowHardLockUntil = 0L
        projectileGraceUntil = 0L
        parryFromBow = false
        parryExtendedUntil = 0L

        stillFrames = 0
        bowSlowFrames = 0
        oppLastX = 0.0
        oppLastZ = 0.0

        // Tir d’ouverture (full charge)
        TimeUtils.setTimeout({
            val opp = opponent()
            if (opp != null && !Mouse.isUsingProjectile()) {
                val d = EntityUtils.getDistanceNoY(mc.thePlayer, opp)
                if (d >= openShotMinDist && shotsFired < maxArrows) {
                    val now = System.currentTimeMillis()
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    useBow(d) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120   // posé APRÈS l’appel
                }
            }
        }, RandomUtils.randomIntInRange(350, 650))
    }

    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    // ——— Combat hooks ———
    private var tapping = false

    override fun onAttack() {
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3f) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) {
                    ChatUtils.info("W-Tap 300")
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout({ tapping = false }, 300)
                }
            }
        } else {
            ChatUtils.info("W-Tap 100")
            Combat.wTap(100)
            tapping = true
            TimeUtils.setTimeout({ tapping = false }, 100)
        }
        if (combo >= 3) Movement.clearLeftRight()
    }

    // ——— Tick ———
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val w = mc.theWorld ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // “Projectile en cours” élargi par la grâce
        val usingProjectile = Mouse.isUsingProjectile() || now < projectileGraceUntil

        // Anti-jump post-hit
        val ht = p.hurtTime
        if (ht > 0 && lastHurtTime == 0) {
            noJumpUntil = now + RandomUtils.randomIntInRange(340, 520)
        }
        lastHurtTime = ht

        // Obstacle proche (jamais collé)
        var needJump = false
        if (distance > noJumpCloseDist) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                needJump = true
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
            }
        }

        // Immobilité + lenteur (arc)
        if (oppLastX == 0.0 && oppLastZ == 0.0) {
            oppLastX = opp.posX; oppLastZ = opp.posZ
        }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        // “lent mais pas immobile” pour l’arc
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0

        oppLastX = opp.posX; oppLastZ = opp.posZ
        val isStill = stillFrames >= stillFramesNeeded

        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowDrawLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")

        // Charge arc : ne pas casser avant l’échéance ; danger proche -> annule
        if (usingProjectile && Mouse.rClickDown) {
            if (distance < bowCancelApproachDist || approaching) {
                Mouse.rClickUp()
                bowHardLockUntil = 0L
                projectileGraceUntil = 0L
            } else if (now >= bowHardLockUntil && bowHardLockUntil != 0L) {
                // full charge atteint : Bow.kt s’occupe de la release
            }
        }

        // Parade épée (inclut “slow-walk bow” et stick post-tir)
        if (holdingSword) {
            if (Mouse.rClickDown) {
                // si la cible bouge faiblement à l’arc, on considère “toujours en bandage”
                val movingHard = (!isStill && !(oppHasBow && bowSlowFrames >= bowSlowFramesNeeded)) || approaching
                val mustKeep = (parryFromBow && now < parryExtendedUntil)
                if ((!mustKeep && (movingHard || now >= holdBlockUntil))) {
                    val stopNow = RandomUtils.randomIntInRange(0, 99) < 80
                    if (stopNow) {
                        Mouse.rClickUp()
                        parryFromBow = false
                        parryExtendedUntil = 0L
                    } else {
                        holdBlockUntil = max(holdBlockUntil, now + RandomUtils.randomIntInRange(120, 300))
                    }
                }
            } else {
                val sinceStart = now - gameStartAt
                val canStartParry =
                    sinceStart > 1500 &&
                    distance >= parryMinDist &&
                    (isStill || bowDrawLikely) &&                 // ← assoupli pour l’arc
                    !usingProjectile &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    (now - lastSwordBlock) > parryCooldownMs

                if (canStartParry) {
                    if (RandomUtils.randomIntInRange(0, 99) < 65) {
                        val dur = RandomUtils.randomIntInRange(parryHoldMinMs.toInt(), parryHoldMaxMs.toInt())
                        holdBlockUntil = now + dur
                        lastSwordBlock = now
                        parryFromBow = oppHasBow || bowDrawLikely
                        parryExtendedUntil = if (parryFromBow)
                            now + RandomUtils.randomIntInRange(parryStickMinMs.toInt(), parryStickMaxMs.toInt())
                        else 0L
                        Mouse.rClick(dur)
                    }
                } else if (Mouse.rClickDown) {
                    Mouse.rClickUp()
                    parryFromBow = false
                    parryExtendedUntil = 0L
                }
            }
        } else {
            if (Mouse.rClickDown) {
                Mouse.rClickUp()
            }
            parryFromBow = false
            parryExtendedUntil = 0L
        }

        // Sauts sécurisés
        val canJump = (now >= noJumpUntil) && !Mouse.rClickDown
        if (distance <= noJumpCloseDist) {
            Movement.stopJumping()
        } else {
            if (distance > jumpDistanceThreshold) {
                if (canJump && !needJump) Movement.startJumping() else Movement.stopJumping()
            } else if (!needJump) {
                Movement.stopJumping()
            }
        }

        // Avance/arrêt
        if (distance < 1f || (distance < 2.7f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Ne re-switch pas épée si projectile en cours (grâce incluse)
        if (!(Mouse.isUsingProjectile() || now < projectileGraceUntil) && now >= rodLockUntil && !Mouse.rClickDown) {
            if (distance < 1.5f && p.heldItem != null && !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
            }
        }

        // Fenêtres rod/bow
        if (!(Mouse.isUsingProjectile() || now < projectileGraceUntil) && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

            val cdOK = (now - lastRodUse) >= if (distance < 5.3f) 650 else 900

            // (A) Anti-bow mid-range
            if (cdOK && oppHasBow && distance in 4.8f..7.2f &&
                !EntityUtils.entityFacingAway(p, opp) && now >= rodLockUntil) {

                val lockMs = if (distance < 6f) RandomUtils.randomIntInRange(300, 360) else RandomUtils.randomIntInRange(340, 420)
                rodLockUntil = now + lockMs
                lastRodUse = now
                useRod()
                projectileGraceUntil = now + lockMs + 80

            // (B) Anti-rod mid classique
            } else if (cdOK &&
                distance in 3.4f..5.2f &&
                !EntityUtils.entityFacingAway(p, opp) &&
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
                projectileGraceUntil = now + lockMs + 80

            // (C) Fenêtre 5.7..6.5
            } else if (cdOK &&
                distance in 5.7f..6.5f &&
                !EntityUtils.entityFacingAway(p, opp) &&
                approaching &&
                combo <= 1 &&
                now >= rodLockUntil) {

                val lockMs = if (distance < 6.1f) RandomUtils.randomIntInRange(320, 380) else RandomUtils.randomIntInRange(360, 440)
                rodLockUntil = now + lockMs
                lastRodUse = now
                useRod()
                projectileGraceUntil = now + lockMs + 80

            // (D) Bow “safe”, full charge
            } else if ((EntityUtils.entityFacingAway(p, opp) && distance in 3.5f..30f) ||
                       (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(p, opp))) {
                if (distance > 10f && shotsFired < maxArrows) {
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    useBow(distance) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                }
            }
        }

        // Anti-corner single jump (pas collé)
        if (combo >= 3 && distance >= singleJumpMinDist && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Anti-corner & Strafe
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val blockAheadClose = WorldUtils.blockInFront(p, 1.2f, 1.0f) != Blocks.air
        val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
        val cornerLikely = (distance < 3.8f && (blockAheadClose || deltaDist < 0.02f))
        if (cornerLikely && now >= cornerBreakUntil) {
            strafeDir = -strafeDir
            cornerBreakUntil = now + RandomUtils.randomIntInRange(280, 420)
            if (p.onGround && distance >= singleJumpMinDist) {
                Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            }
        }

        if (!clear) {
            if (EntityUtils.entityFacingAway(p, opp)) {
                if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
            } else {
                val rotations = EntityUtils.getRotations(opp, p, false)
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

                randomStrafe = (distance in 8.0f..15.0f) || (oppHasBow && distance > 8.0f)
            }
        }

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
