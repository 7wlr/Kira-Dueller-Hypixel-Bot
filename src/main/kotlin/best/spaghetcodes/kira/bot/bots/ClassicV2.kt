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
    private val bowMinRange = 8.0f
    private val bowCancelCloseDist = 8.0f

    private var openVolleyMax = 1
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private val openSpacingMin = 650L
    private val openSpacingMax = 900L
    private val openShotMinDist = 11.0f

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

    private var gameStartAt = 0L
    private val reserveTightMs = 10_000L
    private val earlyReserve = 3
    private val midReserve = 2

    // ---------------------- ROD ----------------------
    private var lastRodUse = 0L
    private var rodCdCloseMsBase = 380L
    private var rodCdFarMsBase = 560L
    private var rodCdBias = 1.0f

    private val rodCloseMin = 2.0f
    private val rodCloseMax = 3.4f
    private val rodMainMin = 3.0f
    private val rodMainMax = 6.4f
    private val rodInterceptMin = 5.8f
    private val rodInterceptMax = 7.0f

    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L
    private var lastOppRodSeenAt = 0L

    // -------------------- PARADE ---------------------
    private val parryMinDist = 10.0f
    private val parryCloseCancelDist = 8.0f
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 750
    private val parryHoldMaxMs = 1100
    private val parryStickMinMs = 950
    private val parryStickMaxMs = 1600

    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L
    private var parryStrafeDir = 1
    private var parryStrafeFlipAt = 0L
    private var lastParryJumpAt = 0L
    private val parryJumpCd = 520L

    // -------------------- MOUVEMENT -------------------
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L
    private var lastGotHitAt = 0L
    private var tapping = false

    private var strafeBiasDir = 0
    private var strafeBiasStickUntil = 0L

    // Early jumping
    private var earlyJumpActive = false
    private var lastEarlyJumpAt = 0L
    private val earlyJumpHardCapMs = 3000L

    // “Human swings”
    private var lastFakeSwingAt = 0L

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

        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        strafeBiasDir = 0
        strafeBiasStickUntil = 0L

        parryFromBow = false
        parryExtendedUntil = 0L
        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        parryStrafeFlipAt = 0L
        lastParryJumpAt = 0L
        lastSwordBlock = 0L
        holdBlockUntil = 0L

        earlyJumpActive = true
        lastEarlyJumpAt = 0L
        lastFakeSwingAt = 0L
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
    private fun adjustedAimDistance(d: Float, isSecondOpeningShot: Boolean = false): Float {
        val base = when {
            d in 15.0f..22.0f -> d * 0.84f
            d in 22.0f..30.0f -> d * 0.83f
            d in 9.0f..15.0f  -> d * 0.90f
            else              -> d
        }
        return if (isSecondOpeningShot && d in 12.0f..26.0f) base * 0.96f else base
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

    private fun castRodNow(@Suppress("UNUSED_PARAMETER") distanceNow: Float) {
        val now = System.currentTimeMillis()

        if (Mouse.rClickDown && projectileKind == KIND_BOW) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        Inventory.setInvItem("rod")

        val clickHold = RandomUtils.randomIntInRange(75, 100)
        val preDelay = if (lastRodUse == 0L) RandomUtils.randomIntInRange(50, 60) else RandomUtils.randomIntInRange(70, 95)

        TimeUtils.setTimeout({
            val held = mc.thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }
            Mouse.rClick(clickHold)
        }, preDelay)

        val retain = if (distanceNow < 3.2f) RandomUtils.randomIntInRange(120, 150) else RandomUtils.randomIntInRange(180, 220)

        val settle = RandomUtils.randomIntInRange(220, 300)
        pendingProjectileUntil = now + 90L
        actionLockUntil = now + settle + 90
        projectileGraceUntil = actionLockUntil

        lastRodAttemptAt = now
        pendingRodCheck = true
        lastOppHurtTime = opponent()?.hurtTime ?: 0

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
        }, retain)

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
                rodCdBias = min(1.6f, rodCdBias * 1.12f)
            }
        }
    }

    private fun opponentLikelyUsingRod(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem
        if (held != null && held.unlocalizedName.lowercase().contains("rod")) return true
        return false
    }

    // ----------------------- TICK ---------------------
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // early jumping
        if (earlyJumpActive) {
            if (shotsFired > 0 || now - gameStartAt > earlyJumpHardCapMs) {
                earlyJumpActive = false
            } else if (!Mouse.rClickDown && p.onGround && now - lastEarlyJumpAt >= RandomUtils.randomIntInRange(260, 360)) {
                Movement.singleJump(RandomUtils.randomIntInRange(140, 210))
                lastEarlyJumpAt = now
            }
        }

        Movement.stopJumping()

        // “human swings” (début, 15..22 blocs)
        if (now - gameStartAt < 4000L &&
            distance in 15.0f..22.0f &&
            !Mouse.rClickDown &&
            now - lastFakeSwingAt >= RandomUtils.randomIntInRange(620, 900)) {
            p.swingItem()
            lastFakeSwingAt = now
        }

        // suivi rod adverse
        if (opponentLikelyUsingRod(opp)) {
            lastOppRodSeenAt = now
        }

        // immobile/slow MAJ
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        if (p.hurtTime > 0) lastGotHitAt = now

        // anti-bloc
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
                lastTacticalJumpAt = now
            }
        }

        // écart soudain -> coller l’avance
        if (prevDistance > 0f && distance - prevDistance > 0.6f) {
            forwardStickUntil = max(forwardStickUntil, now + 200)
        }

        // avance / stop avec “forward stick”
        if (now < forwardStickUntil) {
            Movement.startForward()
        } else {
            if (distance < 0.75f || (distance < 2.4f && combo >= 2 && approaching)) {
                Movement.stopForward()
            } else {
                Movement.startForward()
            }
        }

        // tenir l’épée si très proche
        if (distance < 1.5f && p.heldItem != null && !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
            Inventory.setInvItem("sword")
        }

        val projectileActive =
            now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        // annule l’arc si trop proche
        if (Mouse.rClickDown && projectileKind == KIND_BOW && distance < bowCancelCloseDist) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        // MAJ rod
        updateRodAccuracyHeuristic(now)

        // --------------------- PARADE ---------------------
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val isStill = stillFrames >= stillFramesNeeded
        val bowLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)

        if (holdingSword) {
            // stop parry si trop près
            if (Mouse.rClickDown && distance < parryCloseCancelDist) {
                Mouse.rClickUp()
                parryFromBow = false
                parryExtendedUntil = 0L
            }

            if (!Mouse.rClickDown) {
                val sinceStart = now - gameStartAt
                val canStartParry =
                    sinceStart > 1600 &&
                    distance >= parryMinDist &&
                    bowLikely &&
                    distance >= bowMinRange &&
                    !projectileActive &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    (now - lastSwordBlock) > parryCooldownMs

                if (canStartParry) {
                    val dur = RandomUtils.randomIntInRange(parryHoldMinMs, parryHoldMaxMs)
                    holdBlockUntil = now + dur
                    lastSwordBlock = now
                    parryFromBow = true

                    var extraStick =
                        if (distance > 12f) RandomUtils.randomIntInRange(600, 900)
                        else RandomUtils.randomIntInRange(320, 520)
                    if (distance > 18f) extraStick += RandomUtils.randomIntInRange(250, 350)

                    parryExtendedUntil = now + RandomUtils.randomIntInRange(parryStickMinMs, parryStickMaxMs) + extraStick
                    parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    parryStrafeFlipAt = now + RandomUtils.randomIntInRange(240, 380)
                    Mouse.rClick(dur)
                }
            } else {
                // petits sauts latéraux quand on bloque
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

        val parryActive = Mouse.rClickDown && (now < holdBlockUntil || (parryFromBow && now < parryExtendedUntil))

        // ----------------------- ROD ----------------------------
        if (!projectileActive && !Mouse.rClickDown && !parryActive) {
            val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
            val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
            val cdCloseOK = (now - lastRodUse) >= cdClose
            val cdFarOK = (now - lastRodUse) >= cdFar

            val facingAway = EntityUtils.entityFacingAway(p, opp) // pas de rod si l’ennemi fuit
            val oppRodRecently = (now - lastOppRodSeenAt) <= 2000L
            val meleeRange = distance < 3.0f
            val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

            if (!facingAway && allowRodByMeleePolicy) {
                // ultra proche : seulement pour casser un combo
                if (distance < 2.2f && p.hurtTime > 0 && cdCloseOK) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }

                // close / main
                if (distance in rodCloseMin..rodCloseMax && (p.hurtTime > 0 || approaching) && cdCloseOK) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }

                if (distance in rodMainMin..rodMainMax && cdFarOK && oppRodRecently) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }

                // interception si approche nette ET pas de strafes abusifs
                val lateral = (dx + dz)
                if (distance in rodInterceptMin..rodInterceptMax && approaching && lateral < 0.22 && cdFarOK) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }
            }
        }

        // ------------------------ ARC --------------------------
        if (!projectileActive && !Mouse.rClickDown && !parryActive) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            // Ouverture (1–2 tirs), jamais si < bowMinRange
            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= max(openShotMinDist, bowMinRange) &&
                left > reserve &&
                (now - lastShotAt) >= RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt())) {

                val isSecondOpen = openVolleyFired >= 1
                val tunedD = adjustedAimDistance(distance, isSecondOpen)
                val lock = chargeMsFor(distance, opening = true)

                earlyJumpActive = false

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

            // Tir réactif : vrai arc en main + distance OK
            val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val bowLikelyNow =
                oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)

            if (shotsFired < maxArrows &&
                bowLikelyNow &&
                distance >= bowMinRange &&
                now - lastReactiveShotAt >= reactiveCdMs &&
                WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                left > reserve) {

                earlyJumpActive = false

                val tunedD = adjustedAimDistance(distance, false)
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

            // Opportuniste long range
            if (shotsFired < maxArrows && left > reserve && distance >= bowMinRange) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) ||
                    (!away && distance in 28.0f..33.0f)) {

                    earlyJumpActive = false

                    val tunedD = adjustedAimDistance(distance, false)
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

        // ---------------------- STRAFE / HANDLE ----------------
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        if (now < strafeBiasStickUntil && strafeBiasDir != 0) {
            val w = if (distance > 6f) 6 else 7
            if (strafeBiasDir < 0) movePriority[0] += w else movePriority[1] += w
        }

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else {
            val rotations = EntityUtils.getRotations(p, opp, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
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
