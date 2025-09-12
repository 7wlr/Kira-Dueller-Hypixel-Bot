package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.util.BlockPos
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*

object LobbyMovement {

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

        if (activeMovementType == typeToStart && typeToStart != Config.LobbyMovementType.FAST_FORWARD) {
            return
        }

        if (activeMovementType == typeToStart && typeToStart == Config.LobbyMovementType.FAST_FORWARD) {
            if (kira.mc.thePlayer?.onGround == true && !Movement.jumping()) {
                Movement.startJumping()
            }
            return
        }

        stop()
        activeMovementType = typeToStart
        internalStartMovementLogic(activeMovementType!!)
    }

    private fun internalStartMovementLogic(type: Config.LobbyMovementType) {
        when (type) {
            Config.LobbyMovementType.RANDOM_MOVES -> {
                val availableMovements = Config.LobbyMovementType.values().filter {
                    it != Config.LobbyMovementType.RANDOM_MOVES
                }

                if (availableMovements.isNotEmpty()) {
                    val randomChoice = availableMovements[RandomUtils.randomIntInRange(0, availableMovements.size - 1)]
                    activeMovementType = randomChoice
                    internalStartMovementLogic(randomChoice)
                } else {
                    activeMovementType = Config.LobbyMovementType.STRAFE_WALK
                    circleStrafeInternal()
                }
            }

            Config.LobbyMovementType.STRAFE_WALK -> circleStrafeInternal()
            Config.LobbyMovementType.WALKER -> walkerInternal()
            Config.LobbyMovementType.SLOW_DRIFT -> shiftWalkerInternal()
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
    }

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return
        desiredPitch = RandomUtils.randomDoubleInRange(-5.0, 10.0).toFloat()

        // Turn 45° to a random side when spawning
        val turnRight = RandomUtils.randomBool()
        player.rotationYaw += if (turnRight) 45f else -45f

        // Start moving forward and circle around the platform
        Movement.startForward()
        Movement.startSprinting()
        tickYawChange = if (turnRight) -RandomUtils.randomDoubleInRange(2.0, 4.0).toFloat()
            else RandomUtils.randomDoubleInRange(2.0, 4.0).toFloat()

        // Repeatedly jump while slightly turning in the opposite direction
        intervals.add(TimeUtils.setInterval(fun() {
            val p = kira.mc.thePlayer ?: return@setInterval
            if (p.onGround) Movement.singleJump(RandomUtils.randomIntInRange(80, 150))
        }, RandomUtils.randomIntInRange(300, 500), RandomUtils.randomIntInRange(300, 500)))
    }

    private fun circleStrafeInternal() {
        Movement.startForward()
        if (RandomUtils.randomBool()) Movement.startSprinting()
        desiredPitch = 0f
        val circleYawSpeed = RandomUtils.randomDoubleInRange(3.5, 6.5).toFloat()
        tickYawChange = if (RandomUtils.randomBool()) circleYawSpeed else -circleYawSpeed

        var strafingLeft = RandomUtils.randomBool()
        if (strafingLeft) Movement.startLeft() else Movement.startRight()

        intervals.add(TimeUtils.setInterval(fun() {
            if (kira.mc.thePlayer == null || activeMovementType != Config.LobbyMovementType.STRAFE_WALK) return@setInterval
            strafingLeft = !strafingLeft
            if (strafingLeft) { Movement.stopRight(); Movement.startLeft() } else { Movement.stopLeft(); Movement.startRight() }
            if (RandomUtils.randomIntInRange(0, 2) == 0) tickYawChange *= -1
        }, RandomUtils.randomIntInRange(1500, 3500), RandomUtils.randomIntInRange(1500, 3500)))

        intervals.add(TimeUtils.setInterval(fun() {
            if (kira.mc.thePlayer?.onGround == true && activeMovementType == Config.LobbyMovementType.STRAFE_WALK) {
                if (RandomUtils.randomIntInRange(0, 2) == 0) Movement.singleJump(RandomUtils.randomIntInRange(70, 130))
            }
        }, RandomUtils.randomIntInRange(1000, 2500), RandomUtils.randomIntInRange(1000, 2500)))
    }

    private fun walkerInternal() {
        Movement.startForward()
        if (RandomUtils.randomBool()) Movement.startSprinting()
        desiredPitch = RandomUtils.randomDoubleInRange(-10.0, 10.0).toFloat()
        tickYawChange = 0f
        var preferredSideIsLeft = RandomUtils.randomBool()
        val wallCheckDistance = 1.0f
        val turnStrength = RandomUtils.randomDoubleInRange(6.0, 12.0).toFloat()
        val sharpTurnStrength = RandomUtils.randomDoubleInRange(20.0, 30.0).toFloat()

        intervals.add(TimeUtils.setInterval(fun() {
            val player = kira.mc.thePlayer ?: return@setInterval
            if (activeMovementType != Config.LobbyMovementType.WALKER) return@setInterval

            if (!WorldUtils.airInFront(player, 0.6f)) {
                tickYawChange = (if (preferredSideIsLeft) 1 else -1) * sharpTurnStrength
                Movement.clearLeftRight(); return@setInterval
            }
            val (wallOnPreferredSide, wallOnOppositeSide) = if (preferredSideIsLeft) Pair(!WorldUtils.airOnLeft(player, wallCheckDistance), !WorldUtils.airOnRight(player, wallCheckDistance)) else Pair(!WorldUtils.airOnRight(player, wallCheckDistance), !WorldUtils.airOnLeft(player, wallCheckDistance))
            if (wallOnPreferredSide) {
                Movement.clearLeftRight()
                tickYawChange = (if (preferredSideIsLeft) 1 else -1) * RandomUtils.randomDoubleInRange(1.0, 3.0).toFloat()
                val veryCloseDist = 0.3f
                val isVeryClose = if (preferredSideIsLeft) !WorldUtils.airOnLeft(player, veryCloseDist) else !WorldUtils.airOnRight(player, veryCloseDist)
                if (isVeryClose) tickYawChange = (if (preferredSideIsLeft) 1 else -1) * (turnStrength + 3f)
            } else {
                tickYawChange = (if (preferredSideIsLeft) -1 else 1) * turnStrength
                if (wallOnOppositeSide) preferredSideIsLeft = !preferredSideIsLeft
                else tickYawChange = RandomUtils.randomDoubleInRange(-sharpTurnStrength / 1.5, sharpTurnStrength / 1.5).toFloat()
            }
        }, RandomUtils.randomIntInRange(70, 180), RandomUtils.randomIntInRange(70, 180)))
    }

    private fun shiftWalkerInternal() {
        Movement.startSneaking()
        Movement.startForward()
        desiredPitch = RandomUtils.randomDoubleInRange(45.0, 75.0).toFloat()
        tickYawChange = 0f
        var currentAction = "walking_slow"
        var targetYaw = kira.mc.thePlayer?.rotationYaw ?: 0f

        intervals.add(TimeUtils.setInterval(fun() {
            val player = kira.mc.thePlayer ?: return@setInterval
            if (activeMovementType != Config.LobbyMovementType.SLOW_DRIFT) return@setInterval

            desiredPitch = RandomUtils.randomDoubleInRange(40.0, 80.0).toFloat()

            val lookVec = player.lookVec
            val checkX = player.posX + lookVec.xCoord * 0.8
            val checkZ = player.posZ + lookVec.zCoord * 0.8
            val checkY = player.posY - 1
            val blockAheadBelow = kira.mc.theWorld.getBlockState(BlockPos(checkX, checkY, checkZ)).block

            if (blockAheadBelow.material.isReplaceable || !WorldUtils.airInFront(player, 0.6f)) {
                val deltaYaw = RandomUtils.randomDoubleInRange(60.0, 120.0) * (if (RandomUtils.randomBool()) 1 else -1)
                targetYaw += deltaYaw.toFloat()
                Movement.stopForward()
                TimeUtils.setTimeout(fun() {
                    if (activeMovementType == Config.LobbyMovementType.SLOW_DRIFT && currentAction == "walking_slow")
                        Movement.startForward()
                }, 600)
                return@setInterval
            }

            val actionChoice = RandomUtils.randomIntInRange(0, 10)
            when {
                actionChoice < 8 -> {
                    if (currentAction != "walking_slow" || !Movement.forward()) {
                        Movement.clearLeftRight(); Movement.stopBackward(); Movement.startForward(); currentAction = "walking_slow"
                    }

                    val yawWiggle = RandomUtils.randomDoubleInRange(-5.0, 5.0).toFloat()
                    targetYaw += yawWiggle
                }
                else -> {
                    if (currentAction != "shuffling") {
                        Movement.stopForward(); currentAction = "shuffling"
                    }
                    val dir = if (RandomUtils.randomBool()) Movement::startLeft else Movement::startRight
                    val stopDir = if (dir == Movement::startLeft) Movement::stopLeft else Movement::stopRight
                    dir.invoke()
                    TimeUtils.setTimeout({ if (activeMovementType == Config.LobbyMovementType.SLOW_DRIFT) stopDir.invoke() }, RandomUtils.randomIntInRange(250, 550))
                }
            }
        }, 1200, 1200))

        intervals.add(TimeUtils.setInterval({
            val player = kira.mc.thePlayer ?: return@setInterval
            if (activeMovementType != Config.LobbyMovementType.SLOW_DRIFT) return@setInterval

            val currentYaw = player.rotationYaw
            val diff = ((targetYaw - currentYaw + 540) % 360) - 180
            tickYawChange = (diff * 0.15f).coerceIn(-4f, 4f)
        }, 50, 50))
    }



    fun stop() {
        Movement.clearAll()
        intervals.forEach { it?.cancel() }
        intervals.clear()
        tickYawChange = 0f
        desiredPitch = null
        activeMovementType = null
    }

    private fun maintainMovement() {
        when (activeMovementType) {
            Config.LobbyMovementType.STRAFE_WALK -> {
                if (!Movement.forward()) Movement.startForward()
                if (!Movement.left() && !Movement.right()) {
                    if (RandomUtils.randomBool()) Movement.startLeft() else Movement.startRight()
                }
            }
            Config.LobbyMovementType.WALKER -> {
                if (!Movement.forward()) Movement.startForward()
            }
            Config.LobbyMovementType.SLOW_DRIFT -> {
                if (!Movement.sneaking()) Movement.startSneaking()
                if (!Movement.forward() && !Movement.left() && !Movement.right()) Movement.startForward()
            }
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

