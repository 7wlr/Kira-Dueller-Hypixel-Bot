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

    // ---------- TIMERS / ETATS ----------
    private var actionLockUntil = 0L       // interdit toute nouvelle action (arc/rod/switchs) tant que > now
    private var lastBowAt = 0L
    private var lastRodAt = 0L
    private var shotsFired = 0
    private val maxArrows = 5
    private var gameStartAt = 0L
    private var firstRodPending = true     // pour fiabiliser la toute première rod à l’entrée en zone

    private var prevDistance = -1f
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1

    private var lastGotHitAt = 0L

    // ---------- SEUILS ----------
    private val BOW_MIN_DIST = 9.5f         // pas d’arc en-dessous
    private val BOW_CANCEL_DIST = 8.0f      // si on charge et l’ennemi s’approche, on annule
    private val BOW_SPACING_MS = 600L       // espacement mini entre deux tentatives d’arc

    private val ROD_CLOSE_MIN = 1.8f
    private val ROD_CLOSE_MAX = 3.2f
    private val ROD_MAIN_MIN = 3.0f
    private val ROD_MAIN_MAX = 6.6f
    private val ROD_INT_MIN  = 5.8f
    private val ROD_INT_MAX  = 7.2f

    // ---------- LIFECYCLE ----------
    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

        shotsFired = 0
        actionLockUntil = 0L
        lastBowAt = 0L
        lastRodAt = 0L
        gameStartAt = System.currentTimeMillis()
        firstRodPending = true

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

        lastGotHitAt = 0L
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
        // w-tap court + stick avant agressif
        Combat.wTap(100)
        TimeUtils.setTimeout({
            Movement.startForward()
            Movement.startSprinting()
        }, 70)
    }

    // ---------- HELPERS ----------
    private fun now(): Long = System.currentTimeMillis()
    private fun locked(n: Long) = n < actionLockUntil

    private fun lock(ms: Long) {
        actionLockUntil = max(actionLockUntil, now() + ms)
    }

    private fun adjustedAimDistance(d: Float): Float {
        // baisse légère mid-range (15–25) pour corriger la trajectoire trop haute
        return when {
            d in 15.0f..25.0f -> d * 0.90f
            d in 25.0f..32.0f -> d * 0.88f
            d in 10.0f..15.0f -> d * 0.94f
            else              -> d
        }
    }

    private fun safeCancelBowWhenTooClose(distance: Float) {
        if (Mouse.rClickDown &&
            mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()?.contains("bow") == true &&
            distance < BOW_CANCEL_DIST) {
            Mouse.rClickUp()
            Inventory.setInvItem("sword")
            lock(120) // évite re-switch immédiat
        }
    }

    // ---------- TICK ----------
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val t = now()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.14f)

        if (p.hurtTime > 0) lastGotHitAt = t

        // petit anti-bloc basique
        if (distance > 2.0f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround && !Mouse.rClickDown) {
                Movement.singleJump(RandomUtils.randomIntInRange(140, 220))
            }
        }

        // annule l’arc si trop proche
        safeCancelBowWhenTooClose(distance)

        // si action en cours -> juste gérer le mouvement et sortir
        if (locked(t)) {
            basicMove(p, opp, distance, t)
            prevDistance = distance
            return
        }

        // garder l’épée si très proche
        if (distance < 1.5f && p.heldItem != null &&
            !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
            Inventory.setInvItem("sword")
        }

        // ---------- PRIORITE 1 : ROD (close/mid/intercept) ----------
        val facingAway = EntityUtils.entityFacingAway(p, opp)
        val dx = abs(opp.posX - opp.lastTickPosX)
        val dz = abs(opp.posZ - opp.lastTickPosZ)
        val lateral = dx + dz

        // Première rod fiable : à la 1ère entrée dans la zone 3–7 blocs on la privilégie
        if (firstRodPending && distance in 3.0f..7.0f && !facingAway) {
            // petite fenêtre anti-double action
            useRod()
            lock(300)
            lastRodAt = t
            firstRodPending = false
            prevDistance = distance
            return
        }

        // casser un combo en ultra-close après avoir pris un hit
        if (distance < 2.2f && p.hurtTime > 0 && !facingAway && (t - lastRodAt) >= 220) {
            useRodImmediate()
            lock(260)
            lastRodAt = t
            prevDistance = distance
            return
        }

        // close principal quand on s’approche ou vient d’être touché
        if (distance in ROD_CLOSE_MIN..ROD_CLOSE_MAX &&
            (approaching || p.hurtTime > 0) && !facingAway && (t - lastRodAt) >= 260) {
            useRod()
            lock(300)
            lastRodAt = t
            prevDistance = distance
            return
        }

        // intercept mid (strafe fort ou range principal)
        if (!facingAway && (t - lastRodAt) >= 340) {
            val strongLateral = (lateral > 0.18 && distance in 4.8f..7.0f)
            if (strongLateral || distance in ROD_MAIN_MIN..ROD_MAIN_MAX ||
                (distance in ROD_INT_MIN..ROD_INT_MAX && approaching)) {
                useRod()
                lock(320)
                lastRodAt = t
                prevDistance = distance
                return
            }
        }

        // ---------- PRIORITE 2 : ARC (strictement à distance) ----------
        val canBow = distance >= BOW_MIN_DIST &&
            shotsFired < maxArrows &&
            (t - lastBowAt) >= BOW_SPACING_MS

        if (canBow) {
            val tunedD = adjustedAimDistance(distance)
            // Tir normal (charge complète) – Bow.kt gère le hold
            useBow(tunedD) {
                shotsFired++
            }
            // on “bloque” le reste des actions pendant la charge + un petit tail
            lock(1250L + 140L)
            lastBowAt = t
            prevDistance = distance
            return
        }

        // ---------- MOUVEMENT / HANDLE ----------
        basicMove(p, opp, distance, t)
        prevDistance = distance
    }

    private fun basicMove(p: net.minecraft.entity.player.EntityPlayer, opp: net.minecraft.entity.EntityLivingBase, distance: Float, t: Long) {
        // avancer/stopper
        if (distance < 0.75f || (distance < 2.4f && combo >= 2 && (prevDistance > 0 && prevDistance - distance >= 0.14f))) {
            Movement.stopForward()
        } else {
            Movement.startForward()
        }

        // strafe directionnel propre
        val movePriority = arrayListOf(0, 0)
        var randomStrafe = false
        var clear = false

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else {
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null && t - lastStrafeSwitch > 300) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = t
                }
            }
            // inversion anti-stagnation
            val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
            if (distance in 1.8f..3.6f && deltaDist < 0.03f && t - lastStrafeSwitch > 260) {
                strafeDir = -strafeDir
                lastStrafeSwitch = t
            }
            val weight = if (distance < 4f) 7 else 5
            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
            randomStrafe = (distance in 8.0f..15.0f)
        }

        handle(clear, randomStrafe, movePriority)
    }
}
