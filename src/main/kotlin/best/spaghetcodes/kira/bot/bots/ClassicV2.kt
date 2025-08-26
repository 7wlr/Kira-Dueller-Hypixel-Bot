package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Bow
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Rod
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ClassicV2 : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String = "ClassicV2"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.classic_duel_wins",
                "losses" to "player.stats.Duels.classic_duel_losses",
                "ws" to "player.stats.Duels.current_classic_winstreak",
            )
        )
    }

    // ---------------------- ARC ----------------------
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val bowCancelCloseDist = 6.8f       // annule tout tir en cours si < 6.8
    private val minBowDist = 7.2f               // n’initie jamais un tir en-dessous

    // Ouverture contrôlée
    private var openVolleyMax = 1
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private val openSpacingMin = 650L
    private val openSpacingMax = 900L
    private val openShotMinDist = 9.0f

    // Détection “immobile/slow-bow”
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    private var shotsFired = 0
    private val maxArrows = 5
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

    private var lastReactiveShotAt = 0L
    private val reactiveCdMs = 650L

    // Réserve d’arrows
    private var gameStartAt = 0L
    private val reserveTightMs = 10_000L
    private val earlyReserve = 3
    private val midReserve = 2

    // ---------------------- ROD ----------------------
    private var lastRodUse = 0L
    private var rodCdCloseMsBase = 320L
    private var rodCdFarMsBase = 440L
    private var rodCdBias = 1.0f

    private val rodCloseMin = 2.0f
    private val rodCloseMax = 3.6f
    private val rodMainMin = 3.2f
    private val rodMainMax = 6.9f
    private val rodInterceptMin = 5.6f
    private val rodInterceptMax = 7.4f

    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L
    private var lastOppRodSeenAt = 0L

    // -------------------- PARADE ÉPÉE -----------------
    private val parryMinDist = 15.0f
    private val parryCloseCancelDist = 15.0f
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650
    private val parryHoldMaxMs = 980
    private val parryStickMinMs = 900
    private val parryStickMaxMs = 1500

    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L
    private var parryCloseLockUntil = 0L
    private var parryStrafeDir = 1
    private var parryStrafeFlipAt = 0L
    private var lastParryJumpAt = 0L
    private val parryJumpCd = 580L

    // -------------------- MOUVEMENT -------------------
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L
    private var lastGotHitAt = 0L
    private var tapping = false

    private var strafeBiasDir = 0
    private var strafeBiasStickUntil = 0L

    // Suppression courte projectiles en mêlée
    private var projectileSuppressUntil = 0L
    private val meleeGuardDist = 5.9f         // forcer l’épée un poil plus tôt

    // ---------------------- LIFECYCLE -----------------
    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()
        Mouse.rClickUp()

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

        shotsFired = 0
        bowHardLockUntil = 0L
        projectileGraceUntil = 0L
        pendingProjectileUntil = 0L
        actionLockUntil = 0L
        projectileKind = KIND_NONE

        openVolleyMax = RandomUtils.randomIntInRange(1, 2)
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 4500L
        openStartDelayUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(700, 1100)
        lastShotAt = 0L

        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0
        lastOppHurtTime = 0

        lastReactiveShotAt = 0L
        gameStartAt = System.currentTimeMillis()

        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0
        rodMisses = 0
        pendingRodCheck = false
        lastRodAttemptAt = 0L
        lastOppRodSeenAt = 0L

        lastTacticalJumpAt = 0L
        lastGotHitAt = 0L

        lastSwordBlock = 0L
        holdBlockUntil = 0L
        parryFromBow = false
        parryExtendedUntil = 0L
        parryCloseLockUntil = 0L
        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        parryStrafeFlipAt = 0L
        lastParryJumpAt = 0L

        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        strafeBiasDir = 0
        strafeBiasStickUntil = 0L

        projectileSuppressUntil = 0L

        TimeUtils.setTimeout({
            Movement.singleJump(RandomUtils.randomIntInRange(140, 220))
        }, RandomUtils.randomIntInRange(180, 320))
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

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)

        val now = System.currentTimeMillis()
        forwardStickUntil = now + RandomUtils.randomIntInRange(220, 280)
        meleeFocusUntil = now + RandomUtils.randomIntInRange(300, 340)
        TimeUtils.setTimeout({
            Movement.startForward()
            Movement.startSprinting()
        }, 80)

        if (combo >= 3) Movement.clearLeftRight()
    }

    // -------------------- HELPERS ---------------------
    private fun adjustedAimDistance(d: Float): Float {
        return when {
            d in 15.0f..22.0f -> d * 0.88f
            d in 22.0f..30.0f -> d * 0.86f
            d in 9.0f..15.0f  -> d * 0.92f
            else              -> d
        }
    }

    private fun chargeMsFor(distance: Float, opening: Boolean): Long {
        return if (opening) {
            RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
        } else {
            when {
                distance < 6.0f   -> RandomUtils.randomIntInRange(220, 320).toLong()
                distance < 10.0f  -> RandomUtils.randomIntInRange(320, 450).toLong()
                distance < 15.0f  -> RandomUtils.randomIntInRange(450, 650).toLong()
                distance < 25.0f  -> RandomUtils.randomIntInRange(550, 800).toLong()
                else              -> RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
            }
        }
    }

    private fun arrowsLeft(): Int = maxArrows - shotsFired
    private fun reserveNeeded(now: Long): Int =
        if (now - gameStartAt < reserveTightMs) earlyReserve else midReserve

    private fun cancelProjectiles() {
        if (Mouse.rClickDown) Mouse.rClickUp()
        bowHardLockUntil = 0L
        projectileGraceUntil = 0L
        pendingProjectileUntil = 0L
        actionLockUntil = 0L
        projectileKind = KIND_NONE
        Mouse.setUsingProjectile(false)
    }

    private fun yawError(): Float {
        val p = mc.thePlayer ?: return 180f
        val o = opponent() ?: return 180f
        val r = EntityUtils.getRotations(o, p, false) ?: return 180f
        return abs(r[0])
    }

    private fun hasClearLineOfSight(playerDist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        return WorldUtils.blockInFront(p, playerDist, 0.5f) == Blocks.air
    }

    private fun castRodNow(@Suppress("UNUSED_PARAMETER") distanceNow: Float) {
        val now = System.currentTimeMillis()
        if (Mouse.rClickDown && projectileKind == KIND_BOW) cancelProjectiles()

        Inventory.setInvItem("rod")
        Mouse.setUsingProjectile(true)
        Mouse.rClick(RandomUtils.randomIntInRange(45, 65)) // switch -> click immédiat

        val settle = RandomUtils.randomIntInRange(200, 240)
        pendingProjectileUntil = now + 60L
        actionLockUntil = now + settle + 60
        projectileGraceUntil = actionLockUntil

        lastRodAttemptAt = now
        pendingRodCheck = true
        lastOppHurtTime = opponent()?.hurtTime ?: 0

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            Mouse.setUsingProjectile(false)
        }, settle)

        lastRodUse = now
    }

    private fun updateRodAccuracyHeuristic(now: Long) {
        if (!pendingRodCheck) return
        val opp = opponent() ?: return
        val dt = now - lastRodAttemptAt
        if (dt in 80..420) {
            val ht = opp.hurtTime
            if (ht > 0 && (ht != lastOppHurtTime)) {
                rodHits++
                pendingRodCheck = false
                rodCdBias = max(0.85f, rodCdBias * 0.95f)
            }
        } else if (dt > 480) {
            rodMisses++
            pendingRodCheck = false
            if (rodMisses - rodHits >= 2) {
                rodCdBias = min(1.6f, rodCdBias * 1.10f)
            }
        }
    }

    private fun opponentLikelyUsingRod(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem
        return held != null && held.unlocalizedName.lowercase().contains("rod")
    }

    // ----------------------- TICK ---------------------
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.stopJumping()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        if (opponentLikelyUsingRod(opp)) lastOppRodSeenAt = now

        // immobile/slow + vitesse latérale (strafe)
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        val isStrafing = frameSpeed > 0.18
        oppLastX = opp.posX; oppLastZ = opp.posZ

        if (p.hurtTime > 0) lastGotHitAt = now

        // Anti-bloc devant
        if (distance > 2.0f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
                lastTacticalJumpAt = now
            }
        }

        // Stick avant court en cas de lag/kb
        if (prevDistance > 0f && distance - prevDistance > 0.6f) {
            forwardStickUntil = max(forwardStickUntil, now + 200)
        }

        // Avance / arrêt, avec “forward stick”
        if (now < forwardStickUntil) {
            Movement.startForward()
        } else {
            if (distance < 0.75f || (distance < 2.4f && combo >= 2 && approaching)) {
                Movement.stopForward()
            } else {
                Movement.startForward()
            }
        }

        // Forcer l'épée si proche / anti-arc en mêlée
        val heldName = p.heldItem?.unlocalizedName?.lowercase() ?: ""
        val holdingBow = heldName.contains("bow")
        val holdingRod = heldName.contains("rod")

        if (distance < meleeGuardDist) {
            // maintien épée systématique en mêlée
            if (!heldName.contains("sword")) {
                cancelProjectiles()
                Inventory.setInvItem("sword")
            }
            projectileSuppressUntil = now + 320L
        }
        // arc cancel durci
        if (holdingBow && distance < bowCancelCloseDist) {
            cancelProjectiles()
            Inventory.setInvItem("sword")
            projectileSuppressUntil = now + 350L
        }

        // Toujours épée <1.5
        if (distance < 1.5f && !heldName.contains("sword")) {
            Inventory.setInvItem("sword")
        }

        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        updateRodAccuracyHeuristic(now)

        // --------------------- PARADE ÉPÉE ---------------------
        val holdingSword = (p.heldItem?.unlocalizedName?.lowercase()?.contains("sword") == true)
        val isStill = stillFrames >= stillFramesNeeded
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)

        if (Mouse.rClickDown && distance < parryCloseCancelDist) {
            Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
            parryCloseLockUntil = now + 700L
        }

        if (holdingSword) {
            if (!Mouse.rClickDown) {
                val sinceStart = now - gameStartAt
                val closeRange = distance < parryCloseCancelDist

                if (!closeRange && now >= parryCloseLockUntil) {
                    val canStartParry =
                        sinceStart > 1600 &&
                        distance >= parryMinDist &&
                        (isStill || bowLikely) &&
                        !projectileActive &&
                        hasClearLineOfSight(distance) &&
                        (now - lastSwordBlock) > parryCooldownMs

                    if (canStartParry) {
                        val dur = RandomUtils.randomIntInRange(parryHoldMinMs, parryHoldMaxMs)
                        holdBlockUntil = now + dur
                        lastSwordBlock = now
                        parryFromBow = true

                        val extraStick =
                            if (distance > 15f) RandomUtils.randomIntInRange(900, 1200)
                            else RandomUtils.randomIntInRange(500, 800)

                        parryExtendedUntil =
                            now + RandomUtils.randomIntInRange(parryStickMinMs, parryStickMaxMs) + extraStick
                        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                        parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                        Mouse.rClick(dur)
                    }
                }
            } else {
                if (distance >= parryCloseCancelDist &&
                    p.onGround &&
                    now - lastParryJumpAt >= parryJumpCd &&
                    !projectileActive &&
                    now - lastGotHitAt > 260) {

                    if (RandomUtils.randomIntInRange(0, 1) == 1 || now >= parryStrafeFlipAt) {
                        parryStrafeDir = -parryStrafeDir
                        parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                    }
                    Movement.singleJump(RandomUtils.randomIntInRange(140, 210))
                    lastParryJumpAt = now
                    Movement.startForward()
                    Movement.startSprinting()
                }

                val mustKeep = parryFromBow && now < parryExtendedUntil
                if (!mustKeep && now >= holdBlockUntil) {
                    Mouse.rClickUp()
                    parryFromBow = false
                    parryExtendedUntil = 0L
                }
            }
        } else {
            if (Mouse.rClickDown && !projectileActive) Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
        }

        // --------- JUMPS CONTEXTUELS ----------
        if (!Mouse.rClickDown && !projectileActive && (now - lastGotHitAt) > 260) {
            val facingAway = EntityUtils.entityFacingAway(p, opp)
            val oppVeryStill = (stillFrames >= 6)
            if (distance > 8.0f) {
                if (p.onGround && now - lastTacticalJumpAt >= 520) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastTacticalJumpAt = now
                }
            } else if (distance in 4.5f..8.0f && (facingAway || oppVeryStill)) {
                if (p.onGround && now - lastTacticalJumpAt >= 720) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastTacticalJumpAt = now
                }
            }
        }

        // ----------------------- ROD ----------------------------
        // -> Rod prioritaire/fiable, même si l’adversaire strafe ; sans exigence d’alignement stricte.
        if (!Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
            val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
            val cdCloseOK = (now - lastRodUse) >= cdClose
            val cdFarOK = (now - lastRodUse) >= cdFar
            val facingAway = EntityUtils.entityFacingAway(p, opp)
            val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
            val los = hasClearLineOfSight(distance)

            // Autoriser la rod même si un lock projectile est actif en cas de très courte portée
            val busy = Mouse.isUsingProjectile() || now < actionLockUntil
            val busyButClose = busy && distance < 3.4f

            // Ne pas “rod” en vrai corps-à-corps si on a déjà le combo
            val tooCloseMelee = (distance < 2.0f && combo >= 1)

            if (los && !facingAway && !tooCloseMelee) {
                // Anti-combo très proche
                if ((distance < 2.2f && (p.hurtTime > 0 || now - lastGotHitAt < 220)) && cdCloseOK) {
                    castRodNow(distance); prevDistance = distance; return
                }
                // Fenêtre close
                if (distance in rodCloseMin..rodCloseMax && (p.hurtTime > 0 || approaching) && (cdCloseOK || busyButClose)) {
                    castRodNow(distance); prevDistance = distance; return
                }
                // Fenêtre principale (permissive)
                if (distance in rodMainMin..rodMainMax && (cdFarOK || busyButClose)) {
                    castRodNow(distance); prevDistance = distance; return
                }
                // Réponse à rod adverse
                if (oppRodRecently && distance in rodMainMin..rodInterceptMax && (cdFarOK || busyButClose)) {
                    castRodNow(distance); prevDistance = distance; return
                }
                // Interception d’approche
                if (distance in rodInterceptMin..rodInterceptMax && approaching && (cdFarOK || busyButClose)) {
                    castRodNow(distance); prevDistance = distance; return
                }
            }
        }

        // ------------------------ ARC --------------------------
        if (!Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown && now >= projectileSuppressUntil) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            // Interdire toute init en dessous du minBowDist
            if (distance >= minBowDist) {
                // Ouverture
                if (shotsFired < maxArrows &&
                    openVolleyFired < openVolleyMax &&
                    now < openWindowUntil &&
                    now >= openStartDelayUntil &&
                    distance >= openShotMinDist &&
                    left > reserve &&
                    (now - lastShotAt) >= RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt())) {

                    val tunedD = adjustedAimDistance(distance)
                    val lock = chargeMsFor(distance, opening = true)
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (lock + 120)
                    projectileKind = KIND_BOW
                    useBow(tunedD) {
                        shotsFired++
                        openVolleyFired++
                        lastShotAt = System.currentTimeMillis()
                    }
                    projectileGraceUntil = bowHardLockUntil + 120
                    prevDistance = distance
                    return
                }

                // Tir réactif “slow bow”
                val oppHasBowNow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
                val bowLikelyNow = oppHasBowNow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
                if (shotsFired < maxArrows &&
                    bowLikelyNow &&
                    now - lastReactiveShotAt >= reactiveCdMs &&
                    hasClearLineOfSight(distance) &&
                    left > reserve) {

                    val tunedD = adjustedAimDistance(distance)
                    val lock = chargeMsFor(distance, opening = false)
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 50L
                    actionLockUntil = now + (lock + 100)
                    projectileKind = KIND_BOW
                    useBow(tunedD) {
                        shotsFired++
                        lastReactiveShotAt = System.currentTimeMillis()
                    }
                    projectileGraceUntil = bowHardLockUntil + 100
                    prevDistance = distance
                    return
                }

                // Opportuniste (loin / dos)
                if (shotsFired < maxArrows && left > reserve) {
                    val away = EntityUtils.entityFacingAway(p, opp)
                    if ((away && distance in 3.5f..30.0f) ||
                        (!away && distance in 28.0f..33.0f)) {

                        val tunedD = adjustedAimDistance(distance)
                        val lock = chargeMsFor(distance, opening = false)
                        bowHardLockUntil = now + lock
                        pendingProjectileUntil = now + 60L
                        actionLockUntil = now + (lock + 120)
                        projectileKind = KIND_BOW
                        useBow(tunedD) { shotsFired++ }
                        projectileGraceUntil = bowHardLockUntil + 120
                        prevDistance = distance
                        return
                    }
                }
            }
        }

        // Annulation immédiate si la distance retombe en mêlée pendant un tir
        if (Mouse.rClickDown && projectileKind == KIND_BOW && distance < bowCancelCloseDist) {
            cancelProjectiles()
            Inventory.setInvItem("sword")
            projectileSuppressUntil = now + 350L
        }

        // ---------------------- STRAFE / HANDLE ----------------
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val parryActive = Mouse.rClickDown && (now < holdBlockUntil || (parryFromBow && now < parryExtendedUntil))
        if (parryActive) {
            val w = if (distance > 6f) 7 else 9
            if (parryStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        }

        if (!parryActive && now < strafeBiasStickUntil && strafeBiasDir != 0) {
            val w = if (distance > 6f) 6 else 7
            if (strafeBiasDir < 0) movePriority[0] += w else movePriority[1] += w
        }

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else if (!parryActive) {
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
            }
            if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }
            val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
            if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }
            val weight = if (distance < 4f) 7 else 5
            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
            randomStrafe = (distance in 8.0f..15.0f)
        }

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
