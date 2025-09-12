package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.features.*
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

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
    private var retreating = false
    private var eatingGap = false
    private var firstSpeedTaken = false

    var tapping = false

    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var lastCloseStrafeSwitch = 0L
    private var closeStrafeNextAt = 0L
    private var longStrafeUntil = 0L
    private var longStrafeChance = 25

    private fun computeCloseStrafeDelay(distance: Float): Long {
        return when {
            distance < 2.0f -> RandomUtils.randomIntInRange(90, 160).toLong()
            distance < 2.8f -> RandomUtils.randomIntInRange(180, 250).toLong()
            else -> RandomUtils.randomIntInRange(220, 300).toLong()
        }
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

    private fun spawnSplash(damage: Int, delay: Int = 0) {
        TimeUtils.setTimeout({
            Movement.startForward()
            Movement.startSprinting()
            Movement.startJumping()
            
            TimeUtils.setTimeout({
                useSplashPotion(damage, false, false)
                
                TimeUtils.setTimeout({
                    Movement.stopJumping()
                }, RandomUtils.randomIntInRange(1500, 2000))
            }, RandomUtils.randomIntInRange(800, 1200))
        }, delay)
    }

    private fun retreatAndSplash(damage: Int, onComplete: () -> Unit) {
        retreating = true
        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(false)
        
        val opp = opponent()
        if (opp == null) {
            retreating = false
            return
        }
        
        val player = mc.thePlayer
        val oppX = opp.posX
        val oppZ = opp.posZ
        val playerX = player.posX
        val playerZ = player.posZ
        
        val dirX = playerX - oppX
        val dirZ = playerZ - oppZ
        val length = kotlin.math.sqrt(dirX * dirX + dirZ * dirZ)
        
        if (length > 0) {
            val normalizedX = dirX / length
            val normalizedZ = dirZ / length
            
            Mouse.setRunningAway(true)
            Movement.startForward()
            Movement.startSprinting()
            Movement.startJumping()
            
            TimeUtils.setTimeout({
                useSplashPotion(damage, false, false)
                
                TimeUtils.setTimeout({
                    Movement.stopForward()
                    Movement.stopSprinting()
                    Movement.stopJumping()
                    Mouse.setRunningAway(false)
                    retreating = false
                }, RandomUtils.randomIntInRange(1800, 2200))
                onComplete()
            }, RandomUtils.randomIntInRange(2000, 2500))
        } else {
            retreating = false
        }
    }

    override fun onGameStart() {
        gameStartAt = System.currentTimeMillis()
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        
        TimeUtils.setTimeout({
            spawnSplash(speedDamage)
            speedPotsLeft--
            lastSpeedUse = System.currentTimeMillis()
            firstSpeedTaken = true
            
            if (regenPotsLeft == 2) {
                spawnSplash(regenDamage, RandomUtils.randomIntInRange(1500, 2000))
                regenPotsLeft--
                lastRegenUse = System.currentTimeMillis()
            }
        }, RandomUtils.randomIntInRange(300, 600))
        
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(3000, 4000))
        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }

        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        lastStrafeSwitch = 0L
        lastCloseStrafeSwitch = 0L
        closeStrafeNextAt = 0L
        longStrafeUntil = 0L
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
        retreating = false
        eatingGap = false
        firstSpeedTaken = false

        strafeDir = 1
        lastStrafeSwitch = 0L
        lastCloseStrafeSwitch = 0L
        closeStrafeNextAt = 0L
        longStrafeUntil = 0L

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
            }
        }
    }

    override fun onTick() {
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) Movement.startSprinting()

            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())

            var hasSpeed = false
            for (effect in mc.thePlayer.activePotionEffects) {
                if (effect.effectName.lowercase().contains("speed")) hasSpeed = true
            }

            Mouse.startTracking()

            if (kira.config?.kiraHit == true && !retreating && !eatingGap) {
                Mouse.startLeftAC()
            } else {
                Mouse.stopLeftAC()
            }

            if (distance > 8.8f && firstSpeedTaken) {
                if (opponent() != null && opponent()!!.heldItem != null && opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (!Mouse.isRunningAway()) Movement.stopJumping()
                } else {
                    Movement.startJumping()
                }
            } else {
                Movement.stopJumping()
            }

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            if (distance < 0.7f || (distance < 1.4f && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping) {
                Movement.startForward()
            }

            if (distance < 1.5f && mc.thePlayer.heldItem != null &&
                !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword") &&
                !Mouse.isUsingPotion()) {
                Inventory.setInvItem("sword")
                Mouse.rClickUp()
            }

            if (!hasSpeed && speedPotsLeft > 0 &&
                System.currentTimeMillis() - lastSpeedUse > 15000 &&
                System.currentTimeMillis() - lastPotion > 3500) {
                
                if (speedPotsLeft > 0) {
                    retreatAndSplash(speedDamage) {
                        speedPotsLeft--
                        lastSpeedUse = System.currentTimeMillis()
                    }
                }
            }

            if (WorldUtils.blockInFront(mc.thePlayer, 3f, 1.5f) != Blocks.air) {
                Mouse.setRunningAway(false)
            }

            val now = System.currentTimeMillis()
            if (((distance > 3f && mc.thePlayer.health < 12) || mc.thePlayer.health < 9) &&
                combo < 2 && mc.thePlayer.health <= opponent()!!.health) {
                if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() &&
                    !eatingGap && now - lastPotion > 3500) {
                    
                    if (gapsLeft > 0 && now - lastGap > 4000) {
                        eatingGap = true
                        Mouse.stopLeftAC()
                        useGap(distance, distance < 2f, EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))
                        gapsLeft--
                        
                        TimeUtils.setTimeout({
                            eatingGap = false
                        }, RandomUtils.randomIntInRange(3000, 4000))
                    } else if (regenPotsLeft > 0 && now - gameStartAt >= 120000 && now - lastRegenUse > 3500) {
                        retreatAndSplash(regenDamage) {
                            regenPotsLeft--
                            lastRegenUse = System.currentTimeMillis()
                        }
                    }
                }
            }

            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown &&
                !eatingGap && System.currentTimeMillis() - lastGap > 2500) {

                if ((distance in 5.7f..6.5f || distance in 9.0f..9.5f) &&
                    !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                    useRod()
                } else if ((EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && distance in 3.5f..30f) ||
                           (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))) {
                    if (distance > 10f && shotsFired < maxArrows && System.currentTimeMillis() - lastPotion > 5000) {
                        clear = true
                        useBow(distance) { shotsFired++ }
                    } else {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    }
                } else {
                    if (opponent()!!.isInvisibleToPlayer(mc.thePlayer)) {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    } else if (EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    } else {
                        val nowMs = System.currentTimeMillis()
                        if (distance < 3.8f) {
                            // Combat rapproché : strafes rapides + long strafes imprévisibles
                            if (shouldStartLongStrafe(distance, nowMs)) {
                                longStrafeUntil = nowMs + RandomUtils.randomIntInRange(1200, 2500)
                                strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                                lastCloseStrafeSwitch = nowMs
                                closeStrafeNextAt = longStrafeUntil + RandomUtils.randomIntInRange(100, 300)
                            } else if (longStrafeUntil > nowMs) {
                                // En cours de long strafe, ne pas changer de direction
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
                            // Distance moyenne : strafes longs uniquement
                            closeStrafeNextAt = 0L
                            if (distance < 5.5f && nowMs - lastStrafeSwitch > RandomUtils.randomIntInRange(1500, 2200)) {
                                strafeDir = -strafeDir
                                lastStrafeSwitch = nowMs
                            } else if (distance >= 5.5f && nowMs - lastStrafeSwitch > RandomUtils.randomIntInRange(2000, 3000)) {
                                strafeDir = -strafeDir
                                lastStrafeSwitch = nowMs
                            }
                            val weight = 6
                            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                            randomStrafe = false
                        } else {
                            closeStrafeNextAt = 0L
                            if (distance < 6.5f && nowMs - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                                strafeDir = -strafeDir
                                lastStrafeSwitch = nowMs
                            }
                            val weight = if (distance < 4f) 7 else 5
                            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                            randomStrafe = (distance >= 8f && opponent()!!.heldItem != null &&
                                (opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow") ||
                                 opponent()!!.heldItem.unlocalizedName.lowercase().contains("rod")))
                            if (randomStrafe && distance < 15f) Movement.stopJumping()
                        }
                    }
                }
            }

            if (WorldUtils.blockInPath(mc.thePlayer, RandomUtils.randomIntInRange(3, 7), 1f) == Blocks.fire) {
                Movement.singleJump(RandomUtils.randomIntInRange(200, 400))
            }

            handle(clear, randomStrafe, movePriority)
        }
    }
}
