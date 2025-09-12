package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*

object LobbyMovement {

    // --- commun ---
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

        // ex : demi-tour toutes les 7s
        intervals.add(TimeUtils.setInterval(fun() {
            val p = kira.mc.thePlayer ?: return@setInterval
            p.rotationYaw += 180f
        }, 7000, 7000))
    }

    // =======================
    //         SUMO
    // =======================

    // réglages
    private const val SUMO_INITIAL_ANGLE_DEG = 45f         // pivot au spawn
    private const val SUMO_PER_JUMP_ANGLE_DEG = 18f        // << tourne à CHAQUE saut de cet angle
    private const val SUMO_JUMP_HOLD_TICKS = 2             // durée d’appui saut en ticks

    // état
    private var sumoInitialTurnRight = true                // sens du pivot initial
    private var sumoCircleTurnRight = false                // sens du cercle = opposé
    private var sumoWasOnGround = false                    // détection atterrissage
    private var sumoQueuedJumpTicks = 0                    // appui jump en cours (ticks)

    private fun queueJumpTap(ticks: Int = SUMO_JUMP_HOLD_TICKS) {
        // Appui synchronisé au tick
        if (!Movement.jumping()) Movement.startJumping()
        sumoQueuedJumpTicks = maxOf(sumoQueuedJumpTicks, ticks)

        // Rotation à CHAQUE impulsion
        val p = kira.mc.thePlayer ?: return
        p.rotationYaw += if (sumoCircleTurnRight) SUMO_PER_JUMP_ANGLE_DEG else -SUMO_PER_JUMP_ANGLE_DEG
    }

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return

        // pitch naturel
        desiredPitch = RandomUtils.randomDoubleInRange(-5.0, 10.0).toFloat()

        // 1) pivot initial ±45°
        sumoInitialTurnRight = RandomUtils.randomBool()
        player.rotationYaw += if (sumoInitialTurnRight) SUMO_INITIAL_ANGLE_DEG else -SUMO_INITIAL_ANGLE_DEG

        // 2) sens du cercle = opposé au pivot initial
        sumoCircleTurnRight = !sumoInitialTurnRight

        // 3) démarrer l’allure
        Movement.startForward()
        Movement.startSprinting()
        tickYawChange = 0f

        // 4) init états
        sumoWasOnGround = player.onGround
        sumoQueuedJumpTicks = 0

        // 5) premier saut immédiat si possible (et on applique déjà la petite rotation)
        if (player.onGround) {
            queueJumpTap()
        }
    }

    private fun sumoTick() {
        val p = kira.mc.thePlayer ?: return

        // maintenir la marche/sprint
        if (!Movement.forward()) Movement.startForward()
        if (!Movement.sprinting()) Movement.startSprinting()

        // Détection atterrissage (false -> true) : on relance un saut + petite rotation
        val justLanded = p.onGround && !sumoWasOnGround
        if (justLanded) {
            queueJumpTap()
        }

        // Gestion de l’appui de saut (en ticks pour ne pas rater l’input)
        if (sumoQueuedJumpTicks > 0) {
            sumoQueuedJumpTicks--
            if (sumoQueuedJumpTicks == 0) {
                Movement.stopJumping()
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

        // reset sumo
        sumoWasOnGround = false
        sumoQueuedJumpTicks = 0
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
