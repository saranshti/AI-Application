package com.example.aiapplication

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatInference(private val context: Context) {

    companion object {
        private const val TAG = "ChatInference"
        
        // Use static variables to ensure only ONE session exists in the entire App process
        // This prevents "A Session already Exist" errors during Activity recreation
        @Volatile
        private var instanceEngine: Engine? = null
        
        @Volatile
        private var instanceConversation: Conversation? = null
        
        @Volatile
        private var isInitializing = false
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var inferenceJob: Job? = null
    
    var isBusy = false
        private set

    val isInitialized: Boolean
        get() = instanceEngine != null

    fun initModel(modelPath: String) {
        if (instanceEngine != null || isInitializing) {
            Log.d(TAG, "Engine already initialized or initializing. Skipping.")
            return
        }

        synchronized(this) {
            if (instanceEngine != null || isInitializing) return
            isInitializing = true
            
            try {
                Log.d(TAG, "Initializing LiteRT Engine with model: $modelPath")
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU()
                )
                
                val engine = Engine(engineConfig)
                engine.initialize()
                
                instanceEngine = engine
                instanceConversation = engine.createConversation()
                Log.d(TAG, "Engine and Conversation initialized successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing engine: ${e.message}", e)
                isInitializing = false
                throw e
            } finally {
                isInitializing = false
            }
        }
    }

    fun generateStreamingResponse(prompt: String, onUpdate: (String, Boolean) -> Unit) {
        if (isBusy || instanceConversation == null) {
            if (instanceConversation == null) {
                onUpdate("Error: Model not initialized", true)
            }
            return
        }
        
        isBusy = true
        val fullResponse = StringBuilder()
        
        inferenceJob = scope.launch {
            try {
                // sendMessageAsync returns a Flow of tokens
                instanceConversation?.sendMessageAsync(prompt)?.collect { token ->
                    fullResponse.append(token)
                    // Update UI with the cumulative response
                    onUpdate(fullResponse.toString(), false)
                }
                
                // When collect finishes naturally
                isBusy = false
                onUpdate(fullResponse.toString().trim(), true)
            } catch (e: Exception) {
                isBusy = false
                // Check if the error is just a cancellation
                if (e is kotlinx.coroutines.CancellationException || e.message?.contains("cancelled", ignoreCase = true) == true) {
                    // It was stopped by the user. 
                    // Send the last available text and mark as done WITHOUT the error message.
                    onUpdate(fullResponse.toString().trim() + " (Stopped)", true)
                } else {
                    Log.e(TAG, "Inference error: ${e.message}", e)
                    onUpdate("Error during inference: ${e.message}", true)
                }
            }
        }
    }

    fun stopResponse() {
        // 1. Stop the Kotlin coroutine that is collecting the response
        inferenceJob?.cancel()
        
        // 2. IMPORTANT: Tell the LiteRT Engine to stop the native inference process
        // This prevents it from "finishing" the old prompt in the background.
        try {
            instanceConversation?.cancelProcess()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling process: ${e.message}")
        }

        isBusy = false
    }

    fun close() {
        stopResponse()
        // Note: In a real app, you might only want to close this when the App process terminates
        // or in a ViewModel's onCleared.
        synchronized(this) {
            instanceConversation?.close()
            instanceEngine?.close()
            instanceConversation = null
            instanceEngine = null
        }
    }
}
