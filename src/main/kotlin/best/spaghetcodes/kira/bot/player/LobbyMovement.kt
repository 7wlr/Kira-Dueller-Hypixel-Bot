package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*

// Mouvement de lobby : FAST_FORWARD (inchangé) et SUMO (cercle régulier)

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

    // Paramètres faciles à tuner
    private const val SUMO_INITIAL_ANGLE_DEG = 45f        // pivot initial au spawn (fixe)
    private const val SUMO_STEP_ANGLE_DEG = 60f           // <<< angle ajouté à CHAQUE saut à partir du 2e (augmenter => cercle plus petit)

    // État Sumo
    private var sumoInitialTurnRight = true               // sens du pivot initial
    private var sumoCircleTurnRight = false               // sens du cercle (opposé au pivot initial)
    private var sumoJumpCounter = 0                       // nb d’impulsions déclenchées (1er saut = 1)
    private var sumoWasOnGround = false                   // pour détecter l’atterrissage

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return

        // petit pitch naturel
        desiredPitch = RandomUtils.randomDoubleInRange(-5.0, 10.0).toFloat()

        // 1) rotation initiale ±45°
        sumoInitialTurnRight = RandomUtils.randomBool()
        player.rotationYaw += if (sumoInitialTurnRight) SUMO_INITIAL_ANGLE_DEG else -SUMO_INITIAL_ANGLE_DEG

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
            Movement.singleJump(RandomUtils.randomIntInRange(80, 150))
            sumoJumpCounter = 1 // on compte le 1er saut, mais on NE tourne PAS ici
        }

        // NB : on gère la suite à l’atterrissage dans onClientTick()
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
            Movement.singleJump(RandomUtils.randomIntInRange(80, 150))
            sumoJumpCounter++

            // Règle demandée :
            // - 1er saut : PAS de rotation (déjà fait au start)
            // - 2e saut : on applique la première rotation
            // - 3e, 4e, ... : on applique la rotation à CHAQUE saut
            if (sumoJumpCounter >= 2) {
                p.rotationYaw += if (sumoCircleTurnRight) SUMO_STEP_ANGLE_DEG else -SUMO_STEP_ANGLE_DEG
            }
        }

        sumoWasOnGround = p.onGround
    }

    fun stop() {
        Movement.clearAll()
        intervals.forEach { it?.cancel() }
        intervals.clear()
        tickYawChange = 0f
        desiredPitch = null
        activeMovementType = null

        // reset état sumo
        sumoJumpCounter = 0
        sumoInitialTurnRight = true
        sumoCircleTurnRight = false
        sumoWasOnGround = false
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

        // “entretien” commun
        maintainMovement()

        // appli pitch/yaw
        desiredPitch?.let { kira.mc.thePlayer!!.rotationPitch = it }
        kira.mc.thePlayer!!.rotationYaw += tickYawChange

        // boucle sumo par tick
        if (activeMovementType == Config.LobbyMovementType.SUMO) {
            sumoTick()
        }
    }
}
