package com.example.aiapplication

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.LifecycleService
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
import java.io.File

class FloatingAssistantService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

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
        setTheme(R.style.Theme_AIApplication)
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        chatInference = ChatInference(this)
        initModel()
        
        createBubbleView()
        createChatView()
        
        windowManager.addView(bubbleView, bubbleParams)
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

        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        sendBtn.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty() && !chatInference.isBusy) {
                sendMessage(text, inputField, recyclerView)
            }
        }

        minimizeBtn.setOnClickListener {
            switchToBubble()
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

    override fun onDestroy() {
        super.onDestroy()
        if (bubbleView.parent != null) windowManager.removeView(bubbleView)
        if (chatView.parent != null) windowManager.removeView(chatView)
        chatInference.stopResponse()
    }
}
