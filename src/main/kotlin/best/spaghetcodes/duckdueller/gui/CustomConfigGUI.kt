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
    
    private var currentTab = 0  // Changed from enum to Int for simplicity
    private val tabNames = listOf("General", "Combat", "Dodging", "Webhook", "Stats")
    private var fadeIn = 0f
    
    // Text fields
    private lateinit var apiKeyField: GuiTextField
    private lateinit var webhookField: GuiTextField
    
    // Theme colors
    private val primaryColor = Color(0, 240, 255, 255).rgb  // Cyan
    private val backgroundColor = Color(15, 15, 25, 240).rgb
    private val cardColor = Color(25, 25, 40, 200).rgb
    
    override fun initGui() {
        super.initGui()
        fadeIn = 0f
        
        // Initialize text fields
        val centerX = width / 2
        apiKeyField = GuiTextField(0, fontRendererObj, centerX - 100, 100, 200, 20)
        apiKeyField.maxStringLength = 36
        apiKeyField.text = DuckDueller.config?.apiKey ?: ""
        
        webhookField = GuiTextField(1, fontRendererObj, centerX - 100, 150, 200, 20)
        webhookField.maxStringLength = 200
        webhookField.text = DuckDueller.config?.webhookURL ?: ""
        
        // Add save button
        buttonList.clear()
        buttonList.add(GuiButton(100, width / 2 - 50, height - 30, 100, 20, "Save & Close"))
    }
    
    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        // Animate fade in
        if (fadeIn < 1f) fadeIn += 0.05f
        
        // Draw gradient background
        drawGradientRect(0, 0, width, height, 
            Color(10, 10, 20, (250 * fadeIn).toInt()).rgb, 
            Color(30, 10, 50, (250 * fadeIn).toInt()).rgb)
        
        // Draw main container
        val containerX = 40
        val containerY = 40
        val containerWidth = width - 80
        val containerHeight = height - 80
        
        // Container background
        drawRect(containerX, containerY, containerX + containerWidth, containerY + containerHeight, backgroundColor)
        
        // Draw border
        drawBorder(containerX, containerY, containerWidth, containerHeight, primaryColor)
        
        // Draw title
        val title = "§lKIRA CONFIG"
        drawCenteredString(fontRendererObj, title, width / 2, 15, primaryColor)
        
        // Draw tabs
        drawTabs(containerX, containerY - 25, mouseX, mouseY)
        
        // Draw content based on current tab
        val contentX = containerX + 20
        val contentY = containerY + 20
        
        when (currentTab) {
            0 -> drawGeneralTab(contentX, contentY)
            1 -> drawCombatTab(contentX, contentY)
            2 -> drawDodgingTab(contentX, contentY)
            3 -> drawWebhookTab(contentX, contentY)
            4 -> drawStatsTab(contentX, contentY)
        }
        
        // Draw status
        val status = if (DuckDueller.bot?.toggled() == true) "§aONLINE" else "§cOFFLINE"
        drawString(fontRendererObj, "Status: $status", width - 80, height - 10, -1)
        
        // Draw version
        drawString(fontRendererObj, "§7v1.0 by Nix", 5, height - 10, Color.GRAY.rgb)
        
        super.drawScreen(mouseX, mouseY, partialTicks)
    }
    
    private fun drawBorder(x: Int, y: Int, width: Int, height: Int, color: Int) {
        // Top
        drawHorizontalLine(x, x + width, y, color)
        // Bottom
        drawHorizontalLine(x, x + width, y + height, color)
        // Left
        drawVerticalLine(x, y, y + height, color)
        // Right
        drawVerticalLine(x + width, y, y + height, color)
    }
    
    private fun drawTabs(x: Int, y: Int, mouseX: Int, mouseY: Int) {
        var tabX = x
        tabNames.forEachIndexed { index, name ->
            val isSelected = index == currentTab
            val isHovered = mouseX in tabX..(tabX + 100) && mouseY in y..(y + 25)
            
            val bgColor = when {
                isSelected -> Color(0, 240, 255, 100).rgb
                isHovered -> Color(100, 100, 120, 150).rgb
                else -> Color(40, 40, 60, 100).rgb
            }
            
            // Tab background
            drawRect(tabX, y, tabX + 100, y + 25, bgColor)
            
            // Tab text
            val textColor = if (isSelected) primaryColor else -1
            drawCenteredString(fontRendererObj, name, tabX + 50, y + 8, textColor)
            
            tabX += 102
        }
    }
    
    private fun drawGeneralTab(x: Int, y: Int) {
        var yOffset = y
        
        drawString(fontRendererObj, "§lGENERAL SETTINGS", x, yOffset, primaryColor)
        yOffset += 25
        
        // Bot selector
        drawString(fontRendererObj, "Current Bot: ${getBotName()}", x, yOffset, -1)
        yOffset += 20
        
        // API Key
        drawString(fontRendererObj, "API Key:", x, yOffset, -1)
        yOffset += 15
        apiKeyField.xPosition = x
        apiKeyField.yPosition = yOffset
        apiKeyField.drawTextBox()
        yOffset += 30
        
        // Toggles
        drawToggle("Lobby Movement", x, yOffset, DuckDueller.config?.lobbyMovement ?: true)
        yOffset += 25
        
        drawToggle("Fast Requeue", x, yOffset, DuckDueller.config?.fastRequeue ?: true)
        yOffset += 25
        
        drawToggle("Paper Requeue", x, yOffset, DuckDueller.config?.paperRequeue ?: true)
        yOffset += 25
        
        // Values
        drawValue("Disconnect After Games", x, yOffset, DuckDueller.config?.disconnectAfterGames ?: 0)
        yOffset += 25
        
        drawValue("Disconnect After Minutes", x, yOffset, DuckDueller.config?.disconnectAfterMinutes ?: 0)
    }
    
    private fun drawCombatTab(x: Int, y: Int) {
        var yOffset = y
        
        drawString(fontRendererObj, "§lCOMBAT SETTINGS", x, yOffset, primaryColor)
        yOffset += 25
        
        drawValue("Min CPS", x, yOffset, DuckDueller.config?.minCPS ?: 10)
        yOffset += 25
        
        drawValue("Max CPS", x, yOffset, DuckDueller.config?.maxCPS ?: 14)
        yOffset += 25
        
        drawValue("Horizontal Look Speed", x, yOffset, DuckDueller.config?.lookSpeedHorizontal ?: 10)
        yOffset += 25
        
        drawValue("Vertical Look Speed", x, yOffset, DuckDueller.config?.lookSpeedVertical ?: 5)
        yOffset += 25
        
        val lookRand = DuckDueller.config?.lookRand ?: 0.3f
        drawString(fontRendererObj, "Look Randomization: $lookRand", x, yOffset, -1)
        yOffset += 25
        
        drawValue("Max Look Distance", x, yOffset, DuckDueller.config?.maxDistanceLook ?: 150)
        yOffset += 25
        
        drawValue("Max Attack Distance", x, yOffset, DuckDueller.config?.maxDistanceAttack ?: 5)
    }
    
    private fun drawDodgingTab(x: Int, y: Int) {
        var yOffset = y
        
        drawString(fontRendererObj, "§lQUEUE DODGING", x, yOffset, primaryColor)
        yOffset += 25
        
        drawToggle("Enable Dodging", x, yOffset, DuckDueller.config?.enableDodging ?: true)
        yOffset += 25
        
        if (DuckDueller.config?.enableDodging == true) {
            drawValue("Max Wins", x, yOffset, DuckDueller.config?.dodgeWins ?: 4000)
            yOffset += 25
            
            drawValue("Max Winstreak", x, yOffset, DuckDueller.config?.dodgeWS ?: 15)
            yOffset += 25
            
            val wlr = DuckDueller.config?.dodgeWLR ?: 3.0f
            drawString(fontRendererObj, "Max W/L Ratio: $wlr", x, yOffset, -1)
            yOffset += 25
            
            drawToggle("Dodge Lost To", x, yOffset, DuckDueller.config?.dodgeLostTo ?: true)
            yOffset += 25
            
            drawToggle("Dodge No Stats", x, yOffset, DuckDueller.config?.dodgeNoStats ?: true)
            yOffset += 25
            
            drawToggle("Strict Dodging", x, yOffset, DuckDueller.config?.strictDodging ?: false)
        }
    }
    
    private fun drawWebhookTab(x: Int, y: Int) {
        var yOffset = y
        
        drawString(fontRendererObj, "§lWEBHOOK SETTINGS", x, yOffset, primaryColor)
        yOffset += 25
        
        drawToggle("Send Webhook Messages", x, yOffset, DuckDueller.config?.sendWebhookMessages ?: false)
        yOffset += 25
        
        if (DuckDueller.config?.sendWebhookMessages == true) {
            drawString(fontRendererObj, "Webhook URL:", x, yOffset, -1)
            yOffset += 15
            webhookField.xPosition = x
            webhookField.yPosition = yOffset
            webhookField.width = 350
            webhookField.drawTextBox()
            yOffset += 30
            
            drawToggle("Send Queue Stats", x, yOffset, DuckDueller.config?.sendWebhookStats ?: false)
            yOffset += 25
            
            drawToggle("Send Dodge Alerts", x, yOffset, DuckDueller.config?.sendWebhookDodge ?: false)
            yOffset += 25
            
            drawToggle("Send AutoGG", x, yOffset, DuckDueller.config?.sendAutoGG ?: true)
            yOffset += 25
            
            drawToggle("Send Start Message", x, yOffset, DuckDueller.config?.sendStartMessage ?: false)
        }
    }
    
    private fun drawStatsTab(x: Int, y: Int) {
        var yOffset = y
        
        drawString(fontRendererObj, "§lSESSION STATISTICS", x, yOffset, primaryColor)
        yOffset += 25
        
        val wins = Session.wins
        val losses = Session.losses
        val wlr = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val winrate = if (wins + losses == 0) 0f else (wins.toFloat() / (wins + losses)) * 100
        
        drawStatCard("WINS", x, yOffset, wins.toString(), Color.GREEN.rgb)
        drawStatCard("LOSSES", x + 120, yOffset, losses.toString(), Color.RED.rgb)
        drawStatCard("W/L RATIO", x + 240, yOffset, String.format("%.2f", wlr), primaryColor)
        yOffset += 60
        
        drawStatCard("WIN RATE", x, yOffset, String.format("%.1f%%", winrate), Color.CYAN.rgb)
        drawStatCard("GAMES", x + 120, yOffset, (wins + losses).toString(), Color.YELLOW.rgb)
        
        if (Session.startTime > 0) {
            val duration = (System.currentTimeMillis() - Session.startTime) / 1000 / 60
            drawStatCard("TIME", x + 240, yOffset, "${duration}m", Color.MAGENTA.rgb)
        }
    }
    
    private fun drawToggle(label: String, x: Int, y: Int, enabled: Boolean) {
        val color = if (enabled) "§a" else "§c"
        val status = if (enabled) "ON" else "OFF"
        drawString(fontRendererObj, "$label: $color$status", x, y, -1)
    }
    
    private fun drawValue(label: String, x: Int, y: Int, value: Int) {
        drawString(fontRendererObj, "$label: §b$value", x, y, -1)
    }
    
    private fun drawStatCard(title: String, x: Int, y: Int, value: String, color: Int) {
        // Background
        drawRect(x, y, x + 100, y + 50, cardColor)
        
        // Title
        drawString(fontRendererObj, title, x + 5, y + 5, Color.GRAY.rgb)
        
        // Value
        GL11.glPushMatrix()
        GL11.glTranslatef((x + 50).toFloat(), (y + 25).toFloat(), 0f)
        GL11.glScalef(1.5f, 1.5f, 1f)
        drawCenteredString(fontRendererObj, value, 0, 0, color)
        GL11.glPopMatrix()
    }
    
    private fun getBotName(): String {
        val botIndex = DuckDueller.config?.currentBot ?: 0
        val botNames = listOf("Sumo", "Boxing", "Classic", "OP", "Combo")
        return if (botIndex in botNames.indices) botNames[botIndex] else "Unknown"
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)
        
        // Handle tab clicks
        var tabX = 40
        val tabY = 15
        tabNames.forEachIndexed { index, _ ->
            if (mouseX in tabX..(tabX + 100) && mouseY in tabY..(tabY + 25)) {
                currentTab = index
            }
            tabX += 102
        }
        
        // Handle text fields
        apiKeyField.mouseClicked(mouseX, mouseY, mouseButton)
        webhookField.mouseClicked(mouseX, mouseY, mouseButton)
    }
    
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            saveConfig()
            mc.displayGuiScreen(null)
            return
        }
        
        apiKeyField.textboxKeyTyped(typedChar, keyCode)
        webhookField.textboxKeyTyped(typedChar, keyCode)
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
        DuckDueller.config?.writeData()
        
        ChatUtils.info("Configuration saved!")
    }
    
    override fun doesGuiPauseGame(): Boolean = false
}
