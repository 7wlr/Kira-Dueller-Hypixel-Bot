package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// Mouvement de lobby : FAST_FORWARD (inchangé) et SUMO (cercle régulier et humain)

object LobbyMovement {

    // === commun ===
    private var tickYawChange = 0f
    private var desiredPitch: Float? = null
    private var intervals: ArrayList<Timer?> = ArrayList()
    private var activeMovementType: Config.LobbyMovementType? = null

    private fun canActivateAndRunAnyMovement(): Boolean {
        return kira.mc.thePlayer != null &&
                kira.bot?.toggled() == true &&
                kira.config?.lobbyMovement == true
    }

    fun startMovement(typeToStart: Config.LobbyMovementType) {
        if (!canActivateAndRunAnyMovement()) {
            if (activeMovementType != null) stop()
            return
        }
        if (activeMovementType == typeToStart) {
            if (typeToStart == Config.LobbyMovementType.FAST_FORWARD) {
                if (kira.mc.thePlayer?.onGround == true && !Movement.jumping()) {
                    Movement.startJumping()
                }
            }
            return
        }
        stop()
        activeMovementType = typeToStart
        internalStartMovementLogic(activeMovementType!!)
    }

    private fun internalStartMovementLogic(type: Config.LobbyMovementType) {
        when (type) {
            Config.LobbyMovementType.FAST_FORWARD -> runForwardPreGameInternal()
            Config.LobbyMovementType.SUMO -> sumoInternal()
        }
    }

    // =======================
    // FAST_FORWARD (exemple)
    // =======================
    private fun runForwardPreGameInternal() {
        Movement.startForward()
        Movement.startSprinting()
        Movement.startJumping()
        tickYawChange = 0f
        desiredPitch = 0f
        intervals.add(TimeUtils.setInterval(fun() {
            val p = kira.mc.thePlayer ?: return@setInterval
            p.rotationYaw += 180f
        }, 7000, 7000))
    }

    // =======================
    //         SUMO
    // =======================

    // —— Réglages cercle ——
    private const val SUMO_INITIAL_ANGLE_DEG = 45f     // pivot initial (toujours)
    private const val SUMO_STEP_ANGLE_DEG = 60f        // angle appliqué à CHAQUE saut à partir du 2e (↑ => cercle plus petit)
    private const val SUMO_JUMP_DELAY_MIN = 80         // ms (relance saut)
    private const val SUMO_JUMP_DELAY_MAX = 150        // ms

    // —— Réglages lissage ——
    private const val INITIAL_SMOOTH_MIN_TICKS = 4     // durée lissée du pivot initial (en ticks)
    private const val INITIAL_SMOOTH_MAX_TICKS = 7
    // Les rotations par saut utilisent la durée du temps de vol mesuré (clampé ci-dessous).
    private const val AIR_SMOOTH_MIN_TICKS = 4
    private const val AIR_SMOOTH_MAX_TICKS = 14

    // — État Sumo —
    private var sumoInitialTurnRight = true            // sens pivot initial
    private var sumoCircleTurnRight = false            // sens du cercle (opposé au pivot initial)
    private var sumoJumpCounter = 0                    // 1er saut = 1
    private var sumoWasOnGround = false                // pour les fronts

    // — Mesure temps de vol —
    private var ticksAirborneCurrent = 0               // ticks en l’air pour le saut en cours
    private var lastAirTicks = 8                       // estimation du temps de vol (sert pour le saut suivant)

    // — Rotation lissée planifiée —
    private var yawPlanActive = false
    private var yawPlanTotalAngle = 0f                 // angle total de la rotation en cours
    private var yawPlanTicksTotal = 0                  // durée totale (ticks)
    private var yawPlanTickIndex = 0                   // progression (ticks)
    private var yawPlanLastEase = 0f                   // ease(t-1) pour calculer l'incrément
    private var queuedYawAngle: Float? = null          // angle en attente de décollage (pour éviter de tourner au sol)

    private fun easeInOutSine(t: Float): Float {
        // t in [0..1]
        return (-(cos(PI * t) - 1.0) / 2.0).toFloat()
    }

    private fun startYawPlan(totalAngleDeg: Float, durationTicks: Int) {
        val dur = max(1, min(max(durationTicks, AIR_SMOOTH_MIN_TICKS), AIR_SMOOTH_MAX_TICKS))
        yawPlanActive = true
        yawPlanTotalAngle = totalAngleDeg
        yawPlanTicksTotal = dur
        yawPlanTickIndex = 0
        yawPlanLastEase = 0f
    }

    private fun tickYawPlan() {
        if (!yawPlanActive) return
        val p = kira.mc.thePlayer ?: return

        yawPlanTickIndex++
        val t = min(1f, yawPlanTickIndex.toFloat() / yawPlanTicksTotal.toFloat())
        val e = easeInOutSine(t)
        val step = yawPlanTotalAngle * (e - yawPlanLastEase)

        p.rotationYaw += step
        yawPlanLastEase = e

        if (yawPlanTickIndex >= yawPlanTicksTotal) {
            yawPlanActive = false
            // sécurité floating point : finir l’angle exactement
            val done = yawPlanTotalAngle * e
            val correction = yawPlanTotalAngle - done
            p.rotationYaw += correction
        }
    }

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return

        // Pitch “vivant” mais discret
        desiredPitch = RandomUtils.randomDoubleInRange(-3.0, 6.0).toFloat()

        // 1) pivot initial ±45° (lissé sur 4..7 ticks)
        sumoInitialTurnRight = RandomUtils.randomBool()
        val initialDelta = if (sumoInitialTurnRight) SUMO_INITIAL_ANGLE_DEG else -SUMO_INITIAL_ANGLE_DEG
        startYawPlan(initialDelta, RandomUtils.randomIntInRange(INITIAL_SMOOTH_MIN_TICKS, INITIAL_SMOOTH_MAX_TICKS))

        // 2) sens du cercle = opposé au pivot initial
        sumoCircleTurnRight = !sumoInitialTurnRight

        // 3) démarrage
        Movement.startForward()
        Movement.startSprinting()
        tickYawChange = 0f
        sumoJumpCounter = 0
        sumoWasOnGround = player.onGround
        ticksAirborneCurrent = 0
        lastAirTicks = 8
        queuedYawAngle = null

        // 4) 1er saut immédiat (SANS rotation)
        if (player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(SUMO_JUMP_DELAY_MIN, SUMO_JUMP_DELAY_MAX))
            sumoJumpCounter = 1
        }
    }

    private fun sumoTick() {
        val p = kira.mc.thePlayer ?: return

        // maintenir la marche/sprint
        if (!Movement.forward()) Movement.startForward()
        if (!Movement.sprinting()) Movement.startSprinting()

        val onGround = p.onGround
        val justLanded = onGround && !sumoWasOnGround
        val justTookOff = !onGround && sumoWasOnGround

        // Décollage : si une rotation est en attente, on la démarre MAINTENANT et on l'étale sur la durée estimée du vol
        if (justTookOff) {
            ticksAirborneCurrent = 0
            queuedYawAngle?.let { angle ->
                startYawPlan(angle, lastAirTicks)
                queuedYawAngle = null
            }
        }

        // En l’air : compter les ticks de vol
        if (!onGround) {
            ticksAirborneCurrent++
        }

        // Atterrissage : planifier le saut suivant, et préparer la rotation du saut qui va démarrer
        if (justLanded) {
            // mémoriser la durée de vol réelle du saut qui vient de finir
            lastAirTicks = min(max(ticksAirborneCurrent, AIR_SMOOTH_MIN_TICKS), AIR_SMOOTH_MAX_TICKS)

            // relancer un saut (petit délai réaliste)
            Movement.singleJump(RandomUtils.randomIntInRange(SUMO_JUMP_DELAY_MIN, SUMO_JUMP_DELAY_MAX))
            sumoJumpCounter++

            // Règle : 1er saut -> pas de rotation ; à partir du 2e -> rotation à chaque saut
            if (sumoJumpCounter >= 2) {
                val angle = if (sumoCircleTurnRight) SUMO_STEP_ANGLE_DEG else -SUMO_STEP_ANGLE_DEG
                // Ne pas tourner au sol → on met l’angle "en attente" du prochain décollage
                queuedYawAngle = angle
            }

            // reset compteur de vol pour le prochain saut
            ticksAirborneCurrent = 0
        }

        // Appliquer la rotation lissée planifiée (si en cours)
        tickYawPlan()

        sumoWasOnGround = onGround
    }

    fun stop() {
        Movement.clearAll()
        intervals.forEach { it?.cancel() }
        intervals.clear()
        tickYawChange = 0f
        desiredPitch = null
        activeMovementType = null

        // reset sumo
        sumoJumpCounter = 0
        sumoInitialTurnRight = true
        sumoCircleTurnRight = false
        sumoWasOnGround = false
        ticksAirborneCurrent = 0
        lastAirTicks = 8
        queuedYawAngle = null

        // reset yaw plan
        yawPlanActive = false
        yawPlanTotalAngle = 0f
        yawPlanTicksTotal = 0
        yawPlanTickIndex = 0
        yawPlanLastEase = 0f
    }

    private fun maintainMovement() {
        when (activeMovementType) {
            Config.LobbyMovementType.FAST_FORWARD -> {
                if (!Movement.forward()) Movement.startForward()
                if (!Movement.sprinting()) Movement.startSprinting()
                val p = kira.mc.thePlayer
                if (p != null && p.onGround && !Movement.jumping()) Movement.startJumping()
            }
            Config.LobbyMovementType.SUMO -> {
                if (!Movement.forward()) Movement.startForward()
                if (!Movement.sprinting()) Movement.startSprinting()
            }
            else -> {}
        }
    }

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent) {
        if (!canActivateAndRunAnyMovement()) {
            if (activeMovementType != null) stop()
            return
        }

        maintainMovement()

        desiredPitch?.let { kira.mc.thePlayer!!.rotationPitch = it }
        kira.mc.thePlayer!!.rotationYaw += tickYawChange

        if (activeMovementType == Config.LobbyMovementType.SUMO) {
            sumoTick()
        }
    }
}
