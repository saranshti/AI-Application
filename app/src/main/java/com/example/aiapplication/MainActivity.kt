package com.example.aiapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aiapplication.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<Message>()
    private lateinit var chatInference: ChatInference
    private var isModelReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatInference = ChatInference(this)
        
        binding.sendButton.isEnabled = false
        
        if (checkStoragePermission()) {
            startModelInitialization()
        } else {
            requestStoragePermission()
        }

        // Handle Safe Area and Keyboard (Window Insets)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                if (ime.bottom > 0) ime.bottom else systemBars.bottom
            )
            insets
        }

        setupRecyclerView()

        binding.enableFloatingButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Please enable AI Assistant in Accessibility Settings", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } else {
                // Accessibility Service is enabled, it should be running
                val serviceIntent = Intent(this, FloatingAssistantService::class.java)
                serviceIntent.action = "ACTION_SHOW_FLOATING"
                startService(serviceIntent)
                Toast.makeText(this, "AI Assistant Active", Toast.LENGTH_SHORT).show()
            }
        }

        // Scroll to bottom when keyboard appears
        binding.recyclerView.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && messages.isNotEmpty()) {
                binding.recyclerView.postDelayed({
                    binding.recyclerView.scrollToPosition(messages.size - 1)
                }, 100)
            }
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text.toString().trim()
            if (chatInference.isBusy) {
                chatInference.stopResponse()
                handleInferenceFinished()
            } else if (text.isNotEmpty()) {
                sendMessage(text)
                binding.messageInput.text.clear()
            }
        }
    }

    private fun handleInferenceFinished() {
        runOnUiThread {
            binding.sendButton.setImageResource(android.R.drawable.ic_menu_send)
            binding.sendButton.isEnabled = true
            binding.messageInput.isEnabled = true
            binding.messageInput.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isModelReady && checkStoragePermission()) {
            startModelInitialization()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        chatInference.stopResponse()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = android.content.ComponentName(this, FloatingAssistantService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun checkStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivity(intent)
            }
        }
    }

    private fun startModelInitialization() {
        if (chatInference.isInitialized) {
            isModelReady = true
            binding.loadingLayout.visibility = android.view.View.GONE
            binding.sendButton.isEnabled = true
            return
        }

        binding.loadingLayout.visibility = android.view.View.VISIBLE
        binding.sendButton.isEnabled = false
        
        Thread {
            val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val modelFile = File(downloadsPath, "gemma.litertlm")
            val modelPath = modelFile.absolutePath

            if (modelFile.exists()) {
                try {
                    chatInference.initModel(modelPath)
                    runOnUiThread {
                        isModelReady = true
                        binding.loadingLayout.visibility = android.view.View.GONE
                        binding.sendButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.loadingLayout.visibility = android.view.View.GONE
                        messages.add(Message("Error loading model: ${e.message}", false))
                        chatAdapter.notifyItemInserted(messages.size - 1)
                    }
                }
            } else {
                runOnUiThread {
                    binding.loadingLayout.visibility = android.view.View.GONE
                    messages.add(Message("Model file not found. Place 'gemma.litertlm' in Downloads.", false))
                    chatAdapter.notifyItemInserted(messages.size - 1)
                }
            }
        }.start()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages)
        binding.recyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }
    }

    private fun sendMessage(text: String) {
        if (!isModelReady) {
            messages.add(Message("Model is still initializing, please wait...", false))
            chatAdapter.notifyItemInserted(messages.size - 1)
            return
        }
        
        if (chatInference.isBusy) return

        val templateOptions = mapOf(
            InputEditorLabel.LANGUAGE.label to "Kotlin",
            InputEditorLabel.TONE.label to "Friendly",
            InputEditorLabel.STYLE.label to "Key bullet points"
        )
        
        val finalTemplate = when {
            text.startsWith("code:", ignoreCase = true) -> PromptTemplateType.CODE_SNIPPET
            text.startsWith("summarize:", ignoreCase = true) -> PromptTemplateType.SUMMARIZE_TEXT
            text.startsWith("rewrite:", ignoreCase = true) -> PromptTemplateType.REWRITE_TONE
            else -> PromptTemplateType.FREE_FORM
        }

        val cleanInput = if (finalTemplate != PromptTemplateType.FREE_FORM) text.substringAfter(":").trim() else text
        val promptForAI = finalTemplate.genFullPrompt(cleanInput, templateOptions)

        binding.sendButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        binding.sendButton.isEnabled = true
        binding.messageInput.isEnabled = false

        messages.add(Message(text, isUser = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)

        val botMessagePosition = messages.size
        messages.add(Message("Thinking...", isUser = false))
        chatAdapter.notifyItemInserted(botMessagePosition)
        binding.recyclerView.scrollToPosition(botMessagePosition)

        chatInference.generateStreamingResponse(promptForAI) { result, done ->
            runOnUiThread {
                if (result.isNotEmpty()) {
                    messages[botMessagePosition] = Message(result, isUser = false)
                    chatAdapter.notifyItemChanged(botMessagePosition)
                    binding.recyclerView.scrollToPosition(botMessagePosition)
                }
                if (done) handleInferenceFinished()
            }
        }
    }
}
