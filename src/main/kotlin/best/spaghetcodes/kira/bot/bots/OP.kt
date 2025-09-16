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
    private var openingRegenPending = false   // Regen d’ouverture différée (à ~20 blocs)

    private var retreating = false
    private var eatingGap = false
    private var preparingGap = false          // phase d’action pré-gap (rod / flint pendant le recul)
    private var firstSpeedTaken = false
    private var allowStrafing = false

    // État potions (action en cours)
    private var takingPotion = false

    var tapping = false

    // Anti double-gap strict
    private val MIN_GAP_INTERVAL_MS = 4500L
    private var gapLockUntil = 0L

    // =====================  STRAFE  =====================
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var lastCloseStrafeSwitch = 0L
    private var closeStrafeNextAt = 0L
    private var longStrafeUntil = 0L
    private var longStrafeChance = 25

    // Verrou d'aim court pour stabiliser un cast
    private var aimFreezeUntil = 0L

    // Anti-parry watchdog : fenêtre pendant laquelle on autorise un court block à l'épée
    private var blockGuardUntil = 0L

    // =====================  PRE-GAP ACTIONS (ROD / FLINT) =====================
    private var usePreGapRod = true
    private var usePreGapFlint = true
    private var preGapRodMaxDistance = 4.2f
    private var preGapFlintMaxDistance = 3.2f
    private var preGapDelayAfterActionMin = 150
    private var preGapDelayAfterActionMax = 240
    private var retreatLeadMsMin = 140           // temps avant d’essayer l’action pendant le recul
    private var retreatLeadMsMax = 220

    private var lastPreGapRodAt = 0L
    private var lastPreGapFlintAt = 0L
    private val preGapRodCooldown = 1200L
    private val preGapFlintCooldown = 1800L

    // =====================  LOGIQUE ROD (import ClassicV2 améliorée)  =====================
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

    // =====================  UTILITAIRES STRAFE (manquants si erreurs) =====================
    private fun computeCloseStrafeDelay(distance: Float): Long = when {
        distance < 2.0f -> RandomUtils.randomIntInRange(120, 160).toLong()
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

    // --------- Détection “adversaire mange” (heuristique) ---------
    private var oppEatingLikelyUntil = 0L     // fenêtre où on pense qu’il mange
    private var oppEatingWatchUntil = 0L      // on surveille la reprise de mouvement

    private fun opponentHoldingApple(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem ?: return false
        val n = held.unlocalizedName.lowercase()
        return n.contains("apple")
    }

    private fun markOpponentEatingWindow(now: Long) {
        oppEatingLikelyUntil = now + RandomUtils.randomIntInRange(1500, 1800)
        oppEatingWatchUntil = now + RandomUtils.randomIntInRange(1900, 2300)
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
            if (Mouse.rClickDown) Mouse.rClickUp()
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

    private fun setPitchLock(pitch: Float, lockMs: Int = 220) {
        val p = mc.thePlayer ?: return
        p.rotationPitch = pitch
        aimFreezeUntil = System.currentTimeMillis() + lockMs
    }

    private fun setPitchInstant(pitch: Float) {
        val p = mc.thePlayer ?: return
        p.rotationPitch = pitch
    }

    private fun pickForwardOrSlightUpPitch(): Float {
        return if (RandomUtils.randomIntInRange(0, 1) == 0) 0f else -RandomUtils.randomIntInRange(6, 12).toFloat()
    }

    private fun pickDownwardPitch(): Float {
        return RandomUtils.randomIntInRange(70, 80).toFloat()
    }

    private fun waitUntilOnGround(maxWaitMs: Int = 420, after: () -> Unit) {
        fun loop(elapsed: Int) {
            val p = mc.thePlayer ?: return
            if (p.onGround || elapsed >= maxWaitMs) after() else TimeUtils.setTimeout({ loop(elapsed + 20) }, 20)
        }
        loop(0)
    }

    private fun waitUntilRunningOnGround(minRunMs: Int, maxWaitMs: Int, after: () -> Unit) {
        fun loop(elapsed: Int, grounded: Int) {
            val p = mc.thePlayer ?: return
            val moving = p.onGround && (Math.abs(p.motionX) + Math.abs(p.motionZ) > 0.08)
            val g = if (moving) grounded + 30 else 0
            val e = elapsed + 30
            if (g >= minRunMs || e >= maxWaitMs) {
                after()
            } else {
                TimeUtils.setTimeout({ loop(e, g) }, 30)
            }
        }
        loop(0, 0)
    }

    private fun smoothFaceAway(totalMsMin: Int = 140, totalMsMax: Int = 200, pitch: Float? = null) {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return
        val dx = p.posX - opp.posX
        val dz = p.posZ - opp.posZ
        if (dx == 0.0 && dz == 0.0) return
        val targetYaw = (Math.toDegrees(kotlin.math.atan2(dz, dx)) - 90.0).toFloat()
        fun wrap(a: Float): Float { var x = a; while (x <= -180f) x += 360f; while (x > 180f) x -= 360f; return x }
        fun delta(cur: Float, tgt: Float) = wrap(tgt - cur)

        val curYaw = p.rotationYaw
        val d = delta(curYaw, targetYaw)
        val midYaw = wrap(curYaw + d * 0.55f)
        val midDelay = RandomUtils.randomIntInRange(60, 90)
        val endDelay = RandomUtils.randomIntInRange(totalMsMin, totalMsMax)

        if (pitch != null) p.rotationPitch = pitch
        aimFreezeUntil = System.currentTimeMillis() + endDelay

        TimeUtils.setTimeout({
            p.rotationYaw = midYaw; p.rotationYawHead = midYaw; p.renderYawOffset = midYaw
            if (pitch != null) p.rotationPitch = pitch
        }, midDelay)
        TimeUtils.setTimeout({
            p.rotationYaw = targetYaw; p.rotationYawHead = targetYaw; p.renderYawOffset = targetYaw
            if (pitch != null) p.rotationPitch = pitch
        }, endDelay)
    }

    private fun startOppositeRun() {
        Mouse.setRunningAway(true)
        Movement.startForward()
        Movement.startSprinting()
        Movement.startJumping()
        Movement.clearLeftRight()
    }

    private fun stopOppositeRun() {
        Movement.stopForward()
        Movement.stopSprinting()
        Movement.stopJumping()
        Mouse.setRunningAway(false)
    }

    // ========= Utilitaire fiable pour l’état d’ingestion =========
    private fun isUsingItemSafe(p: net.minecraft.entity.player.EntityPlayer?): Boolean {
        if (p == null) return false
        return try {
            p.isUsingItem || p.itemInUseCount > 0
        } catch (_: Throwable) {
            p.isUsingItem
        }
    }

    // ========= Attendre la fin RÉELLE de l’ingestion de la gap =========
    private fun waitUntilFinishedEating(maxWaitMs: Int = 2400, after: () -> Unit) {
        fun loop(elapsed: Int, hasStarted: Boolean) {
            val p = mc.thePlayer ?: run { after(); return }
            val stillEating = isUsingItemSafe(p)
            val started = hasStarted || stillEating
            if ((started && !stillEating) || elapsed >= maxWaitMs) after()
            else TimeUtils.setTimeout({ loop(elapsed + 40, started) }, 40)
        }
        loop(0, false)
    }

    // ---- OUVERTURE : cast en place (SANS retrait) ----
    private fun castOpeningPotionInPlace(damage: Int, onComplete: (() -> Unit)? = null) {
        if (takingPotion) return
        takingPotion = true
        Movement.startForward(); Movement.startSprinting(); Movement.stopJumping()
        val pitch = pickForwardOrSlightUpPitch()

        waitUntilRunningOnGround(
            minRunMs = RandomUtils.randomIntInRange(160, 240),
            maxWaitMs = 800
        ) {
            setPitchLock(pitch, lockMs = RandomUtils.randomIntInRange(220, 300))
            Mouse.stopTracking()
            useSplashPotion(damage, false, false)
            lastPotion = System.currentTimeMillis()

            TimeUtils.setTimeout({
                Movement.singleJump(RandomUtils.randomIntInRange(180, 240))
                takingPotion = false
                onComplete?.invoke()
            }, RandomUtils.randomIntInRange(180, 240))
        }
    }

    // ---- COMBAT : fuite opposée + cast ----
    private fun retreatAndSplash(damage: Int, onComplete: () -> Unit) {
        if (takingPotion) return
        retreating = true
        takingPotion = true
        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(false)

        val pitch = pickForwardOrSlightUpPitch()
        smoothFaceAway(totalMsMin = 140, totalMsMax = 220, pitch = pitch)
        Mouse.stopTracking()

        startOppositeRun(); Movement.stopJumping()

        waitUntilRunningOnGround(
            minRunMs = RandomUtils.randomIntInRange(150, 230),
            maxWaitMs = 900
        ) {
            setPitchLock(pitch, lockMs = RandomUtils.randomIntInRange(240, 320))
            useSplashPotion(damage, false, false)
            lastPotion = System.currentTimeMillis()
            onComplete()

            TimeUtils.setTimeout({
                Movement.singleJump(RandomUtils.randomIntInRange(160, 220))
                stopOppositeRun()
                retreating = false
                takingPotion = false
            }, RandomUtils.randomIntInRange(160, 220))
        }
    }

    // ---- 2e Speed / 2e Regen : cast aux PIEDS ----
    private fun feetSplash(damage: Int, onComplete: (() -> Unit)? = null) {
        if (takingPotion) return
        takingPotion = true
        Mouse.stopTracking()
        Movement.stopJumping()
        waitUntilOnGround(maxWaitMs = 420) {
            val down = pickDownwardPitch()
            setPitchInstant(down)
            TimeUtils.setTimeout({
                useSplashPotion(damage, false, false)
                if (Mouse.rClickDown) Mouse.rClickUp()
                lastPotion = System.currentTimeMillis()
                setPitchLock(down, lockMs = RandomUtils.randomIntInRange(130, 170))
                takingPotion = false
                Mouse.startTracking()
                onComplete?.invoke()
            }, RandomUtils.randomIntInRange(80, 140))
        }
    }

    // ---- FLINT & ROD (pendant le recul) ----
    private fun ensureRodSelected(): Boolean {
        return Inventory.setInvItem("rod") || Inventory.setInvItem("fishing_rod") || Inventory.setInvItem("rod_")
    }
    private fun ensureFlintSelected(): Boolean {
        return Inventory.setInvItem("flint_and_steel") || Inventory.setInvItem("flint") || Inventory.setInvItem("steel")
    }

    private fun tryPlaceFireWhileRetreating(currentDistance: Float): Boolean {
        val now = System.currentTimeMillis()
        if (!usePreGapFlint || currentDistance > preGapFlintMaxDistance) return false
        if (now - lastPreGapFlintAt < preGapFlintCooldown) return false
        if (takingPotion || eatingGap || preparingGap) return false
        if (!ensureFlintSelected()) return false

        preparingGap = true
        Mouse.stopLeftAC()
        Movement.stopJumping()
        val down = RandomUtils.randomIntInRange(68, 80).toFloat()
        setPitchInstant(down)

        TimeUtils.setTimeout({
            Mouse.rClick(RandomUtils.randomIntInRange(70, 110))
            lastPreGapFlintAt = System.currentTimeMillis()
            setPitchLock(down, lockMs = RandomUtils.randomIntInRange(100, 150))
            preparingGap = false
        }, RandomUtils.randomIntInRange(60, 90))

        return true
    }

    private fun tryRodWhileRetreating(currentDistance: Float): Boolean {
        val now = System.currentTimeMillis()
        if (!usePreGapRod || currentDistance > preGapRodMaxDistance) return false
        if (now - lastPreGapRodAt < preGapRodCooldown) return false
        if (takingPotion || eatingGap || preparingGap) return false
        if (!ensureRodSelected()) return false

        val allow = now >= rodAntiSpamUntil || now < reentryRodGraceUntil
        if (!allow) return false

        preparingGap = true
        castRodNow(currentDistance)
        lastPreGapRodAt = System.currentTimeMillis()
        TimeUtils.setTimeout({ preparingGap = false }, RandomUtils.randomIntInRange(110, 160))
        return true
    }

    // ---- GAP fiable (corrigée) + pré-actions PENDANT le recul ----
    private fun eatGoldenApple(distance: Float, close: Boolean, facingAway: Boolean) {
        val now = System.currentTimeMillis()
        if (eatingGap || preparingGap || now < lastGap + MIN_GAP_INTERVAL_MS) return

        val p = mc.thePlayer ?: return

        // -------- SEULEMENT SI (PV BRUTS < 10) OU (regen < 30s ET PV BRUTS < 8) --------
        val recentRegen = now - lastRegenUse < 30_000L
        val healthOnly = p.health // <-- pas d'absorption dans ce calcul
        val gapThreshold = if (recentRegen) 8f else 10f
        if (healthOnly >= gapThreshold) return
        // -----------------------------------------------------------------------------

        // ==== 1) Recul d'abord ====
        val wasForward = Movement.forward()
        Movement.stopForward()
        Movement.startBackward()
        if (!facingAway) smoothFaceAway(totalMsMin = 100, totalMsMax = 160, pitch = null)

        // ==== 2) Pendant le recul, tenter FLINT ou ROD ====
        val lead = RandomUtils.randomIntInRange(retreatLeadMsMin, retreatLeadMsMax)
        TimeUtils.setTimeout({
            val pNow = mc.thePlayer ?: return@setTimeout
            val opp = opponent()
            val curDist = if (opp != null) EntityUtils.getDistanceNoY(pNow, opp) else distance

            var didPre = false
            if (usePreGapFlint && curDist <= preGapFlintMaxDistance) {
                didPre = tryPlaceFireWhileRetreating(curDist)
            }
            if (!didPre && usePreGapRod && curDist <= preGapRodMaxDistance) {
                didPre = tryRodWhileRetreating(curDist)
            }

            val preDelay = if (didPre) RandomUtils.randomIntInRange(preGapDelayAfterActionMin, preGapDelayAfterActionMax) else 0

            // ==== 3) Après l'action, on MANGE ====
            TimeUtils.setTimeout({
                eatingGap = true
                Mouse.stopLeftAC()
                Mouse.setUsingProjectile(false)

                val againOpp = opponent()
                val againDist = if (againOpp != null) EntityUtils.getDistanceNoY(pNow, againOpp) else curDist
                if (againDist < 2.2f || close) {
                    Movement.startBackward()
                } else {
                    Movement.stopBackward()
                    if (wasForward) Movement.startForward()
                }

                var eatingStarted = false
                var decremented = false

                fun ensureHoldingGap(): Boolean {
                    val held = pNow.heldItem?.unlocalizedName?.lowercase() ?: ""
                    if (held.contains("apple")) return true
                    return Inventory.setInvItem("gold") ||
                           Inventory.setInvItem("gap") ||
                           Inventory.setInvItem("gapple") ||
                           Inventory.setInvItem("apple") ||
                           Inventory.setInvItem("golden_apple")
                }

                fun tryStartEat(forceHoldMs: Int? = null, after: (() -> Unit)? = null) {
                    val okSelect = ensureHoldingGap()
                    if (okSelect) {
                        val hold = forceHoldMs ?: RandomUtils.randomIntInRange(1200, 1600)
                        if (!Mouse.rClickDown) Mouse.rClick(hold)
                        TimeUtils.setTimeout({
                            if (!isUsingItemSafe(pNow) && !Mouse.rClickDown) {
                                ensureHoldingGap()
                                Mouse.rClick(RandomUtils.randomIntInRange(900, 1300))
                            }
                            after?.invoke()
                        }, RandomUtils.randomIntInRange(90, 130))
                    } else {
                        after?.invoke()
                    }
                }

                useGap(againDist, false, EntityUtils.entityFacingAway(pNow, againOpp))

                TimeUtils.setTimeout({
                    eatingStarted = isUsingItemSafe(pNow)
                    if (!eatingStarted) {
                        tryStartEat(forceHoldMs = RandomUtils.randomIntInRange(1200, 1600)) {
                            eatingStarted = isUsingItemSafe(pNow)
                        }
                    }
                }, RandomUtils.randomIntInRange(90, 130))

                TimeUtils.setTimeout({
                    eatingStarted = eatingStarted || isUsingItemSafe(pNow)

                    if (eatingStarted) {
                        if (!decremented) {
                            gapsLeft = max(0, gapsLeft - 1)
                            lastGap = System.currentTimeMillis()
                            gapLockUntil = lastGap + MIN_GAP_INTERVAL_MS
                            decremented = true
                        }

                        waitUntilFinishedEating(maxWaitMs = 2600) {
                            Movement.stopBackward()
                            if (wasForward) Movement.startForward()
                            eatingGap = false
                            if (Mouse.rClickDown) Mouse.rClickUp()
                            if (!Mouse.isUsingProjectile() && !Mouse.isUsingPotion()) {
                                Inventory.setInvItem("sword")
                            }
                        }
                    } else {
                        Movement.stopBackward()
                        if (wasForward) Movement.startForward()
                        eatingGap = false
                        if (Mouse.rClickDown) Mouse.rClickUp()
                        if (!Mouse.isUsingProjectile() && !Mouse.isUsingPotion()) {
                            Inventory.setInvItem("sword")
                        }
                    }
                }, RandomUtils.randomIntInRange(240, 320))
            }, preDelay)

        }, lead)
    }

    // =====================  LIFECYCLE  =====================
    override fun onGameStart() {
        gameStartAt = System.currentTimeMillis()
        openingPhaseUntil = gameStartAt + 5500L
        openingDone = false
        openingRegenPending = false

        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.clearLeftRight()
        Combat.stopRandomStrafe()
        allowStrafing = false

        takingPotion = false
        aimFreezeUntil = 0L
        preparingGap = false
        eatingGap = false

        // OUVERTURE : Speed en place, puis Regen d’ouverture différée (~20 blocs)
        TimeUtils.setTimeout({
            castOpeningPotionInPlace(speedDamage) {
                speedPotsLeft--
                lastSpeedUse = System.currentTimeMillis()
                firstSpeedTaken = true
                openingRegenPending = true
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

        lastPreGapRodAt = 0L
        lastPreGapFlintAt = 0L

        oppEatingLikelyUntil = 0L
        oppEatingWatchUntil = 0L
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
        openingRegenPending = false
        retreating = false
        eatingGap = false
        preparingGap = false
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

        lastPreGapRodAt = 0L
        lastPreGapFlintAt = 0L

        oppEatingLikelyUntil = 0L
        oppEatingWatchUntil = 0L

        Mouse.stopLeftAC()
        if (Mouse.rClickDown) Mouse.rClickUp()
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
                    blockGuardUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(160, 240)
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

            val p = mc.thePlayer!!   // <— FIX nullabilité pour les API qui attendent Entity non-null
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

            // Tracking caméra : coupé si fuite/potion/aim-lock/pré-gap
            if (retreating || takingPotion || now < aimFreezeUntil || preparingGap) Mouse.stopTracking() else Mouse.startTracking()

            if (kira.config?.kiraHit == true && !retreating && !eatingGap && !takingPotion && !preparingGap) Mouse.startLeftAC() else Mouse.stopLeftAC()

            // Sauts contextuels
            if (distance > 8.8f && firstSpeedTaken) {
                if (opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (!Mouse.isRunningAway()) Movement.stopJumping()
                } else {
                    Movement.startJumping()
                }
            } else {
                Movement.stopJumping()
            }

            // Avance / stick avant court
            if (now < forwardStickUntil && !takingPotion && !retreating && !eatingGap && !preparingGap) {
                Movement.startForward()
            } else if (distance < 0.7f || distance < 1.4f) {
                Movement.stopForward()
            } else if (!tapping && !eatingGap && !takingPotion && !retreating && !preparingGap) {
                Movement.startForward()
            }

            // ===================== Anti-parry STUCK (patch) =====================
            if (p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")) {
                val usingOther = Mouse.isUsingPotion() || Mouse.isUsingProjectile() || eatingGap || retreating || takingPotion || preparingGap || isUsingItemSafe(p)
                val allowShortBlock = (!usingOther) && now < blockGuardUntil && distance < 2.6f
                val tooLong = now > blockGuardUntil + 200
                if (Mouse.rClickDown && (!allowShortBlock || tooLong)) Mouse.rClickUp()
            } else {
                val safeToRelease = !Mouse.isUsingProjectile() && !Mouse.isUsingPotion() && !eatingGap && !preparingGap && !isUsingItemSafe(p)
                if (Mouse.rClickDown && safeToRelease) Mouse.rClickUp()
            }
            // ===================================================================

            // ===== OUVERTURE — 1re REGEN À 20 BLOCS =====
            if (openingRegenPending && !takingPotion && regenPotsLeft > 0 && !hasRegen) {
                if (distance >= 19.5f) {
                    castOpeningPotionInPlace(regenDamage) {
                        regenPotsLeft--
                        lastRegenUse = System.currentTimeMillis()
                        openingRegenPending = false
                        openingDone = true
                    }
                }
            }

            // ===== 2e SPEED : cast aux pieds =====
            if (openingDone && now >= openingPhaseUntil && !hasSpeed && speedPotsLeft > 0 && now - lastSpeedUse > 15000 &&
                now - lastPotion > 3500 && !takingPotion) {
                feetSplash(speedDamage) {
                    speedPotsLeft--
                    lastSpeedUse = System.currentTimeMillis()
                }
            }

            if (WorldUtils.blockInFront(p, 3f, 1.5f) != Blocks.air) {
                Mouse.setRunningAway(false)
            }

            val hbActive = now < hbActiveUntil

            // ===== Détection immobile / slow-bow + suivi rod adverse =====
            if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
            val dx = abs(opp.posX - oppLastX)
            val dz = abs(opp.posZ - oppLastZ)
            if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
            val frameSpeed = dx + dz
            if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
            oppLastX = opp.posX; oppLastZ = opp.posZ

            if (opponentLikelyUsingRod(opp)) lastOppRodSeenAt = now

            // ======= Heuristique “l’adversaire mange” =======
            val oppHoldingApple = opponentHoldingApple(opp)
            val eatingHeuristic = oppHoldingApple || (stillFrames >= stillFramesNeeded && bowSlowFrames >= bowSlowFramesNeeded + 1)
            if (eatingHeuristic) markOpponentEatingWindow(now)

            // ======= Réaction si on pense qu’il mange =======
            val oppEatingWindow = now < oppEatingLikelyUntil
            if (oppEatingWindow && !takingPotion && !eatingGap && !preparingGap) {
                val healthOnly = p.health
                val recentRegen = now - lastRegenUse < 30_000L
                val baseGapThreshold = if (recentRegen) 8f else 10f
                val opportunisticThreshold = min(14f, baseGapThreshold + 4f)
                val canBow = (distance >= 7.0f && shotsFired < maxArrows && now - lastPotion > 1500)
                val canOpportunisticGap = (healthOnly < 20f && healthOnly < opportunisticThreshold &&
                        gapsLeft > 0 && now >= gapLockUntil && !Mouse.isUsingProjectile() && !Mouse.isUsingPotion())

                if (canBow) {
                    useBow(distance) { shotsFired++ }
                } else if (canOpportunisticGap) {
                    eatGoldenApple(distance, distance < 2f, EntityUtils.entityFacingAway(p, opp))
                }
            }

            // ======= Quand il recommence à bouger : Rod instant =======
            val oppEatingWatch = now < oppEatingWatchUntil
            val movementResumed = oppEatingWatch && (stillFrames < 2 || frameSpeed > bowSlowThreshold * 1.6)
            if (movementResumed && !takingPotion && !eatingGap && !preparingGap) {
                if (distance > 2.2f && distance <= rodMaxRangeHard) {
                    val allowByAntiSpam = now >= rodAntiSpamUntil || now < reentryRodGraceUntil
                    if (allowByAntiSpam) {
                        castRodNow(distance)
                        oppEatingWatchUntil = now
                    }
                } else {
                    oppEatingWatchUntil = now
                }
            }

            // Far -> re-entry pour lever la latence de rod
            val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)
            if (distance > 11.0f) {
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

            // =====================  ROD (logique ClassicV2 adaptée)  =====================
            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && (!Mouse.rClickDown || hbActive) &&
                !eatingGap && !takingPotion && !preparingGap && now - lastGap > 2500) {

                val isStillNow = stillFrames >= stillFramesNeeded
                val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
                val bowLikelyNowClose = oppHasBow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded) && distance <= 10.0f
                val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
                val allowByAntiSpam2 = now >= rodAntiSpamUntil || now < reentryRodGraceUntil || oppRodRecently

                if (distance > rodBanMeleeDist && distance <= rodMaxRangeHard) {
                    if (isStillNow && distance in rodMidInstantMin..rodMidInstantMax && allowByAntiSpam2) {
                        castRodNow(distance)
                        rodAntiSpamUntil = now + RandomUtils.randomIntInRange(300, 380)
                        prevDistance = distance
                    } else if (bowLikelyNowClose) {
                        castRodNow(distance); prevDistance = distance
                    } else {
                        val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
                        val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
                        val cdCloseOK = (now - lastRodUse) >= cdClose || now < reentryRodGraceUntil
                        val cdFarOK = (now - lastRodUse) >= cdFar || now < reentryRodGraceUntil
                        val facingAway = EntityUtils.entityFacingAway(p, opp)
                        val meleeRange = distance < 3.1f
                        val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

                        if (distance in rodMidInstantMin..rodMidInstantMax && allowByAntiSpam2) {
                            castRodNow(distance); prevDistance = distance
                        } else if (allowRodByMeleePolicy && distance in rodCloseMin..rodCloseMax && distance > rodBanMeleeDist &&
                                   (p.hurtTime > 0 || approaching) && !facingAway && cdCloseOK && allowByAntiSpam2) {
                            castRodNow(distance); prevDistance = distance
                        } else if (allowRodByMeleePolicy && !facingAway && (cdFarOK || cdCloseOK) && allowByAntiSpam2) {
                            if (oppRodRecently && distance > rodBanMeleeDist) {
                                castRodNow(distance); prevDistance = distance
                            } else if (distance in rodMainMin..rodMainMax && distance > rodBanMeleeDist) {
                                castRodNow(distance); prevDistance = distance
                            }
                        } else if (allowRodByMeleePolicy && distance in rodInterceptMin..rodInterceptMax && !facingAway && (cdFarOK || cdCloseOK) && allowByAntiSpam2) {
                            castRodNow(distance); prevDistance = distance
                        }
                    }
                }

                // =====================  ARC / MOUVEMENT (logique OP d'origine)  =====================
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
                    // NOTE: on évite opp.isInvisibleToPlayer(p) (diff sigs) pour compat build
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

            if (WorldUtils.blockInPath(p, RandomUtils.randomIntInRange(3, 7), 1f) == Blocks.fire) {
                Movement.singleJump(RandomUtils.randomIntInRange(200, 400))
            }

            if (allowStrafing && !eatingGap && !takingPotion && !retreating && !preparingGap) handle(clear, randomStrafe, movePriority)
            else { Combat.stopRandomStrafe(); Movement.clearLeftRight() }

            prevDistance = distance
        }
    }
}
