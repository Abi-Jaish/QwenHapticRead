package com.example.qwenhapticread

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import androidx.activity.ComponentActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var linearAccelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var vibrator: Vibrator

    private val client = OkHttpClient()
    private val apiKey = "" // There will be an API key provided by the alibaba cloud console

    // Session states
    private var isSessionActive = false
    private var sessionStartTime = 0L
    private val accelMagnitudes = mutableListOf<Float>()
    private val gyroMagnitudes = mutableListOf<Float>()

    // User comfort tracking (Scale: 1 = very sick/uncomfortable, 5 = perfectly comfortable)
    private var currentUserComfort = 4
    private var currentHapticIntensity = 50

    // Handler for continuous repeating vibration loops
    private val vibrationHandler = Handler(Looper.getMainLooper())
    private lateinit var continuousVibrationRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnReEvaluate = findViewById<Button>(R.id.btnReEvaluate)
        val btnStop = findViewById<Button>(R.id.btnStop)

        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (e: Exception) {
            Log.e("MotionData", "Error initializing hardware: ${e.message}", e)
        }

        isSessionActive = true
        sessionStartTime = System.currentTimeMillis()

        btnReEvaluate.setOnClickListener {
            Log.d("HapticSession", "User reported feeling sick. Lowering comfort rating and re-evaluating...")
            if (currentUserComfort > 1) currentUserComfort -= 1
            requestAiReEvaluation()
        }

        btnStop.setOnClickListener {
            stopContinuousVibration()
            Log.d("HapticSession", "User stopped continuous vibration.")
        }
    }

    private fun requestAiReEvaluation() {
        // 1. Snapshot sample counts before clearing buffers
        val accelCountSnapshot = accelMagnitudes.size
        val gyroCountSnapshot = gyroMagnitudes.size

        // Summarize current buffer of sensor readings
        val avgAccel = if (accelMagnitudes.isNotEmpty()) accelMagnitudes.average().toFloat() else 0.1f
        val avgGyro = if (gyroMagnitudes.isNotEmpty()) gyroMagnitudes.average().toFloat() else 0.1f

        accelMagnitudes.clear()
        gyroMagnitudes.clear()

        val readingDuration = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()

        // 2. Build payload incorporating user comfort feedback
        val sensorDataJson = """
            {
              "acceleration_level": $avgAccel,
              "rotation_level": $avgGyro,
              "reading_duration": $readingDuration,
              "user_comfort": $currentUserComfort,
              "current_haptic_intensity": $currentHapticIntensity
            }
        """.trimIndent()

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val requestBodyJson = JSONObject().apply {
            put("model", "qwen3.8-flash")
            put("stream", false)

            val messagesArray = org.json.JSONArray()
            val systemMessage = JSONObject().apply {
                put("role", "system")
                put("content", "You are a haptic feedback decision engine. Based on the sensor data and user comfort, output a JSON object with a vibration strategy containing exact keys: 'pattern' (String: 'single_pulse' or 'double_pulse'), 'intensity' (Int 0-255), 'duration_ms' (Int), and 'cooldown_ms' (Int). Lower comfort scores require adjusted intensity. Do not output markdown.")
            }
            val userMessage = JSONObject().apply {
                put("role", "user")
                put("content", sensorDataJson)
            }

            messagesArray.put(systemMessage)
            messagesArray.put(userMessage)
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("QwenAPI", "Failed to connect to AI: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val choices = jsonResponse.getJSONArray("choices")
                        val messageContent = choices.getJSONObject(0).getJSONObject("message").getString("content")

                        val cleanJsonString = messageContent.replace("```json", "").replace("```", "").trim()
                        val strategy = JSONObject(cleanJsonString)

                        val pattern = strategy.optString("pattern", "single_pulse")
                        val intensity = strategy.optInt("intensity", 100)
                        val durationMs = strategy.optLong("duration_ms", 70L)
                        val cooldownMs = strategy.optLong("cooldown_ms", 1200L)

                        currentHapticIntensity = intensity

                        // 3. Log all metrics after receiving response and starting vibration
                        logSessionDebugDetails(
                            collectedAccelCount = accelCountSnapshot,
                            collectedGyroCount = gyroCountSnapshot,
                            avgAccel = avgAccel,
                            avgGyro = avgGyro,
                            comfort = currentUserComfort,
                            duration = readingDuration,
                            inputPayload = sensorDataJson,
                            aiOutput = messageContent
                        )

                        runOnUiThread {
                            startContinuousVibrationLoop(pattern, intensity, durationMs, cooldownMs)
                        }

                    } catch (e: Exception) {
                        Log.e("QwenAPI", "Parsing error: ${e.message}")
                    }
                }
            }
        })
    }

    // New function to display collected values, evaluated values, input sent, and AI output
    private fun logSessionDebugDetails(
        collectedAccelCount: Int,
        collectedGyroCount: Int,
        avgAccel: Float,
        avgGyro: Float,
        comfort: Int,
        duration: Int,
        inputPayload: String,
        aiOutput: String
    ) {
        Log.d("SessionDebug", """
            
            ========== MOTION HAPTIC SESSION DEBUG ==========
            [1] COLLECTED SENSOR DATA:
                - Accelerometer Raw Samples Collected: $collectedAccelCount
                - Gyroscope Raw Samples Collected: $collectedGyroCount
            [2] EVALUATED / SUMMARIZED METRICS:
                - Average Acceleration Level: $avgAccel
                - Average Rotation Level: $avgGyro
                - Current User Comfort Score: $comfort
                - Session Reading Duration: ${duration}s
            [3] INPUT SENT TO AI (JSON Payload):
                $inputPayload
            [4] OUTPUT GENERATED BY AI:
                $aiOutput
            =================================================
        """.trimIndent())
    }

    private fun startContinuousVibrationLoop(pattern: String, intensity: Int, duration: Long, cooldown: Long) {
        stopContinuousVibration()

        continuousVibrationRunnable = object : Runnable {
            override fun run() {
                executeHapticFeedback(pattern, intensity, duration)
                vibrationHandler.postDelayed(this, duration + cooldown)
            }
        }
        vibrationHandler.post(continuousVibrationRunnable)
        Log.d("HapticTest", "Continuous vibration started with pattern: $pattern, intensity: $intensity")
    }

    private fun stopContinuousVibration() {
        if (::continuousVibrationRunnable.isInitialized) {
            vibrationHandler.removeCallbacks(continuousVibrationRunnable)
        }
        vibrator.cancel()
    }

    private fun executeHapticFeedback(pattern: String, intensity: Int, duration: Long) {
        if (!vibrator.hasVibrator()) return

        try {
            when (pattern) {
                "double_pulse" -> {
                    val timings = longArrayOf(0, duration, 100, duration)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val amplitudes = intArrayOf(0, intensity, 0, intensity)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(timings, -1)
                    }
                }
                "single_pulse" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(duration, intensity))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(duration)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HapticTest", "Vibration failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        linearAccelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopContinuousVibration()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isSessionActive) return

        when (event?.sensor?.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                accelMagnitudes.add(sqrt((x * x + y * y + z * z).toDouble()).toFloat())
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                gyroMagnitudes.add(sqrt((x * x + y * y + z * z).toDouble()).toFloat())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}