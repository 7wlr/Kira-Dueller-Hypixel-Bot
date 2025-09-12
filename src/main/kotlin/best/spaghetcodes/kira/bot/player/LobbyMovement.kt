package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*

object LobbyMovement {

    private var tickYawChange = 0f
    private var desiredPitch: Float? = null
    private var intervals: ArrayList<Timer?> = ArrayList()
    private var activeMovementType: Config.LobbyMovementType? = null
    private var sumoTurnRight = true
    private var sumoJumpCounter = 0

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

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return
        desiredPitch = RandomUtils.randomDoubleInRange(-5.0, 10.0).toFloat()

        // Turn 45° to a random side when spawning
        sumoTurnRight = RandomUtils.randomBool()
        player.rotationYaw += if (sumoTurnRight) 45f else -45f

        // Start moving forward and circle around the platform with wider turns
        Movement.startForward()
        Movement.startSprinting()
        tickYawChange = 0f
        sumoJumpCounter = 0

        // Repeatedly jump and only turn every second jump
        intervals.add(TimeUtils.setInterval(fun() {
            val p = kira.mc.thePlayer ?: return@setInterval
            if (p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(80, 150))
                sumoJumpCounter++
                if (sumoJumpCounter % 2 == 0) {
                    p.rotationYaw += if (sumoTurnRight) 45f else -45f
                }
            }
        }, RandomUtils.randomIntInRange(300, 500), RandomUtils.randomIntInRange(300, 500)))
    }




    fun stop() {
        Movement.clearAll()
        intervals.forEach { it?.cancel() }
        intervals.clear()
        tickYawChange = 0f
        desiredPitch = null
        activeMovementType = null
        sumoJumpCounter = 0
        sumoTurnRight = true
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
    }
}

