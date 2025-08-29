package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Gap
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Potion
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max

class Combo : BotBase("/play duels_combo_duel"), MovePriority, Gap, Potion {

    override fun getName(): String = "Combo"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.combo_duel_wins",
                "losses" to "player.stats.Duels.combo_duel_losses",
                "ws" to "player.stats.Duels.current_combo_winstreak",
            )
        )
    }

    // --------- Ouverture (Potion -> Gapple enchaînés) ----------
    private var openingPhase = true
    private var openingScheduled = false

    // --------- Items & cooldowns ----------
    private var strengthPots = 2
    override var lastPotion = 0L

    private var gaps = 32
    override var lastGap = 0L

    private var pearls = 5
    private var lastPearl = 0L

    private var tapping = false

    // --------- Armure (simple : équiper si manquant) ----------
    enum class ArmorEnum { BOOTS, LEGGINGS, CHESTPLATE, HELMET }
    private var armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

    // --------- Strafe “Classic-like” ----------
    private var prevDistance = -1f
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1
    private var stagnantSince = 0L

    // Close-range state machine
    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    // AD-tapping (offensif type 1)
    private var adTapActive = false
    private var adToggleAt = 0L

    // Centre “virtuel” pour recentrer quand bord/obstacle
    private var centerX = 0.0
    private var centerZ = 0.0

    // --------- Verrou de consommation (anti-switch) ----------
    private var consumingUntil = 0L
    private var consumingType = 0 // 0=none, 1=potion, 2=gap

    // --------- Saut long-range ----------
    private var lastFarJumpAt = 0L

    // --------- Run-back mid-combo (défensif) ----------
    private var incomingStreak = 0
    private var lastHurtStamp = 0L
    private var prevHurtTime = 0

    // --------- QuickPearl après gap (guide) ----------
    private var quickPearlQueued = false
    private var quickPearlAt = 0L
    private val quickPearlMinDist = 6.0f
    private val quickPearlMaxDist = 12.0f

    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        mc.thePlayer?.let {
            centerX = it.posX
            centerZ = it.posZ
        }

        openingPhase = true
        openingScheduled = false

        // Strafes init
        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        stagnantSince = 0L
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L
        adTapActive = false
        adToggleAt = 0L
        tapping = false

        consumingUntil = 0L
        consumingType = 0
        lastFarJumpAt = 0L

        incomingStreak = 0
        lastHurtStamp = 0L
        prevHurtTime = 0

        quickPearlQueued = false
        quickPearlAt = 0L

        strengthPots = max(0, strengthPots)
        gaps = max(0, gaps)
        pearls = max(0, pearls)
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false

            strengthPots = 2
            lastPotion = 0L

            gaps = 32
            lastGap = 0L

            pearls = 5
            lastPearl = 0L

            armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)
            openingPhase = false
            openingScheduled = false
            Mouse.stopTracking()
            consumingUntil = 0L
            consumingType = 0

            incomingStreak = 0
            prevHurtTime = 0
            quickPearlQueued = false
        }, RandomUtils.randomIntInRange(100, 300))
    }

    // --- Forcer la boisson de force : sélection + maintien clic droit ---
    private fun forceDrinkStrengthPotion(): Int {
        val p = mc.thePlayer ?: return -1
        if (p.isPotionActive(net.minecraft.potion.Potion.damageBoost) || strengthPots <= 0) return -1

        val selected = Inventory.setInvItem("potion")
        if (!selected) return -1

        val hold = RandomUtils.randomIntInRange(1520, 1720) // ~32 ticks
        val now = System.currentTimeMillis()
        consumingType = 1
        consumingUntil = now + hold + 140
        Mouse.rClick(hold)

        lastPotion = now
        strengthPots = max(0, strengthPots - 1)

        TimeUtils.setTimeout({
            if (System.currentTimeMillis() >= consumingUntil && consumingType == 1) {
                consumingUntil = 0L
                consumingType = 0
            }
        }, hold + 160)

        return hold
    }

    // --- Forcer le manger de gapple : sélection + maintien clic droit ---
    private fun forceEatGapple(): Int {
        val p = mc.thePlayer ?: return -1
        if (gaps <= 0) return -1

        val selected =
            Inventory.setInvItem("gap") ||
            Inventory.setInvItem("gapple") ||
            Inventory.setInvItem("apple") ||
            Inventory.setInvItem("golden_apple")

        if (!selected) return -1

        val hold = RandomUtils.randomIntInRange(1500, 1700) // ~32 ticks
        val now = System.currentTimeMillis()
        consumingType = 2
        consumingUntil = now + hold + 140
        Mouse.rClick(hold)

        lastGap = now
        gaps = max(0, gaps - 1)

        TimeUtils.setTimeout({
            if (System.currentTimeMillis() >= consumingUntil && consumingType == 2) {
                consumingUntil = 0L
                consumingType = 0
            }
        }, hold + 160)

        return hold
    }

    // --- QuickPearl (après une gap) -> perle haute, puis potion (guide) ---
    private fun tryQuickPearlSequence(distance: Float) {
        if (!quickPearlQueued) return
        val now = System.currentTimeMillis()
        if (now < quickPearlAt || now < consumingUntil) return
        if (pearls <= 0) { quickPearlQueued = false; return }
        if (distance !in quickPearlMinDist..quickPearlMaxDist) { quickPearlQueued = false; return }
        if (now - lastPearl <= 5000) { quickPearlQueued = false; return }

        // Perle : on jette rapidement (Mouse.setUsingProjectile pour fiabiliser)
        lastPearl = now
        val ok = Inventory.setInvItem("pearl")
        if (ok) {
            pearls = max(0, pearls - 1)
            Mouse.setUsingProjectile(true)
            // petit délai pour laisser le client armer la perle
            TimeUtils.setTimeout({
                Mouse.rClick(RandomUtils.randomIntInRange(110, 160))
                // on revient épée
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                    Inventory.setInvItem("sword")
                    // enchaîner la potion de force juste après (guide)
                    TimeUtils.setTimeout({
                        if (!mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.damageBoost)) {
                            forceDrinkStrengthPotion()
                        }
                    }, RandomUtils.randomIntInRange(180, 260))
                }, RandomUtils.randomIntInRange(220, 300))
            }, RandomUtils.randomIntInRange(180, 260))
        }

        quickPearlQueued = false
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val consumingNow = now < consumingUntil

        // Détection hits entrants (run-back defensif)
        if (p.hurtTime > 0 && p.hurtTime > prevHurtTime) {
            incomingStreak++
            lastHurtStamp = now
        } else if (now - lastHurtStamp > 450) {
            incomingStreak = 0
        }
        prevHurtTime = p.hurtTime

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Saut périodique si l’ennemi est loin (> 8 blocs)
        if (distance > 8f && p.onGround && now - lastFarJumpAt >= 540L) {
            Movement.singleJump(RandomUtils.randomIntInRange(130, 180))
            lastFarJumpAt = now
        }
        // Anti-saut en mêlée
        if (distance < 8f) Movement.stopJumping()

        // Petit hop si on a pris de l'avance et qu'on est au sol (anti-flat)
        if (combo >= 3 && distance >= 3.2f && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance/arrêt simples (éviter de pousser trop quand collé)
        if (distance < 1.5f || (distance < 2.4f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // S’il y a un bloc devant à ~3 blocs/1.5 haut, ne pas déclencher “runaway”
        if (WorldUtils.blockInFront(p, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // ====================== OUVERTURE FORCÉE ======================
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            TimeUtils.setTimeout({
                val hold = forceDrinkStrengthPotion()
                val waitForEnd = if (hold > 0) hold + RandomUtils.randomIntInRange(140, 200) else RandomUtils.randomIntInRange(80, 120)
                TimeUtils.setTimeout({
                    if (System.currentTimeMillis() < consumingUntil) {
                        TimeUtils.setTimeout({
                            if (gaps > 0) {
                                val g = forceEatGapple()
                                // option : quick pearl direct après l’ouverture si distance ok
                                if (g > 0 && distance in quickPearlMinDist..quickPearlMaxDist && pearls > 0) {
                                    quickPearlQueued = true
                                    quickPearlAt = consumingUntil + RandomUtils.randomIntInRange(100, 180)
                                }
                            }
                            TimeUtils.setTimeout({ openingPhase = false }, RandomUtils.randomIntInRange(120, 220))
                        }, (consumingUntil - System.currentTimeMillis() + 30).toInt().coerceAtLeast(50))
                    } else {
                        if (gaps > 0) {
                            val g = forceEatGapple()
                            if (g > 0 && distance in quickPearlMinDist..quickPearlMaxDist && pearls > 0) {
                                quickPearlQueued = true
                                quickPearlAt = System.currentTimeMillis() + g + RandomUtils.randomIntInRange(100, 180)
                            }
                        }
                        TimeUtils.setTimeout({ openingPhase = false }, RandomUtils.randomIntInRange(120, 220))
                    }
                }, waitForEnd)
            }, RandomUtils.randomIntInRange(20, 60))
        }

        // ====================== POTIONS (hors ouverture) ======================
        if (!openingPhase &&
            !p.isPotionActive(net.minecraft.potion.Potion.damageBoost) &&
            now - lastPotion > 5000 &&
            !consumingNow
        ) {
            val hold = forceDrinkStrengthPotion()
            if (hold <= 0 && strengthPots > 0) {
                lastPotion = now
                strengthPots--
                usePotion(8, distance < 3f, EntityUtils.entityFacingAway(opp, p))
                val soft = RandomUtils.randomIntInRange(200, 280)
                Mouse.rClick(soft)
                consumingType = 1
                consumingUntil = System.currentTimeMillis() + soft + 80
                TimeUtils.setTimeout({
                    if (System.currentTimeMillis() >= consumingUntil && consumingType == 1) {
                        consumingUntil = 0L
                        consumingType = 0
                    }
                }, soft + 100)
            }
        }

        // ====================== ARMURE (hors ouverture) ======================
        if (!openingPhase && !consumingNow) {
            for (i in 0..3) {
                if (p.inventory.armorItemInSlot(i) == null) {
                    if ((armor[i] ?: 0) > 0) {
                        TimeUtils.setTimeout({
                            if (System.currentTimeMillis() < consumingUntil) return@setTimeout
                            val ok = Inventory.setInvItem(ArmorEnum.values()[i].name.lowercase())
                            if (ok) {
                                armor[i] = (armor[i] ?: 0) - 1
                                TimeUtils.setTimeout({
                                    val r = RandomUtils.randomIntInRange(100, 150)
                                    Mouse.rClick(r)
                                    TimeUtils.setTimeout({
                                        Inventory.setInvItem("sword")
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    }
                }
            }
        }

        // ====================== GAPPLE (hors ouverture) ======================
        // Mange même en plein combo si besoin, mais jamais pendant une autre conso
        if (!openingPhase && gaps > 0 && !consumingNow) {
            val needGap = (p.health < 12f) || !p.isPotionActive(net.minecraft.potion.Potion.absorption)
            val cdOk = (now - lastGap) > 1600
            if (needGap && cdOk) {
                val g = forceEatGapple()
                // QuickPearl (guide) après n'importe quelle gap si la fenêtre est bonne
                if (g > 0 && distance in quickPearlMinDist..quickPearlMaxDist && pearls > 0) {
                    quickPearlQueued = true
                    quickPearlAt = System.currentTimeMillis() + g + RandomUtils.randomIntInRange(100, 180)
                }
            }
        }

        // ====================== “Free gap” (guide) ======================
        // Si on a >=10 hits de suite ET que la cible est repoussée (distance >= 4.5),
        // on claque une gap immédiatement (sans chercher de fenêtre).
        if (!openingPhase && gaps > 0 && !consumingNow) {
            if (combo >= 10 && distance >= 4.5f && System.currentTimeMillis() - lastGap > 1600) {
                val g = forceEatGapple()
                if (g > 0 && distance in quickPearlMinDist..quickPearlMaxDist && pearls > 0) {
                    quickPearlQueued = true
                    quickPearlAt = System.currentTimeMillis() + g + RandomUtils.randomIntInRange(100, 180)
                }
            }
        }

        // ====================== Run-back mid-combo (défensif) ======================
        if (!openingPhase && !consumingNow && incomingStreak >= 6 && distance < 3.5f) {
            // micro retrait directionnel + strafe latéral (≈280–420ms)
            val dur = RandomUtils.randomIntInRange(280, 420)
            Movement.stopForward()
            Movement.startBackward()
            if (RandomUtils.randomIntInRange(0, 1) == 0) {
                // léger biais latéral
                // on passe par handle() via movePriority plus bas
            }
            TimeUtils.setTimeout({
                Movement.stopBackward()
                Movement.startForward()
            }, dur)
            // Perle défensive si ça s'éternise
            if (incomingStreak >= 8 && pearls > 0 && now - lastPearl > 5000) {
                TimeUtils.setTimeout({
                    if (System.currentTimeMillis() < consumingUntil) return@setTimeout
                    if (Inventory.setInvItem("pearl")) {
                        pearls = max(0, pearls - 1)
                        lastPearl = System.currentTimeMillis()
                        Mouse.setUsingProjectile(true)
                        TimeUtils.setTimeout({
                            Mouse.rClick(RandomUtils.randomIntInRange(100, 150))
                            TimeUtils.setTimeout({
                                Mouse.setUsingProjectile(false)
                                Inventory.setInvItem("sword")
                            }, RandomUtils.randomIntInRange(220, 300))
                        }, RandomUtils.randomIntInRange(220, 300))
                    }
                }, RandomUtils.randomIntInRange(60, 120))
            }
        }

        // ====================== QuickPearl planifiée ======================
        tryQuickPearlSequence(distance)

        // ====================== STRAFE “CLASSIC” + AD-tapping ======================
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        fun edgeAhead(distProbe: Float) =
            WorldUtils.blockInFront(p, distProbe, 0.0f) == Blocks.air

        val voidNear = edgeAhead(1.4f) || edgeAhead(2.0f)
        if (voidNear) {
            val toLeft = WorldUtils.leftOrRightToPoint(p, Vec3(centerX, 0.0, centerZ))
            val w = 10
            if (toLeft) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        } else {
            // ---------- Close-range machine (< 2.6 blocs) ----------
            if (distance < 2.6f) {
                if (now >= closeStrafeNextAt) {
                    val roll = RandomUtils.randomIntInRange(0, 99)
                    closeStrafeMode = when {
                        roll < 50 -> MODE_BURST
                        roll < 75 -> MODE_HOLD_LEFT
                        else     -> MODE_HOLD_RIGHT
                    }
                    closeStrafeNextAt = now + when (closeStrafeMode) {
                        MODE_BURST -> RandomUtils.randomIntInRange(280, 420).toLong()
                        else       -> RandomUtils.randomIntInRange(220, 340).toLong()
                    }
                    if (closeStrafeMode == MODE_BURST) {
                        closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                    } else {
                        strafeDir = if (closeStrafeMode == MODE_HOLD_LEFT) -1 else 1
                    }
                } else if (closeStrafeMode == MODE_BURST && now >= closeStrafeToggleAt) {
                    strafeDir = -strafeDir
                    closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                }

                val weightClose = 6
                if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                Movement.startForward()
                Movement.startSprinting()
                randomStrafe = false
            } else {
                // ---------- AD-tapping (drawn-out combo offensif type 1) ----------
                adTapActive = (combo in 3..12) && (distance in 2.4f..4.0f)
                if (adTapActive) {
                    if (now >= adToggleAt) {
                        strafeDir = -strafeDir
                        adToggleAt = now + RandomUtils.randomIntInRange(80, 120)
                    }
                } else if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }

                val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
                if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }
                val weight = if (distance < 4f) 7 else 5
                if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                randomStrafe = (!adTapActive && distance in 8.0f..15.0f)
            }
        }

        handle(clear, randomStrafe, movePriority)

        prevDistance = distance
    }

    override fun onAttack() {
        // W-tap constant (avantage de strafe)
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}
