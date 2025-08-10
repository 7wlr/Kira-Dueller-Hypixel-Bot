package best.spaghetcodes.duckdueller.gui

import best.spaghetcodes.duckdueller.DuckDueller
import best.spaghetcodes.duckdueller.bot.Session
import best.spaghetcodes.duckdueller.utils.ChatUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiTextField
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.EnumChatFormatting
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

class CustomConfigGUI : GuiScreen() {
    
    private var currentTab = Tab.GENERAL
    private val tabs = listOf(Tab.GENERAL, Tab.COMBAT, Tab.DODGING, Tab.WEBHOOK, Tab.STATS)
    private var scrollOffset = 0f
    private var maxScroll = 0f
    private var isScrolling = false
    
    // Animations
    private var fadeIn = 0f
    private var tabAnimation = mutableMapOf<Tab, Float>()
    private var buttonHover = mutableMapOf<Int, Float>()
    
    // Text fields
    private lateinit var apiKeyField: GuiTextField
    private lateinit var webhookField: GuiTextField
    private lateinit var dodgeListField: GuiTextField
    
    // Theme colors (Cyberpunk style)
    private val primaryColor = Color(0, 240, 255) // Cyan
    private val secondaryColor = Color(255, 0, 128) // Pink
    private val backgroundColor = Color(15, 15, 25, 240)
    private val cardColor = Color(25, 25, 40, 200)
    private val accentColor = Color(100, 255, 218)
    
    enum class Tab(val displayName: String, val icon: String) {
        GENERAL("General", "⚙"),
        COMBAT("Combat", "⚔"),
        DODGING("Queue Dodging", "🛡"),
        WEBHOOK("Webhook", "🔗"),
        STATS("Statistics", "📊")
    }
    
    override fun initGui() {
        super.initGui()
        fadeIn = 0f
        
        // Initialize text fields
        val centerX = width / 2
        apiKeyField = GuiTextField(0, fontRendererObj, centerX - 100, 0, 200, 20)
        apiKeyField.maxStringLength = 36
        apiKeyField.text = DuckDueller.config?.apiKey ?: ""
        
        webhookField = GuiTextField(1, fontRendererObj, centerX - 100, 0, 200, 20)
        webhookField.maxStringLength = 200
        webhookField.text = DuckDueller.config?.webhookURL ?: ""
        
        dodgeListField = GuiTextField(2, fontRendererObj, centerX - 100, 0, 200, 20)
        dodgeListField.maxStringLength = 500
        dodgeListField.text = DuckDueller.config?.dodgePlayersList ?: ""
        
        // Initialize animations
        tabs.forEach { tab ->
            tabAnimation[tab] = 0f
        }
        
        // Add buttons
        buttonList.clear()
        buttonList.add(GuiButton(100, width / 2 - 100, height - 30, 200, 20, "Save & Close"))
    }
    
    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        // Animate fade in
        if (fadeIn < 1f) fadeIn += 0.05f
        
        // Dark gradient background
        drawGradientBackground()
        
        // Draw main container
        val containerX = 50
        val containerY = 40
        val containerWidth = width - 100
        val containerHeight = height - 80
        
        // Container background with glow effect
        drawGlowingRect(containerX, containerY, containerWidth, containerHeight)
        
        // Draw tabs
        drawTabs(containerX, containerY - 30, mouseX, mouseY)
        
        // Draw content based on current tab
        GlStateManager.pushMatrix()
        GlStateManager.translate(0f, -scrollOffset, 0f)
        
        when (currentTab) {
            Tab.GENERAL -> drawGeneralTab(containerX + 20, containerY + 20)
            Tab.COMBAT -> drawCombatTab(containerX + 20, containerY + 20)
            Tab.DODGING -> drawDodgingTab(containerX + 20, containerY + 20)
            Tab.WEBHOOK -> drawWebhookTab(containerX + 20, containerY + 20)
            Tab.STATS -> drawStatsTab(containerX + 20, containerY + 20)
        }
        
        GlStateManager.popMatrix()
        
        // Draw title with glow
        drawCenteredStringWithGlow("§lKIRA CONFIG", width / 2, 15, primaryColor.rgb)
        
        // Draw version
        drawString(fontRendererObj, "§7v1.0 by Nix", 5, height - 10, Color.GRAY.rgb)
        
        // Draw bot status
        val status = if (DuckDueller.bot?.toggled() == true) "§aONLINE" else "§cOFFLINE"
        drawString(fontRendererObj, "Status: $status", width - 80, height - 10, Color.WHITE.rgb)
        
        super.drawScreen(mouseX, mouseY, partialTicks)
    }
    
    private fun drawGradientBackground() {
        drawGradientRect(0, 0, width, height, 
            Color(10, 10, 20, 250).rgb, 
            Color(30, 10, 50, 250).rgb)
        
        // Add animated particles
        val time = System.currentTimeMillis() / 50.0
        for (i in 0..20) {
            val x = (width * (0.1 + i * 0.04) + sin(time * 0.1 + i) * 20).toInt()
            val y = (height * (0.1 + cos(time * 0.15 + i * 2) * 0.3)).toInt()
            val size = (2 + sin(time * 0.2 + i * 3) * 1).toInt()
            drawRect(x, y, x + size, y + size, Color(primaryColor.red, primaryColor.green, primaryColor.blue, 50).rgb)
        }
    }
    
    private fun drawGlowingRect(x: Int, y: Int, width: Int, height: Int) {
        // Glow effect
        for (i in 5 downTo 1) {
            val alpha = (10 * i).coerceIn(0, 255)
            val color = Color(primaryColor.red, primaryColor.green, primaryColor.blue, alpha)
            drawRect(x - i, y - i, x + width + i, y + height + i, color.rgb)
        }
        
        // Main rect
        drawRect(x, y, x + width, y + height, backgroundColor.rgb)
        
        // Border
        drawHorizontalLine(x, x + width, y, primaryColor.rgb)
        drawHorizontalLine(x, x + width, y + height, primaryColor.rgb)
        drawVerticalLine(x, y, y + height, primaryColor.rgb)
        drawVerticalLine(x + width, y, y + height, primaryColor.rgb)
    }
    
    private fun drawTabs(x: Int, y: Int, mouseX: Int, mouseY: Int) {
        var tabX = x
        tabs.forEach { tab ->
            val isSelected = tab == currentTab
            val isHovered = mouseX in tabX..(tabX + 100) && mouseY in y..(y + 25)
            
            // Animate tab
            val targetAnim = if (isSelected) 1f else if (isHovered) 0.5f else 0f
            tabAnimation[tab] = tabAnimation[tab]!! + (targetAnim - tabAnimation[tab]!!) * 0.2f
            
            val anim = tabAnimation[tab]!!
            val color = if (isSelected) {
                primaryColor
            } else {
                Color(100, 100, 120, (150 + anim * 105).toInt())
            }
            
            // Tab background
            drawRect(tabX, y, tabX + 100, y + 25, Color(40, 40, 60, (100 + anim * 155).toInt()).rgb)
            
            // Tab highlight
            if (anim > 0) {
                drawRect(tabX, y + 23, tabX + (100 * anim).toInt(), y + 25, primaryColor.rgb)
            }
            
            // Tab text with icon
            val text = "${tab.icon} ${tab.displayName}"
            drawCenteredString(fontRendererObj, text, tabX + 50, y + 8, color.rgb)
            
            tabX += 102
        }
    }
    
    private fun drawGeneralTab(x: Int, y: Int) {
        var yOffset = y
        
        drawSectionTitle("BOT SETTINGS", x, yOffset)
        yOffset += 30
        
        // Bot selector with cards
        drawLabel("Select Bot:", x, yOffset)
        yOffset += 20
        
        val bots = listOf("Sumo", "Boxing", "Classic", "OP", "Combo")
        var botX = x
        bots.forEachIndexed { index, bot ->
            val isSelected = index == DuckDueller.config?.currentBot
            drawBotCard(botX, yOffset, bot, isSelected)
            botX += 85
        }
        yOffset += 70
        
        // API Key
        drawLabel("API Key:", x, yOffset)
        yOffset += 15
        apiKeyField.yPosition = yOffset
        apiKeyField.xPosition = x
        apiKeyField.drawTextBox()
        yOffset += 30
        
        // Toggles with custom switches
        drawToggle("Lobby Movement", x, yOffset, DuckDueller.config?.lobbyMovement ?: true)
        yOffset += 35
        
        drawToggle("Fast Requeue", x, yOffset, DuckDueller.config?.fastRequeue ?: true)
        yOffset += 35
        
        drawToggle("Paper Requeue", x, yOffset, DuckDueller.config?.paperRequeue ?: true)
        yOffset += 35
        
        // Sliders
        drawSlider("Disconnect After Games", x, yOffset, 0f, 1000f, 
            DuckDueller.config?.disconnectAfterGames?.toFloat() ?: 0f)
        yOffset += 35
        
        drawSlider("Disconnect After Minutes", x, yOffset, 0f, 500f,
            DuckDueller.config?.disconnectAfterMinutes?.toFloat() ?: 0f)
    }
    
    private fun drawCombatTab(x: Int, y: Int) {
        var yOffset = y
        
        drawSectionTitle("COMBAT CONFIGURATION", x, yOffset)
        yOffset += 30
        
        // CPS Settings with visual bars
        drawLabel("Click Speed (CPS):", x, yOffset)
        yOffset += 20
        
        val minCPS = DuckDueller.config?.minCPS ?: 10
        val maxCPS = DuckDueller.config?.maxCPS ?: 14
        
        drawCPSBar(x, yOffset, minCPS, maxCPS)
        yOffset += 40
        
        // Look settings
        drawSectionTitle("AIM SETTINGS", x, yOffset)
        yOffset += 25
        
        drawSlider("Horizontal Speed", x, yOffset, 5f, 15f,
            DuckDueller.config?.lookSpeedHorizontal?.toFloat() ?: 10f)
        yOffset += 35
        
        drawSlider("Vertical Speed", x, yOffset, 1f, 15f,
            DuckDueller.config?.lookSpeedVertical?.toFloat() ?: 5f)
        yOffset += 35
        
        drawSlider("Randomization", x, yOffset, 0f, 2f,
            DuckDueller.config?.lookRand ?: 0.3f)
        yOffset += 35
        
        // Distance settings
        drawSectionTitle("ENGAGEMENT DISTANCE", x, yOffset)
        yOffset += 25
        
        drawSlider("Max Look Distance", x, yOffset, 120f, 180f,
            DuckDueller.config?.maxDistanceLook?.toFloat() ?: 150f)
        yOffset += 35
        
        drawSlider("Max Attack Distance", x, yOffset, 5f, 15f,
            DuckDueller.config?.maxDistanceAttack?.toFloat() ?: 5f)
    }
    
    private fun drawDodgingTab(x: Int, y: Int) {
        var yOffset = y
        
        drawSectionTitle("QUEUE DODGING", x, yOffset)
        yOffset += 30
        
        drawToggle("Enable Dodging", x, yOffset, DuckDueller.config?.enableDodging ?: true)
        yOffset += 35
        
        if (DuckDueller.config?.enableDodging == true) {
            // Dodge criteria with visual indicators
            drawStatCard("Max Wins", x, yOffset, DuckDueller.config?.dodgeWins ?: 4000, 20000)
            yOffset += 80
            
            drawStatCard("Max Winstreak", x + 170, yOffset - 80, DuckDueller.config?.dodgeWS ?: 15, 100)
            yOffset += 0
            
            drawStatCard("Max W/L Ratio", x + 340, yOffset - 80, 
                (DuckDueller.config?.dodgeWLR ?: 3.0f).toInt(), 15)
            yOffset += 30
            
            drawToggle("Dodge Lost To", x, yOffset, DuckDueller.config?.dodgeLostTo ?: true)
            yOffset += 35
            
            drawToggle("Dodge No Stats (Nicks)", x, yOffset, DuckDueller.config?.dodgeNoStats ?: true)
            yOffset += 35
            
            drawToggle("Strict Dodging", x, yOffset, DuckDueller.config?.strictDodging ?: false)
            yOffset += 35
            
            // Dodge list
            drawLabel("Players to Dodge (comma separated):", x, yOffset)
            yOffset += 15
            dodgeListField.yPosition = yOffset
            dodgeListField.xPosition = x
            dodgeListField.width = 300
            dodgeListField.drawTextBox()
        }
    }
    
    private fun drawWebhookTab(x: Int, y: Int) {
        var yOffset = y
        
        drawSectionTitle("DISCORD WEBHOOK", x, yOffset)
        yOffset += 30
        
        drawToggle("Send Webhook Messages", x, yOffset, DuckDueller.config?.sendWebhookMessages ?: false)
        yOffset += 35
        
        if (DuckDueller.config?.sendWebhookMessages == true) {
            drawLabel("Webhook URL:", x, yOffset)
            yOffset += 15
            webhookField.yPosition = yOffset
            webhookField.xPosition = x
            webhookField.width = 400
            webhookField.drawTextBox()
            yOffset += 30
            
            drawToggle("Send Queue Stats", x, yOffset, DuckDueller.config?.sendWebhookStats ?: false)
            yOffset += 35
            
            drawToggle("Send Dodge Alerts", x, yOffset, DuckDueller.config?.sendWebhookDodge ?: false)
            yOffset += 35
            
            // AutoGG settings
            drawSectionTitle("AUTO MESSAGES", x, yOffset)
            yOffset += 25
            
            drawToggle("Send AutoGG", x, yOffset, DuckDueller.config?.sendAutoGG ?: true)
            yOffset += 35
            
            drawToggle("Send Start Message", x, yOffset, DuckDueller.config?.sendStartMessage ?: false)
        }
    }
    
    private fun drawStatsTab(x: Int, y: Int) {
        var yOffset = y
        
        drawSectionTitle("SESSION STATISTICS", x, yOffset)
        yOffset += 30
        
        // Session stats with cool cards
        val wins = Session.wins
        val losses = Session.losses
        val wlr = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val winrate = if (wins + losses == 0) 0f else (wins.toFloat() / (wins + losses)) * 100
        
        drawLargeStatCard("WINS", x, yOffset, wins.toString(), Color.GREEN)
        drawLargeStatCard("LOSSES", x + 150, yOffset, losses.toString(), Color.RED)
        drawLargeStatCard("W/L RATIO", x + 300, yOffset, String.format("%.2f", wlr), primaryColor)
        yOffset += 100
        
        drawLargeStatCard("WIN RATE", x, yOffset, String.format("%.1f%%", winrate), accentColor)
        drawLargeStatCard("GAMES", x + 150, yOffset, (wins + losses).toString(), Color.YELLOW)
        
        // Session time
        if (Session.startTime > 0) {
            val duration = (System.currentTimeMillis() - Session.startTime) / 1000 / 60
            drawLargeStatCard("DURATION", x + 300, yOffset, "${duration}m", Color.CYAN)
        }
    }
    
    // Helper drawing methods
    private fun drawSectionTitle(title: String, x: Int, y: Int) {
        drawString(fontRendererObj, "§l$title", x, y, primaryColor.rgb)
        drawHorizontalLine(x, x + 200, y + 12, Color(primaryColor.red, primaryColor.green, primaryColor.blue, 100).rgb)
    }
    
    private fun drawLabel(text: String, x: Int, y: Int) {
        drawString(fontRendererObj, text, x, y, Color.LIGHT_GRAY.rgb)
    }
    
    private fun drawToggle(label: String, x: Int, y: Int, enabled: Boolean) {
        drawString(fontRendererObj, label, x, y, Color.WHITE.rgb)
        
        val toggleX = x + 200
        val toggleY = y - 2
        
        // Toggle background
        drawRect(toggleX, toggleY, toggleX + 40, toggleY + 20, 
            if (enabled) Color(0, 150, 0, 150).rgb else Color(150, 0, 0, 150).rgb)
        
        // Toggle slider
        val sliderX = if (enabled) toggleX + 22 else toggleX + 2
        drawRect(sliderX, toggleY + 2, sliderX + 16, toggleY + 18, Color.WHITE.rgb)
        
        // Status text
        val status = if (enabled) "ON" else "OFF"
        drawCenteredString(fontRendererObj, status, toggleX + 20, toggleY + 6, 
            if (enabled) Color.GREEN.rgb else Color.RED.rgb)
    }
    
    private fun drawSlider(label: String, x: Int, y: Int, min: Float, max: Float, value: Float) {
        drawString(fontRendererObj, "$label: ${value.toInt()}", x, y, Color.WHITE.rgb)
        
        val sliderX = x
        val sliderY = y + 12
        val sliderWidth = 200
        
        // Slider background
        drawRect(sliderX, sliderY, sliderX + sliderWidth, sliderY + 4, Color(60, 60, 80).rgb)
        
        // Slider fill
        val fillWidth = ((value - min) / (max - min) * sliderWidth).toInt()
        drawRect(sliderX, sliderY, sliderX + fillWidth, sliderY + 4, primaryColor.rgb)
        
        // Slider handle
        val handleX = sliderX + fillWidth - 3
        drawRect(handleX, sliderY - 3, handleX + 6, sliderY + 7, Color.WHITE.rgb)
    }
    
    private fun drawBotCard(x: Int, y: Int, name: String, selected: Boolean) {
        val cardColor = if (selected) primaryColor else Color(80, 80, 100, 150)
        
        // Card background
        drawRect(x, y, x + 80, y + 60, cardColor.rgb)
        
        // Card border
        if (selected) {
            drawRect(x - 1, y - 1, x + 81, y, accentColor.rgb)
            drawRect(x - 1, y + 60, x + 81, y + 61, accentColor.rgb)
            drawRect(x - 1, y, x, y + 60, accentColor.rgb)
            drawRect(x + 80, y, x + 81, y + 60, accentColor.rgb)
        }
        
        // Bot name
        drawCenteredString(fontRendererObj, name, x + 40, y + 25, Color.WHITE.rgb)
    }
    
    private fun drawStatCard(title: String, x: Int, y: Int, value: Int, max: Int) {
        // Card background
        drawRect(x, y, x + 150, y + 70, cardColor.rgb)
        
        // Title
        drawString(fontRendererObj, title, x + 10, y + 10, Color.LIGHT_GRAY.rgb)
        
        // Value
        val valueStr = value.toString()
        drawString(fontRendererObj, valueStr, x + 10, y + 25, primaryColor.rgb)
        
        // Progress bar
        val progress = (value.toFloat() / max * 130).toInt()
        drawRect(x + 10, y + 50, x + 140, y + 55, Color(40, 40, 60).rgb)
        drawRect(x + 10, y + 50, x + 10 + progress, y + 55, 
            if (value > max * 0.7) Color.RED.rgb else primaryColor.rgb)
    }
    
    private fun drawLargeStatCard(title: String, x: Int, y: Int, value: String, color: Color) {
        // Card with glow
        for (i in 3 downTo 1) {
            val alpha = (20 * i).coerceIn(0, 255)
            drawRect(x - i, y - i, x + 130 + i, y + 80 + i, 
                Color(color.red, color.green, color.blue, alpha).rgb)
        }
        
        drawRect(x, y, x + 130, y + 80, cardColor.rgb)
        
        // Title
        drawCenteredString(fontRendererObj, title, x + 65, y + 15, Color.GRAY.rgb)
        
        // Large value
        GL11.glPushMatrix()
        GL11.glTranslatef(x + 65f, y + 45f, 0f)
        GL11.glScalef(2f, 2f, 1f)
        drawCenteredString(fontRendererObj, value, 0, 0, color.rgb)
        GL11.glPopMatrix()
    }
    
    private fun drawCPSBar(x: Int, y: Int, min: Int, max: Int) {
        val barWidth = 300
        
        // Background
        drawRect(x, y, x + barWidth, y + 20, Color(40, 40, 60).rgb)
        
        // Min marker
        val minPos = ((min - 6) / 12f * barWidth).toInt()
        drawRect(x + minPos, y, x + minPos + 3, y + 20, Color.YELLOW.rgb)
        
        // Max marker
        val maxPos = ((max - 6) / 12f * barWidth).toInt()
        drawRect(x + maxPos, y, x + maxPos + 3, y + 20, Color.RED.rgb)
        
        // Fill between min and max
        drawRect(x + minPos, y + 5, x + maxPos, y + 15, primaryColor.rgb)
        
        // Labels
        drawString(fontRendererObj, "MIN: $min", x + minPos - 20, y + 25, Color.YELLOW.rgb)
        drawString(fontRendererObj, "MAX: $max", x + maxPos - 20, y + 25, Color.RED.rgb)
    }
    
    private fun drawCenteredStringWithGlow(text: String, x: Int, y: Int, color: Int) {
        // Glow effect
        GL11.glPushMatrix()
        for (i in 1..3) {
            val alpha = (255 / (i * 2)).coerceIn(0, 255)
            val glowColor = (alpha shl 24) or (color and 0xFFFFFF)
            GL11.glTranslatef(0f, 0f, -1f)
            drawCenteredString(fontRendererObj, text, x, y, glowColor)
        }
        GL11.glPopMatrix()
        
        // Main text
        drawCenteredString(fontRendererObj, text, x, y, color)
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)
        
        // Handle tab clicks
        var tabX = 50
        val tabY = 10
        tabs.forEach { tab ->
            if (mouseX in tabX..(tabX + 100) && mouseY in tabY..(tabY + 25)) {
                currentTab = tab
                scrollOffset = 0f
            }
            tabX += 102
        }
        
        // Handle text fields
        apiKeyField.mouseClicked(mouseX, mouseY, mouseButton)
        webhookField.mouseClicked(mouseX, mouseY, mouseButton)
        dodgeListField.mouseClicked(mouseX, mouseY, mouseButton)
    }
    
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            saveConfig()
            mc.displayGuiScreen(null)
            return
        }
        
        apiKeyField.textboxKeyTyped(typedChar, keyCode)
        webhookField.textboxKeyTyped(typedChar, keyCode)
        dodgeListField.textboxKeyTyped(typedChar, keyCode)
    }
    
    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            100 -> {
                saveConfig()
                mc.displayGuiScreen(null)
            }
        }
    }
    
    private fun saveConfig() {
        // Save the configuration
        DuckDueller.config?.apiKey = apiKeyField.text
        DuckDueller.config?.webhookURL = webhookField.text
        DuckDueller.config?.dodgePlayersList = dodgeListField.text
        DuckDueller.config?.writeData()
        
        ChatUtils.info("Configuration saved!")
    }
    
    override fun doesGuiPauseGame(): Boolean = false
}
