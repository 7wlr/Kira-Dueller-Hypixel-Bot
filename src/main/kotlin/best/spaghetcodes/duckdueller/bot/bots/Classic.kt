package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.features.Bow
import best.spaghetcodes.duckdueller.bot.features.MovePriority
import best.spaghetcodes.duckdueller.bot.features.Rod
import best.spaghetcodes.duckdueller.bot.player.Combat
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.bot.player.Movement
import best.spaghetcodes.duckdueller.utils.*
import best.spaghetcodes.duckdueller.utils.ChatUtils
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

    // Tuning
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
    private val bowCancelCloseDist = 4.8f
    private val singleJumpMinDist = 2.8f
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private val earlyJumpDurationMs = 3000L

    // États
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

    // suivi immobilité / “slow walk bow”
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    // verrous projectiles
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L

    // type d’action en cours
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_ROD = 1
    private val KIND_BOW = 2

    // parade sticky contre l’arc
    private var parryFromBow = false
    private var parryExtendedUntil = 0L

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.startJumping() // jump continu au départ

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
        pendingProjectileUntil = 0L
        actionLockUntil = 0L
        projectileKind = KIND_NONE
        parryFromBow = false
        parryExtendedUntil = 0L
        stillFrames = 0
        bowSlowFrames = 0
        oppLastX = 0.0
        oppLastZ = 0.0

        // Tir d’ouverture (full draw)
        TimeUtils.setTimeout({
            val opp = opponent()
            if (opp != null && !Mouse.isUsingProjectile()) {
                val d = EntityUtils.getDistanceNoY(mc.thePlayer, opp)
                if (d >= openShotMinDist && shotsFired < maxArrows) {
                    val now = System.currentTimeMillis()
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (fullDrawMsMax + 200)
                    projectileKind = KIND_BOW
                    useBow(d) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
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

    // Lancer rod “confirmé” : clic immédiat si la rod est déjà en main,
    // sinon on décale le clic d’UN seul tick (≈50 ms) pour éviter que le client mange l’input
    private fun castRodConfirmed() {
        val now = System.currentTimeMillis()
        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 380)

        projectileKind = KIND_ROD
        pendingProjectileUntil = now + 60L
        actionLockUntil = now + clickMs + settleAfter + 120
        projectileGraceUntil = actionLockUntil

        Inventory.setInvItem("rod")

        val heldNow = mc.thePlayer?.heldItem
        val haveRod = heldNow != null && heldNow.unlocalizedName.lowercase().contains("rod")
        val delay = if (haveRod) 0 else 50 // 1 tick seulement si nécessaire

        if (delay == 0) {
            Mouse.rClick(clickMs)
        } else {
            actionLockUntil += 50
            projectileGraceUntil += 50
            TimeUtils.setTimeout({ Mouse.rClick(clickMs) }, 50)
        }

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            Mouse.setUsingProjectile(false)
            projectileKind = KIND_NONE
        }, delay + clickMs + settleAfter)
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        val ht = p.hurtTime
        if (ht > 0 && lastHurtTime == 0) {
            noJumpUntil = now + RandomUtils.randomIntInRange(340, 520)
        }
        lastHurtTime = ht

        if (now - gameStartAt < earlyJumpDurationMs) {
            Movement.startJumping()
        } else {
            var needJump = false
            if (distance > noJumpCloseDist) {
                if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                    needJump = true
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
                }
            }
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
        }

        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        val isStill = stillFrames >= stillFramesNeeded
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowDrawLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")

        // n’annule l’arc qu’en contact
        if (projectileActive && Mouse.rClickDown) {
            if (distance < bowCancelCloseDist) {
                Mouse.rClickUp()
                bowHardLockUntil = 0L
                projectileGraceUntil = 0L
                pendingProjectileUntil = 0L
                actionLockUntil = 0L
                projectileKind = KIND_NONE
            }
        }

        // Parade épée (évite les parades “gratuites” au tout début si l’adversaire est juste immobile et très loin)
        if (holdingSword) {
            if (Mouse.rClickDown) {
                val movingHard = (!isStill && !(oppHasBow && bowSlowFrames >= bowSlowFramesNeeded)) || approaching
                val mustKeep = (parryFromBow && now < parryExtendedUntil)
                if (!mustKeep && (movingHard || now >= holdBlockUntil)) {
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
                val stillButFar = isStill && distance > 16f
                val canStartParry =
                    sinceStart > 2000 &&
                    distance >= parryMinDist &&
                    ((isStill && distance <= 16f) || bowDrawLikely) &&
                    !projectileActive &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    (now - lastSwordBlock) > parryCooldownMs

                if (canStartParry && !stillButFar) {
                    if (RandomUtils.randomIntInRange(0, 99) < 65) {
                        val dur = RandomUtils.randomIntInRange(parryHoldMinMs.toInt(), parryHoldMaxMs.toInt())
                        holdBlockUntil = now + dur
                        lastSwordBlock = now
                        parryFromBow = oppHasBow || bowDrawLikely
                        val baseStick = RandomUtils.randomIntInRange(parryStickMinMs.toInt(), parryStickMaxMs.toInt())
                        val extraStick =
                            if (distance > 5f) RandomUtils.randomIntInRange(600, 900)
                            else if (distance > 4f) RandomUtils.randomIntInRange(320, 520)
                            else 0
                        parryExtendedUntil = if (parryFromBow) now + baseStick + extraStick else 0L
                        Mouse.rClick(dur)
                    }
                } else if (Mouse.rClickDown) {
                    Mouse.rClickUp()
                    parryFromBow = false
                    parryExtendedUntil = 0L
                }
            }
        } else {
            if (Mouse.rClickDown && !projectileActive) {
                Mouse.rClickUp()
            }
            parryFromBow = false
            parryExtendedUntil = 0L
        }

        // Avance / arrêt
        if (distance < 1f || (distance < 2.7f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Pas de reswitch épée si projectile actif/pending/lock
        if (!projectileActive && now >= rodLockUntil && !Mouse.rClickDown && now >= projectileGraceUntil) {
            if (distance < 1.5f && p.heldItem != null && !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
            }
        }

        // Fenêtres rod/bow
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

            val cdOK = (now - lastRodUse) >= if (distance < 5.3f) 650 else 900

            // (A) Anti-bow mid-range
            if (cdOK && oppHasBow && distance in 4.8f..7.2f &&
                !EntityUtils.entityFacingAway(p, opp) && now >= rodLockUntil) {

                val lockMs = if (distance < 6f) RandomUtils.randomIntInRange(300, 360) else RandomUtils.randomIntInRange(340, 420)
                rodLockUntil = now + lockMs
                lastRodUse = now
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + lockMs + 100
                castRodConfirmed()
                return
            }

            // (B) Anti-rod (inclut courte distance : seuil abaissé à 2.4f)
            if (cdOK &&
                distance in 2.4f..5.2f &&
                !EntityUtils.entityFacingAway(p, opp) &&
                (approaching || distance <= 3.5f) &&
                combo <= 1 &&
                now >= rodLockUntil) {

                val lockMs = when {
                    distance < 3.0f -> RandomUtils.randomIntInRange(220, 280)
                    distance < 3.6f -> RandomUtils.randomIntInRange(240, 300)
                    distance < 4.6f -> RandomUtils.randomIntInRange(300, 360)
                    else            -> RandomUtils.randomIntInRange(340, 420)
                }
                rodLockUntil = now + lockMs
                lastRodUse = now
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + lockMs + 100
                castRodConfirmed()
                return
            }

            // (C) Fenêtre 5.7..6.5
            if (cdOK &&
                distance in 5.7f..6.5f &&
                !EntityUtils.entityFacingAway(p, opp) &&
                approaching &&
                combo <= 1 &&
                now >= rodLockUntil) {

                val lockMs = if (distance < 6.1f) RandomUtils.randomIntInRange(320, 380) else RandomUtils.randomIntInRange(360, 440)
                rodLockUntil = now + lockMs
                lastRodUse = now
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + lockMs + 100
                castRodConfirmed()
                return
            }

            // (D) Bow “safe”, full charge
            if ((EntityUtils.entityFacingAway(p, opp) && distance in 3.5f..30f) ||
                (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(p, opp))) {
                if (distance > 10f && shotsFired < maxArrows) {
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (fullDrawMsMax + 100)
                    projectileKind = KIND_BOW
                    useBow(distance) { shotsFired++ } // ← FIX: param correct
                    projectileGraceUntil = bowHardLockUntil + 120
                    return
                }
            }
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
