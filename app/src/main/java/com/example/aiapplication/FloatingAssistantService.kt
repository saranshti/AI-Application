package com.example.aiapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class FloatingAssistantService : AccessibilityService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var chatView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var chatParams: WindowManager.LayoutParams
    
    private lateinit var chatInference: ChatInference
    private val messages = mutableListOf<Message>()
    private lateinit var chatAdapter: ChatAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = mViewModelStore

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        setTheme(R.style.Theme_AIApplication)
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        chatInference = ChatInference(this)
        initModel()
        
        createBubbleView()
        createChatView()
        
        windowManager.addView(bubbleView, bubbleParams)
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_SHOW_FLOATING") {
            showFloatingViews()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showFloatingViews() {
        mainHandler.post {
            if (chatView.parent == null && bubbleView.parent == null) {
                windowManager.addView(bubbleView, bubbleParams)
            } else if (chatView.parent != null) {
                chatView.visibility = View.VISIBLE
                chatParams.alpha = 1.0f
                chatParams.flags = chatParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(chatView, chatParams)
            } else if (bubbleView.parent != null) {
                bubbleView.visibility = View.VISIBLE
                bubbleParams.alpha = 1.0f
                windowManager.updateViewLayout(bubbleView, bubbleParams)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Service is fully connected and ready to take screenshots
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
        if (bubbleView.parent != null) windowManager.removeView(bubbleView)
        if (chatView.parent != null) windowManager.removeView(chatView)
        chatInference.stopResponse()
    }

    private fun initModel() {
        val downloadsPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val modelFile = File(downloadsPath, "gemma.litertlm")
        if (modelFile.exists()) {
            Thread {
                try {
                    chatInference.initModel(modelFile.absolutePath)
                } catch (e: Exception) {
                    mainHandler.post {
                        Toast.makeText(this, "Model init failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun setupViewTreeOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    private fun createBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        setupViewTreeOwners(bubbleView)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = 100
        bubbleParams.y = 100

        val bubbleIcon = bubbleView.findViewById<ImageView>(R.id.bubble_icon)
        bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isMoving = true
                        bubbleParams.x = initialX + dx
                        bubbleParams.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) v.performClick()
                        return true
                    }
                }
                return false
            }
        })

        bubbleIcon.setOnClickListener {
            switchToChat()
        }
    }

    private fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

    private fun createChatView() {
        chatView = LayoutInflater.from(this).inflate(R.layout.layout_floating_chat, null)
        setupViewTreeOwners(chatView)
        
        chatParams = WindowManager.LayoutParams(
            300.dpToPx(this),
            400.dpToPx(this),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        chatParams.gravity = Gravity.CENTER

        val header = chatView.findViewById<View>(R.id.chat_header)
        header.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = chatParams.x
                        initialY = chatParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        chatParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        chatParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(chatView, chatParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        
        header.setOnClickListener { } // Just to handle click

        val recyclerView = chatView.findViewById<RecyclerView>(R.id.chat_recyclerview)
        val inputField = chatView.findViewById<EditText>(R.id.chat_input)
        val sendBtn = chatView.findViewById<ImageButton>(R.id.btn_send)
        val minimizeBtn = chatView.findViewById<ImageButton>(R.id.btn_minimize)
        val screenshotBtn = chatView.findViewById<ImageButton>(R.id.btn_screenshot)

        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        sendBtn.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty() && !chatInference.isBusy) {
                sendMessage(text, inputField, recyclerView)
            }
        }

        screenshotBtn.setOnClickListener {
            takeScreenshotAndSend(recyclerView)
        }

        minimizeBtn.setOnClickListener {
            switchToBubble()
        }
    }

    private fun takeScreenshotAndSend(recyclerView: RecyclerView) {
        // Temporarily hide our window to capture what's beneath
        // Using Alpha and disabling touch is often more reliable than GONE for overlays
        val originalAlpha = chatParams.alpha
        chatParams.alpha = 0f
        val originalFlags = chatParams.flags
        chatParams.flags = chatParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(chatView, chatParams)
        
        // Wait a bit for the view to disappear from the compositor
        mainHandler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    mainHandler.post {
                        chatParams.alpha = originalAlpha
                        chatParams.flags = originalFlags
                        windowManager.updateViewLayout(chatView, chatParams)
                        
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (bitmap != null) {
                                extractTextAndSend(bitmap, recyclerView)
                            } else {
                                Toast.makeText(this@FloatingAssistantService, "Screenshot failed: Empty buffer", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@FloatingAssistantService, "Screenshot failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    mainHandler.post {
                        chatParams.alpha = originalAlpha
                        chatParams.flags = originalFlags
                        windowManager.updateViewLayout(chatView, chatParams)
                        
                        val errorMsg = when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal Error"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No Access (Re-enable Service)"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Wait a moment"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid Display"
                            else -> "Error Code: $errorCode"
                        }
                        Toast.makeText(this@FloatingAssistantService, "Screenshot failed: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }, 150) // Slightly longer delay
    }

    private fun extractTextAndSend(bitmap: Bitmap, recyclerView: RecyclerView) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
                if (extractedText.isNotBlank()) {
                    sendImageMessage(extractedText, recyclerView)
                } else {
                    sendImageMessage("No text found on screen.", recyclerView)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "OCR Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                sendImageMessage("Failed to read text from screen.", recyclerView)
            }
    }

    private fun sendImageMessage(screenContent: String, recyclerView: RecyclerView) {
        messages.add(Message("📸 [Screenshot Captured]", isUser = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

        val botMsgPos = messages.size
        messages.add(Message("Analyzing screen content...", isUser = false))
        chatAdapter.notifyItemInserted(botMsgPos)
        recyclerView.scrollToPosition(botMsgPos)

        val prompt = "The user just took a screenshot. Here is the text extracted from the screen using OCR:\n\n" +
                "--- START OF SCREEN CONTENT ---\n" +
                screenContent + "\n" +
                "--- END OF SCREEN CONTENT ---\n\n" +
                "Please help the user based on the content above. If they have a specific question about it, answer it. If not, briefly summarize what's on their screen."

        chatInference.generateStreamingResponse(prompt) { result, _ ->
            mainHandler.post {
                if (result.isNotEmpty()) {
                    messages[botMsgPos] = Message(result, isUser = false)
                    chatAdapter.notifyItemChanged(botMsgPos)
                    recyclerView.scrollToPosition(botMsgPos)
                }
            }
        }
    }

    private fun sendMessage(text: String, inputField: EditText, recyclerView: RecyclerView) {
        if (!chatInference.isInitialized) {
            Toast.makeText(this, "Model not ready", Toast.LENGTH_SHORT).show()
            return
        }

        messages.add(Message(text, isUser = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
        inputField.text.clear()

        val botMsgPos = messages.size
        messages.add(Message("Thinking...", isUser = false))
        chatAdapter.notifyItemInserted(botMsgPos)
        recyclerView.scrollToPosition(botMsgPos)

        chatInference.generateStreamingResponse(text) { result, done ->
            mainHandler.post {
                if (result.isNotEmpty()) {
                    messages[botMsgPos] = Message(result, isUser = false)
                    chatAdapter.notifyItemChanged(botMsgPos)
                    recyclerView.scrollToPosition(botMsgPos)
                }
            }
        }
    }

    private fun switchToChat() {
        if (bubbleView.parent != null) windowManager.removeView(bubbleView)
        windowManager.addView(chatView, chatParams)
    }

    private fun switchToBubble() {
        if (chatView.parent != null) windowManager.removeView(chatView)
        windowManager.addView(bubbleView, bubbleParams)
    }
}
