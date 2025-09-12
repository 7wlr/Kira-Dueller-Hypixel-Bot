package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.Timer

object LobbyMovement {

    private var tickYawChange = 0f
    private var initialYaw = 0f
    private var intervals: ArrayList<Timer?> = ArrayList()

    fun sumo() {
        /*val opt = RandomUtils.randomIntInRange(0, 1)
        when (opt) {
            0 -> sumo1()
            1 -> twerk()
        }*/
        sumo1()
    }

    fun generic() {
        if (kira.mc.thePlayer != null) {
            Movement.startForward()
            Movement.startSprinting()
            initialYaw = kira.mc.thePlayer.rotationYaw

            intervals.add(TimeUtils.setInterval(
                fun () {
                    if (RandomUtils.randomBool()) {
                        Movement.singleJump(RandomUtils.randomIntInRange(120, 200))
                    } else {
                        if (Movement.jumping()) {
                            Movement.stopJumping()
                        } else {
                            Movement.startJumping()
                        }
                    }
                },
                RandomUtils.randomIntInRange(400, 800),
                RandomUtils.randomIntInRange(900, 1800)
            ))

            intervals.add(TimeUtils.setInterval(
                fun () {
                    val player = kira.mc.thePlayer
                    if (player != null && !WorldUtils.airInFront(player, 1f)) {
                        player.rotationYaw += 180f
                    }
                },
                0,
                RandomUtils.randomIntInRange(50, 100)
            ))
        }
    }

    fun stop() {
        Movement.clearAll()
        tickYawChange = 0f
        intervals.forEach { it?.cancel() }
    }

    private fun sumo1() {
        if (kira.mc.thePlayer == null) return

        var left = RandomUtils.randomBool()
        var base = RandomUtils.randomDoubleInRange(2.0, 3.5).toFloat()
        base = if (left) -base else base
        tickYawChange = base

        Movement.startForward()
        Movement.startSprinting()
        if (left) {
            Movement.startLeft()
        } else {
            Movement.startRight()
        }

        intervals.add(TimeUtils.setInterval({
            val p = kira.mc.thePlayer
            if (p != null) {
                tickYawChange = base + RandomUtils.randomDoubleInRange(-0.3, 0.3).toFloat()

                val nearVoid = WorldUtils.airInFront(p, 2f) ||
                        (left && WorldUtils.airOnLeft(p, 1.5f)) ||
                        (!left && WorldUtils.airOnRight(p, 1.5f))
                if (nearVoid) {
                    Movement.swapLeftRight()
                    left = !left
                    base = -base
                }
            }
        }, 0, RandomUtils.randomIntInRange(80, 150)))

        intervals.add(TimeUtils.setInterval({
            if (RandomUtils.randomBool()) {
                Movement.singleJump(RandomUtils.randomIntInRange(80, 160))
            }
        }, RandomUtils.randomIntInRange(600, 1000), RandomUtils.randomIntInRange(1500, 2500)))

        intervals.add(TimeUtils.setInterval({
            Movement.stopForward()
            Movement.clearLeftRight()
            TimeUtils.setTimeout({
                Movement.startForward()
                if (left) {
                    Movement.startLeft()
                } else {
                    Movement.startRight()
                }
            }, RandomUtils.randomIntInRange(150, 350))
        }, RandomUtils.randomIntInRange(4000, 8000), RandomUtils.randomIntInRange(4000, 8000)))

        intervals.add(TimeUtils.setInterval({
            Movement.swapLeftRight()
            left = !left
            base = -base
        }, RandomUtils.randomIntInRange(5000, 9000), RandomUtils.randomIntInRange(5000, 9000)))

        intervals.add(TimeUtils.setInterval({
            val p = kira.mc.thePlayer
            if (p != null) {
                p.rotationPitch += RandomUtils.randomDoubleInRange(-1.0, 1.0).toFloat()
            }
        }, 0, RandomUtils.randomIntInRange(500, 900)))
    }

    private fun twerk() {
        intervals.add(TimeUtils.setInterval(
            fun () {
                if (Movement.sneaking()) {
                    Movement.stopSneaking()
                } else {
                    Movement.startSneaking()
                }
        }, RandomUtils.randomIntInRange(500, 900), RandomUtils.randomIntInRange(200, 500)))
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onTick(event: ClientTickEvent) {
        if (kira.bot?.toggled() == true && tickYawChange != 0f && kira.mc.thePlayer != null && StateManager.state != StateManager.States.PLAYING) {
            kira.mc.thePlayer.rotationYaw += tickYawChange
        }
    }
}
