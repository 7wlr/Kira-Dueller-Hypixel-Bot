package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.*
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OP : BotBase("/play duels_op_duel"), Bow, Rod, MovePriority, Potion, Gap {

    override fun getName(): String = "OP"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.op_duel_wins",
                "losses" to "player.stats.Duels.op_duel_losses",
                "ws" to "player.stats.Duels.current_op_winstreak",
            )
        )
    }

    // =====================  CONFIG GÉNÉRALE  =====================
    var shotsFired = 0
    var maxArrows = 20

    var speedDamage = 16386
    var regenDamage = 16385

    var speedPotsLeft = 2
    var regenPotsLeft = 2
    var gapsLeft = 6

    var lastSpeedUse = 0L
    var lastRegenUse = 0L
    override var lastPotion = 0L
    override var lastGap = 0L

    private var gameStartAt = 0L
    private var openingPhaseUntil = 0L
    private var openingDone = false

    private var retreating = false
    private var eatingGap = false
    private var firstSpeedTaken = false
    private var allowStrafing = false

    // État potions
    private var takingPotion = false

    var tapping = false

    // =====================  STRAFE  =====================
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var lastCloseStrafeSwitch = 0L
    private var closeStrafeNextAt = 0L
    private var longStrafeUntil = 0L
    private var longStrafeChance = 25

    // Verrou d'aim court
    private var aimFreezeUntil = 0L

    private fun computeCloseStrafeDelay(distance: Float): Long = when {
        distance < 2.0f -> RandomUtils.randomIntInRange(90, 160).toLong()
        distance < 2.8f -> RandomUtils.randomIntInRange(180, 250).toLong()
        else -> RandomUtils.randomIntInRange(220, 300).toLong()
    }

    private fun shouldStartLongStrafe(distance: Float, nowMs: Long): Boolean {
        if (longStrafeUntil > nowMs) return false
        if (distance > 3.8f) return false

        val chance = when {
            distance < 2.5f -> longStrafeChance + 15
            distance < 3.2f -> longStrafeChance + 5
            else -> longStrafeChance
        }
        return RandomUtils.randomIntInRange(1, 100) <= chance
    }

    // =====================  LOGIQUE ROD (ClassicV2)  =====================
    private var lastRodUse = 0L
    private var rodCdCloseMsBase = 340L
    private var rodCdFarMsBase = 480L
    private var rodCdBias = 1.0f
    private val rodCdBiasMax = 1.25f

    private val rodBanMeleeDist = 4.0f

    private val rodCloseMin = 2.0f
    private val rodCloseMax = 3.4f
    private val rodMainMin = 3.0f
    private val rodMainMax = 6.8f
    private val rodInterceptMin = 5.8f
    private val rodInterceptMax = 7.2f
    private val rodMaxRangeHard = 7.2f

    private val rodMidInstantMin = 5.5f
    private val rodMidInstantMax = 7.0f

    private var rodAntiSpamUntil = 0L

    private var farSince = 0L
    private val farThreshold = 11.0f
    private var reentryRodGraceUntil = 0L

    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L

    private var lastOppRodSeenAt = 0L
    private var rodHoldUntil = 0L

    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var stillFrames = 0
    private var bowSlowFrames = 0

    private var prevDistance = -1f

    private fun opponentLikelyUsingRod(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem
        return held != null && held.unlocalizedName.lowercase().contains("rod")
    }

    private fun updateRodAccuracyHeuristic(now: Long) {
        if (!pendingRodCheck) return
        val opp = opponent() ?: return
        val dt = now - lastRodAttemptAt
        if (dt in 80..420) {
            val ht = opp.hurtTime
            if (ht > 0 && ht != lastOppHurtTime) {
                rodHits++
                pendingRodCheck = false
                rodCdBias = max(0.85f, rodCdBias * 0.92f)
            }
        } else if (dt > 480) {
            rodMisses++
            pendingRodCheck = false
            if (rodMisses - rodHits >= 2) rodCdBias = min(rodCdBiasMax, rodCdBias * 1.10f)
        }
    }

    private fun castRodNow(distanceNow: Float) {
        val nowClick = System.currentTimeMillis()
        Mouse.setUsingProjectile(true)
        if (Mouse.rClickDown) Mouse.rClickUp()
        Inventory.setInvItem("rod")
        Mouse.rClick(RandomUtils.randomIntInRange(70, 95))
        reentryRodGraceUntil = 0L

        val holdMs = when {
            distanceNow < 3.0f -> RandomUtils.randomIntInRange(118, 142)
            distanceNow < 4.8f -> RandomUtils.randomIntInRange(160, 190)
            distanceNow <= 6.2f -> RandomUtils.randomIntInRange(208, 232)
            else -> RandomUtils.randomIntInRange(210, 235)
        }
        rodHoldUntil = nowClick + holdMs

        lastRodAttemptAt = nowClick
        pendingRodCheck = true
        lastOppHurtTime = opponent()?.hurtTime ?: 0

        val settle = RandomUtils.randomIntInRange(200, 260)
        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            Mouse.setUsingProjectile(false)
        }, max(holdMs + 20, settle))

        lastRodUse = nowClick

        val oppPassive = (nowClick - lastOppRodSeenAt) > 5000L
        val antiSpam = when {
            distanceNow < 3.0f -> if (oppPassive) RandomUtils.randomIntInRange(340, 420) else RandomUtils.randomIntInRange(260, 320)
            distanceNow <= 6.2f -> if (oppPassive) RandomUtils.randomIntInRange(520, 680) else RandomUtils.randomIntInRange(380, 520)
            else -> if (oppPassive) RandomUtils.randomIntInRange(520, 700) else RandomUtils.randomIntInRange(400, 560)
        }
        rodAntiSpamUntil = nowClick + antiSpam

        meleeFocusUntil = max(meleeFocusUntil, nowClick + RandomUtils.randomIntInRange(240, 360))
    }

    // =====================  AIDES FUITE & CAST =====================
    private fun faceAwayAndLock(lockMs: Int = 900, pitch: Float? = null) {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return
        val dx = p.posX - opp.posX
        val dz = p.posZ - opp.posZ
        if (dx == 0.0 && dz == 0.0) return
        val yaw = (Math.toDegrees(kotlin.math.atan2(dz, dx)) - 90.0).toFloat()
        p.rotationYaw = yaw
        p.rotationYawHead = yaw
        p.renderYawOffset = yaw
        if (pitch != null) p.rotationPitch = pitch
        aimFreezeUntil = System.currentTimeMillis() + lockMs
    }

    // Pitch « en face » (0°) ou légèrement vers le haut (négatif en MC)
    private fun pickForwardOrSlightUpPitch(): Float {
        return if (RandomUtils.randomIntInRange(0, 1) == 0) 0f else -RandomUtils.randomIntInRange(6, 12).toFloat()
    }

    // Verrouille seulement le pitch pendant un court instant
    private fun lockPitch(pitch: Float, lockMs: Int) {
        val p = mc.thePlayer ?: return
        p.rotationPitch = pitch
        aimFreezeUntil = System.currentTimeMillis() + lockMs
        Mouse.stopTracking()
    }

    private fun startOppositeRun() {
        Mouse.setRunningAway(true)
        Movement.startForward()
        Movement.startSprinting()
        Movement.clearLeftRight()
    }

    private fun stopOppositeRun() {
        Movement.stopForward()
        Movement.stopSprinting()
        Mouse.setRunningAway(false)
    }

    // ---- OUVERTURE : sprint + jump en face, SANS retrait ----
    private fun castOpeningPotionInPlace(damage: Int, onComplete: (() -> Unit)? = null) {
        if (takingPotion) return
        takingPotion = true

        // courir + sauter tout droit, regard en face / légèrement vers le haut
        Movement.startForward(); Movement.startSprinting(); Movement.startJumping()
        val pitch = pickForwardOrSlightUpPitch()
        lockPitch(pitch, RandomUtils.randomIntInRange(240, 320))

        TimeUtils.setTimeout({
            useSplashPotion(damage, false, false)
            lastPotion = System.currentTimeMillis()

            // continuer le saut pour traverser le nuage
            TimeUtils.setTimeout({
                Movement.stopJumping()
                takingPotion = false
                onComplete?.invoke()
            }, RandomUtils.randomIntInRange(220, 320))
        }, RandomUtils.randomIntInRange(90, 140))
    }

    // ---- COMBAT : fuite opposée + sprint-jump + même pitch ----
    private fun retreatAndSplash(damage: Int, onComplete: () -> Unit) {
        if (takingPotion) return
        retreating = true
        takingPotion = true
        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(false)

        val pitch = pickForwardOrSlightUpPitch()
        faceAwayAndLock(lockMs = RandomUtils.randomIntInRange(700, 900), pitch = pitch)
        Mouse.stopTracking()

        startOppositeRun(); Movement.startJumping()

        TimeUtils.setTimeout({
            lockPitch(pitch, RandomUtils.randomIntInRange(260, 360))
            useSplashPotion(damage, false, false)
            lastPotion = System.currentTimeMillis()
            onComplete()

            TimeUtils.setTimeout({
                Movement.stopJumping()
                stopOppositeRun()
                retreating = false
                takingPotion = false
            }, RandomUtils.randomIntInRange(850, 1150))
        }, RandomUtils.randomIntInRange(160, 240))
    }

    // ---- GAP (fuite courte si proche), sans annuler la mastication ----
    private fun eatGoldenApple(distance: Float, close: Boolean, facingAway: Boolean) {
        val now = System.currentTimeMillis()
        if (eatingGap || now < lastGap + 3500) return

        eatingGap = true
        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(false)

        if (distance < 5.5f) {
            val pitch = pickForwardOrSlightUpPitch()
            faceAwayAndLock(lockMs = RandomUtils.randomIntInRange(500, 700), pitch = pitch)
            Mouse.stopTracking()
            startOppositeRun(); Movement.startJumping()
        }

        useGap(distance, close, facingAway)
        lastGap = now

        TimeUtils.setTimeout({
            Movement.stopJumping()
            eatingGap = false
            stopOppositeRun()
            if (!Mouse.isUsingProjectile() && !Mouse.isUsingPotion()) {
                Inventory.setInvItem("sword")
            }
        }, RandomUtils.randomIntInRange(3400, 4200))
    }

    // =====================  LIFECYCLE  =====================
    override fun onGameStart() {
        gameStartAt = System.currentTimeMillis()
        openingPhaseUntil = gameStartAt + 5500L
        openingDone = false

        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.clearLeftRight()
        Combat.stopRandomStrafe()
        allowStrafing = false

        takingPotion = false
        aimFreezeUntil = 0L

        // OUVERTURE : speed -> regen, en place (sprint+jump), pas de retrait
        TimeUtils.setTimeout({
            castOpeningPotionInPlace(speedDamage) {
                speedPotsLeft--
                lastSpeedUse = System.currentTimeMillis()
                firstSpeedTaken = true

                TimeUtils.setTimeout({
                    castOpeningPotionInPlace(regenDamage) {
                        regenPotsLeft--
                        lastRegenUse = System.currentTimeMillis()
                        openingDone = true
                    }
                }, RandomUtils.randomIntInRange(900, 1200))
            }
        }, RandomUtils.randomIntInRange(220, 380))

        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(3000, 4000))
        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        lastStrafeSwitch = 0L
        lastCloseStrafeSwitch = 0L
        closeStrafeNextAt = 0L
        longStrafeUntil = 0L

        // Reset rod
        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0
        rodMisses = 0
        pendingRodCheck = false
        lastRodAttemptAt = 0L
        lastOppRodSeenAt = 0L
        rodHoldUntil = 0L
        rodAntiSpamUntil = 0L
        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0
        farSince = 0L
        reentryRodGraceUntil = 0L
        prevDistance = -1f
    }

    override fun onGameEnd() {
        shotsFired = 0
        speedPotsLeft = 2
        regenPotsLeft = 2
        gapsLeft = 6

        lastSpeedUse = 0L
        lastRegenUse = 0L
        lastPotion = 0L
        lastGap = 0L
        gameStartAt = 0L
        openingPhaseUntil = 0L
        openingDone = false
        retreating = false
        eatingGap = false
        firstSpeedTaken = false
        allowStrafing = false

        takingPotion = false
        aimFreezeUntil = 0L

        strafeDir = 1
        lastStrafeSwitch = 0L
        lastCloseStrafeSwitch = 0L
        closeStrafeNextAt = 0L
        longStrafeUntil = 0L

        // Reset rod
        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0
        rodMisses = 0
        pendingRodCheck = false
        lastRodAttemptAt = 0L
        lastOppRodSeenAt = 0L
        rodHoldUntil = 0L
        rodAntiSpamUntil = 0L
        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0
        farSince = 0L
        reentryRodGraceUntil = 0L
        prevDistance = -1f

        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout(fun () {
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onAttack() {
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
            val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
            if (n.contains("rod")) {
                Combat.wTap(300)
                tapping = true
                combo--
                TimeUtils.setTimeout(fun () { tapping = false }, 300)
            } else if (n.contains("sword")) {
                if (distance < 2f) {
                    Mouse.rClick(RandomUtils.randomIntInRange(60, 90))
                } else {
                    Combat.wTap(100)
                    tapping = true
                    TimeUtils.setTimeout(fun () { tapping = false }, 100)
                }
                val now = System.currentTimeMillis()
                forwardStickUntil = now + RandomUtils.randomIntInRange(220, 280)
                meleeFocusUntil = now + RandomUtils.randomIntInRange(300, 340)
                TimeUtils.setTimeout({
                    Movement.startForward(); Movement.startSprinting()
                }, 80)
            }
        }
    }

    override fun onTick() {
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) Movement.startSprinting()

            val p = mc.thePlayer
            val opp = opponent()!!
            val now = System.currentTimeMillis()
            val distance = EntityUtils.getDistanceNoY(p, opp)

            var hasSpeed = false
            var hasRegen = false
            for (effect in p.activePotionEffects) {
                val name = effect.effectName.lowercase()
                if (name.contains("speed")) hasSpeed = true
                if (name.contains("regeneration")) hasRegen = true
            }

            if (!allowStrafing && hasSpeed && hasRegen) allowStrafing = true

            // Tracking caméra : coupé si fuite/potion/gap/aim-lock
            if (retreating || eatingGap || takingPotion || now < aimFreezeUntil) Mouse.stopTracking() else Mouse.startTracking()

            if (kira.config?.kiraHit == true && !retreating && !eatingGap && !takingPotion) Mouse.startLeftAC() else Mouse.stopLeftAC()

            // Sauts contextuels (hors phases bloquantes)
            if (distance > 8.8f && firstSpeedTaken) {
                if (opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (!Mouse.isRunningAway()) Movement.stopJumping()
                } else {
                    Movement.startJumping()
                }
            } else if (!retreating && !takingPotion && !eatingGap) {
                Movement.stopJumping()
            }

            // Avance / stick avant court
            if (now < forwardStickUntil && !takingPotion && !retreating && !eatingGap) {
                Movement.startForward()
            } else if (distance < 0.7f || (distance < 1.4f && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping && !eatingGap && !takingPotion && !retreating) {
                Movement.startForward()
            }

            // Éviter switch épée pendant actions bloquantes
            if (distance < 1.5f && p.heldItem != null &&
                !p.heldItem.unlocalizedName.lowercase().contains("sword") &&
                !Mouse.isUsingPotion() && now >= rodHoldUntil && !eatingGap && !takingPotion && !retreating) {
                Inventory.setInvItem("sword")
            }

            // 2e SPEED (après ouverture uniquement)
            if (openingDone && now >= openingPhaseUntil && !hasSpeed && speedPotsLeft > 0 && now - lastSpeedUse > 15000 &&
                now - lastPotion > 3500 && !takingPotion) {
                retreatAndSplash(speedDamage) {
                    speedPotsLeft--
                    lastSpeedUse = System.currentTimeMillis()
                }
            }

            if (WorldUtils.blockInFront(p, 3f, 1.5f) != Blocks.air) {
                Mouse.setRunningAway(false)
            }

            val hbActive = now < hbActiveUntil

            // Gap / regen tardive
            if (((distance > 3f && p.health < 12) || p.health < 9) &&
                combo < 2 && p.health <= opp.health) {
                if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() &&
                    !eatingGap && !takingPotion && now - lastPotion > 3500) {

                    if (gapsLeft > 0 && now - lastGap > 4000) {
                        eatGoldenApple(distance, distance < 2f, EntityUtils.entityFacingAway(p, opp))
                        gapsLeft--
                    } else if (regenPotsLeft > 0 && now - gameStartAt >= 120000 && now - lastRegenUse > 3500) {
                        retreatAndSplash(regenDamage) {
                            regenPotsLeft--
                            lastRegenUse = System.currentTimeMillis()
                        }
                    }
                }
            }

            // ===== Détection immobile / slow-bow + suivi rod adverse =====
            if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
            val dx = abs(opp.posX - oppLastX)
            val dz = abs(opp.posZ - oppLastZ)
            if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
            val frameSpeed = dx + dz
            if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
            oppLastX = opp.posX; oppLastZ = opp.posZ

            if (opponentLikelyUsingRod(opp)) lastOppRodSeenAt = now

            // Far -> re-entry
            val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)
            if (distance > farThreshold) {
                if (farSince == 0L) farSince = now
            } else {
                if (farSince != 0L && (now - farSince) >= 500L && approaching) {
                    reentryRodGraceUntil = now + 300L
                }
                farSince = 0L
            }

            // MAJ heuristique rod
            updateRodAccuracyHeuristic(now)

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            // =====================  ROD =====================
            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && (!Mouse.rClickDown || hbActive) &&
                !eatingGap && !takingPotion && now - lastGap > 2500) {

                val isStillNow = stillFrames >= stillFramesNeeded
                val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
                val bowLikelyNowClose = oppHasBow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded) && distance <= 10.0f
                val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
                val allowByAntiSpam = now >= rodAntiSpamUntil || now < reentryRodGraceUntil || oppRodRecently

                if (distance > rodBanMeleeDist && distance <= rodMaxRangeHard) {
                    if (isStillNow && distance in rodMidInstantMin..rodMidInstantMax && allowByAntiSpam) {
                        castRodNow(distance)
                        rodAntiSpamUntil = now + RandomUtils.randomIntInRange(300, 380)
                        prevDistance = distance
                    } else if (bowLikelyNowClose) {
                        castRodNow(distance)
                        prevDistance = distance
                    } else {
                        val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
                        val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
                        val cdCloseOK = (now - lastRodUse) >= cdClose || now < reentryRodGraceUntil
                        val cdFarOK = (now - lastRodUse) >= cdFar || now < reentryRodGraceUntil
                        val facingAway = EntityUtils.entityFacingAway(p, opp)
                        val meleeRange = distance < 3.1f
                        val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

                        if (distance in rodMidInstantMin..rodMidInstantMax && allowByAntiSpam) {
                            castRodNow(distance); prevDistance = distance
                        } else if (allowRodByMeleePolicy && distance in rodCloseMin..rodCloseMax && distance > rodBanMeleeDist &&
                            (p.hurtTime > 0 || approaching) && !facingAway && cdCloseOK && allowByAntiSpam) {
                            castRodNow(distance); prevDistance = distance
                        } else if (allowRodByMeleePolicy && !facingAway && (cdFarOK || cdCloseOK) && allowByAntiSpam) {
                            if (oppRodRecently && distance > rodBanMeleeDist) {
                                castRodNow(distance); prevDistance = distance
                            } else if (distance in rodMainMin..rodMainMax && distance > rodBanMeleeDist) {
                                castRodNow(distance); prevDistance = distance
                            }
                        } else if (allowRodByMeleePolicy && distance in rodInterceptMin..rodInterceptMax && !facingAway && (cdFarOK || cdCloseOK) && allowByAntiSpam) {
                            castRodNow(distance); prevDistance = distance
                        }
                    }
                }

                // =====================  ARC / MOUVEMENT =====================
                if ((EntityUtils.entityFacingAway(p, opp) && distance in 3.5f..30f) ||
                    (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(p, opp))) {
                    if (distance > 10f && shotsFired < maxArrows && now - lastPotion > 5000) {
                        clear = true
                        useBow(distance) { shotsFired++ }
                    } else {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                    }
                } else {
                    if (opp.isInvisibleToPlayer(p)) {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                    } else if (EntityUtils.entityFacingAway(p, opp)) {
                        if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                    } else {
                        val nowMs = now
                        if (distance < 3.8f) {
                            if (shouldStartLongStrafe(distance, nowMs)) {
                                longStrafeUntil = nowMs + RandomUtils.randomIntInRange(1200, 2500)
                                strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                                lastCloseStrafeSwitch = nowMs
                                closeStrafeNextAt = longStrafeUntil + RandomUtils.randomIntInRange(100, 300)
                            } else if (longStrafeUntil > nowMs) {
                                // ne change pas
                            } else if (nowMs >= closeStrafeNextAt && nowMs - lastCloseStrafeSwitch >= 150) {
                                strafeDir = -strafeDir
                                lastCloseStrafeSwitch = nowMs
                                closeStrafeNextAt = nowMs + computeCloseStrafeDelay(distance)
                            } else if (closeStrafeNextAt == 0L) {
                                closeStrafeNextAt = nowMs + computeCloseStrafeDelay(distance)
                            }

                            val weightClose = if (longStrafeUntil > nowMs) 6 else 4
                            if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                            randomStrafe = false
                        } else if (distance < 6.5f) {
                            closeStrafeNextAt = 0L
                            if (distance < 5.5f && nowMs - lastStrafeSwitch > RandomUtils.randomIntInRange(1500, 2200)) {
                                strafeDir = -strafeDir; lastStrafeSwitch = nowMs
                            } else if (distance >= 5.5f && nowMs - lastStrafeSwitch > RandomUtils.randomIntInRange(2000, 3000)) {
                                strafeDir = -strafeDir; lastStrafeSwitch = nowMs
                            }
                            val weight = 6
                            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                            randomStrafe = false
                        } else {
                            closeStrafeNextAt = 0L
                            if (distance < 6.5f && nowMs - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                                strafeDir = -strafeDir; lastStrafeSwitch = nowMs
                            }
                            val weight = if (distance < 4f) 7 else 5
                            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                            randomStrafe = (distance >= 8f && opp.heldItem != null &&
                                (opp.heldItem.unlocalizedName.lowercase().contains("bow") ||
                                 opp.heldItem.unlocalizedName.lowercase().contains("rod")))
                            if (randomStrafe && distance < 15f) Movement.stopJumping()
                        }
                    }
                }
            }

            if (WorldUtils.blockInPath(p, RandomUtils.randomIntInRange(3, 7), 1f) == Blocks.fire) {
                Movement.singleJump(RandomUtils.randomIntInRange(200, 400))
            }

            if (allowStrafing && !eatingGap && !takingPotion && !retreating) handle(clear, randomStrafe, movePriority) else { Combat.stopRandomStrafe(); Movement.clearLeftRight() }

            prevDistance = distance
        }
    }
}
