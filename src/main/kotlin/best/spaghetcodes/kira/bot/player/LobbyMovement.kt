package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

// Mouvement de lobby : FAST_FORWARD (inchangé) et SUMO (cercle régulier et HUMAIN)

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
            // petit bonus pour FAST_FORWARD : s’assurer que ça saute si on est au sol
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

    // === FAST_FORWARD inchangé (exemple générique) ===
    private fun runForwardPreGameInternal() {
        Movement.startForward()
        Movement.startSprinting()
        Movement.startJumping()
        tickYawChange = 0f
        desiredPitch = 0f
        // Ex : demi-tour toutes les 7 s
        intervals.add(TimeUtils.setInterval(fun() {
            val p = kira.mc.thePlayer ?: return@setInterval
            p.rotationYaw += 180f
        }, 7000, 7000))
    }

    // =========================
    //         SUMO
    // =========================

    // —— Réglages cercle ——
    private const val SUMO_INITIAL_ANGLE_DEG = 45f      // pivot initial au spawn
    private const val SUMO_STEP_ANGLE_DEG = 60f         // angle ajouté à chaque saut (à partir du 2e)
    private const val SUMO_STEP_JITTER_DEG = 4f         // petit aléa pour casser l’effet robot (±)
    private const val SUMO_JUMP_DELAY_MIN = 80          // ms
    private const val SUMO_JUMP_DELAY_MAX = 150         // ms

    // —— Réglages lissage (humain) ——
    private const val SMOOTH_INITIAL_MIN_TICKS = 4      // durée du pivot initial lissé
    private const val SMOOTH_INITIAL_MAX_TICKS = 7
    private const val SMOOTH_STEP_MIN_TICKS = 5         // durée des rotations à chaque saut
    private const val SMOOTH_STEP_MAX_TICKS = 9

    // — État Sumo —
    private var sumoInitialTurnRight = true             // sens du pivot initial
    private var sumoCircleTurnRight = false             // sens du cercle (opposé au pivot initial)
    private var sumoJumpCounter = 0                     // nb d’impulsions déclenchées (1er saut = 1)
    private var sumoWasOnGround = false                 // détection atterrissage

    // === Interpolation de yaw (humain) ===
    // On interpole les rotations au lieu de les appliquer instantanément
    private var smoothYawActive = false
    private var smoothYawDelta = 0f         // delta total à appliquer (°)
    private var smoothYawApplied = 0f       // combien déjà appliqué (°)
    private var smoothYawDuration = 0       // ticks total
    private var smoothYawElapsed = 0        // ticks écoulés

    private fun easeInOutSine(t: Float): Float {
        // t in [0..1] → ease in-out (doux, “humain”)
        return (-(cos(PI * t) - 1.0) / 2.0).toFloat()
    }

    private fun scheduleSmoothRotation(rawDeltaDeg: Float, minTicks: Int, maxTicks: Int) {
        val p = kira.mc.thePlayer ?: return

        // Jitter léger de l'angle pour casser l'uniformité
        val jitter = RandomUtils.randomDoubleInRange(-SUMO_STEP_JITTER_DEG.toDouble(), SUMO_STEP_JITTER_DEG.toDouble()).toFloat()
        val delta = rawDeltaDeg + jitter
        val dur = max(1, RandomUtils.randomIntInRange(minTicks, maxTicks))

        if (smoothYawActive) {
            // On “recolle” la fin de la rotation courante avec la nouvelle (évite un snap entre deux rotations)
            val remaining = smoothYawDelta - smoothYawApplied
            smoothYawDelta = remaining + delta
            smoothYawApplied = 0f
            smoothYawDuration = dur
            smoothYawElapsed = 0
        } else {
            smoothYawActive = true
            smoothYawDelta = delta
            smoothYawApplied = 0f
            smoothYawDuration = dur
            smoothYawElapsed = 0
        }
        // Rien d’autre ici : l’application se fait par ticks dans onClientTick()
    }

    private fun applySmoothYawTick() {
        if (!smoothYawActive) return
        val p = kira.mc.thePlayer ?: return

        smoothYawElapsed++
        val t = min(1f, smoothYawElapsed.toFloat() / smoothYawDuration.toFloat())
        val eased = easeInOutSine(t)
        val targetDelta = smoothYawDelta * eased
        val step = targetDelta - smoothYawApplied

        p.rotationYaw += step
        smoothYawApplied += step

        if (smoothYawElapsed >= smoothYawDuration) {
            smoothYawActive = false
            // sécurité : aligner pile si jamais floating point
            val correction = smoothYawDelta - smoothYawApplied
            p.rotationYaw += correction
            smoothYawApplied = smoothYawDelta
        }
    }

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return

        // pitch naturel, mais pas “figé”
        desiredPitch = RandomUtils.randomDoubleInRange(-5.0, 10.0).toFloat()

        // 1) rotation initiale ±45° — lissée pour paraître humaine
        sumoInitialTurnRight = RandomUtils.randomBool()
        val initialDelta = if (sumoInitialTurnRight) SUMO_INITIAL_ANGLE_DEG else -SUMO_INITIAL_ANGLE_DEG
        scheduleSmoothRotation(initialDelta, SMOOTH_INITIAL_MIN_TICKS, SMOOTH_INITIAL_MAX_TICKS)

        // 2) sens du cercle = opposé au pivot initial
        sumoCircleTurnRight = !sumoInitialTurnRight

        // 3) démarrer l’allure
        Movement.startForward()
        Movement.startSprinting()
        tickYawChange = 0f
        sumoJumpCounter = 0
        sumoWasOnGround = player.onGround

        // 4) 1er saut IMMÉDIAT, SANS rotation supplémentaire
        if (player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(SUMO_JUMP_DELAY_MIN, SUMO_JUMP_DELAY_MAX))
            sumoJumpCounter = 1 // on compte le 1er saut, mais on NE tourne PAS ici
        }
    }

    private fun sumoTick() {
        val p = kira.mc.thePlayer ?: return

        // sécurité : maintenir la marche/sprint
        if (!Movement.forward()) Movement.startForward()
        if (!Movement.sprinting()) Movement.startSprinting()

        // Détection d’atterrissage : onGround vient de passer de false -> true
        val justLanded = p.onGround && !sumoWasOnGround
        if (justLanded) {
            // On relance UN saut (léger délai pour fiabiliser)
            Movement.singleJump(RandomUtils.randomIntInRange(SUMO_JUMP_DELAY_MIN, SUMO_JUMP_DELAY_MAX))
            sumoJumpCounter++

            // Règle :
            // - 1er saut : PAS de rotation (déjà pivot initial)
            // - 2e saut : on applique la première rotation
            // - 3e, 4e, ... : rotation à CHAQUE saut
            if (sumoJumpCounter >= 2) {
                val step = if (sumoCircleTurnRight) SUMO_STEP_ANGLE_DEG else -SUMO_STEP_ANGLE_DEG
                scheduleSmoothRotation(step, SMOOTH_STEP_MIN_TICKS, SMOOTH_STEP_MAX_TICKS)
            }
        }

        // Applique la rotation lissée (si présente)
        applySmoothYawTick()

        sumoWasOnGround = p.onGround
    }

    fun stop() {
        Movement.clearAll()
        intervals.forEach { it?.cancel() }
        intervals.clear()
        tickYawChange = 0f
        desiredPitch = null
        activeMovementType = null

        // reset état sumo + lissage
        sumoJumpCounter = 0
        sumoInitialTurnRight = true
        sumoCircleTurnRight = false
        sumoWasOnGround = false

        smoothYawActive = false
        smoothYawDelta = 0f
        smoothYawApplied = 0f
        smoothYawDuration = 0
        smoothYawElapsed = 0
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

        // entretien commun
        maintainMovement()

        // pitch + éventuel yaw par tick
        desiredPitch?.let { kira.mc.thePlayer!!.rotationPitch = it }
        kira.mc.thePlayer!!.rotationYaw += tickYawChange

        // boucle sumo par tick
        if (activeMovementType == Config.LobbyMovementType.SUMO) {
            sumoTick()
        }
    }
}
