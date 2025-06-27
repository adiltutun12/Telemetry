package com.example.telemetry

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Vibrator
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var temperatureSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var mqttClient: MqttClient? = null
    private val mqttBroker = "tcp://broker.hivemq.com:1883"
    private val clientId = "AndroidTelemetry_${UUID.randomUUID()}"

    private val telemetryJob = SupervisorJob()
    private val telemetryScope = CoroutineScope(Dispatchers.IO + telemetryJob)

    private var currentTemperature = 0f
    private var accelerometerData = FloatArray(3)
    private var gyroscopeData = FloatArray(3)

    // REMOTE CONTROL KOMPONENTE - DODANO!
    private lateinit var cameraManager: CameraManager
    private var flashlightOn = false
    private var cameraId: String? = null
    private lateinit var vibrator: Vibrator
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager

    // UI elementi
    private var batteryStatusText: TextView? = null
    private var temperatureStatusText: TextView? = null
    private var wifiStatusText: TextView? = null
    private var memoryStatusText: TextView? = null
    private var mqttStatusText: TextView? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val temperature = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)

                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val batteryPct = level * 100 / scale.toFloat()

                runOnUiThread {
                    batteryStatusText?.text = "${batteryPct.toInt()}% ${if (isCharging) "⚡" else "🔌"}"
                }

                sendBatteryData(batteryPct, isCharging, voltage, temperature / 10f)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        initializeRemoteControl()
        checkPermissions()
        initializeSensors()
        initializeMqtt()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startTelemetryCollection()
    }

    private fun initializeUI() {
        try {
            batteryStatusText = findViewById(R.id.battery_status)
            temperatureStatusText = findViewById(R.id.temperature_status)
            wifiStatusText = findViewById(R.id.wifi_status)
            memoryStatusText = findViewById(R.id.memory_status)
            mqttStatusText = findViewById(R.id.mqtt_status)
        } catch (e: Exception) {
            Toast.makeText(this, "UI Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeRemoteControl() {
        try {
            // Inicijalizuj komponente za remote control
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            // Dobij camera ID za flashlight
            try {
                val cameraIdList = cameraManager.cameraIdList
                for (id in cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val flashAvailable = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    if (flashAvailable == true) {
                        cameraId = id
                        break
                    }
                }
            } catch (e: Exception) {
                println("❌ Camera initialization error: ${e.message}")
            }

            println("✅ Remote control components initialized")
        } catch (e: Exception) {
            println("❌ Remote control initialization failed: ${e.message}")
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        // REMOTE CONTROL PERMISIJE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun initializeSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        temperatureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        temperatureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        if (temperatureSensor == null) {
            runOnUiThread {
                temperatureStatusText?.text = "Senzor N/A"
            }
        }
    }

    private fun initializeMqtt() {
        telemetryScope.launch {
            try {
                println("🔌 Initializing MQTT connection...")

                mqttClient = MqttClient(mqttBroker, clientId, null)
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                }

                println("🚀 Attempting to connect to: $mqttBroker")
                mqttClient?.connect(options)

                // ČEKAJ DA SE POVEŽE
                var attempts = 0
                while (attempts < 10) {
                    if (mqttClient?.isConnected == true) {
                        runOnUiThread {
                            mqttStatusText?.text = "✅ Connected"
                            Toast.makeText(this@MainActivity, "MQTT Connected!", Toast.LENGTH_SHORT).show()
                        }
                        println("✅ MQTT Successfully connected!")

                        // SUBSCRIBE NA COMMAND TOPIC-E
                        subscribeToCommandTopics()
                        return@launch
                    }
                    delay(1000)
                    attempts++
                    println("⏳ Waiting for connection... attempt $attempts/10")
                }

                // AKO NE USPE
                throw Exception("Connection timeout after 10 seconds")

            } catch (e: Exception) {
                println("❌ MQTT Connection failed: ${e.message}")
                runOnUiThread {
                    mqttStatusText?.text = "❌ Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "MQTT Error: ${e.message}", Toast.LENGTH_LONG).show()
                }

                // RETRY POSLE 5 SEKUNDI
                delay(5000)
                println("🔄 Retrying MQTT connection...")
                initializeMqtt()
            }
        }
    }

    private fun subscribeToCommandTopics() {
        telemetryScope.launch {
            try {
                mqttClient?.let { client ->
                    if (client.isConnected) {
                        // KORISTIMO Build.DEVICE umesto clientId!
                        val deviceId = Build.DEVICE // ovo je "a21s"

                        // Subscribe na command topic-e sa device_id
                        client.subscribe("command/${deviceId}/flashlight")
                        client.subscribe("command/${deviceId}/camera")
                        client.subscribe("command/${deviceId}/vibrate")
                        client.subscribe("command/${deviceId}/volume")
                        client.subscribe("command/${deviceId}/system")
                        client.subscribe("command/${deviceId}/notification")

                        println("✅ Subscribed to command topics with device_id: $deviceId")

                        // Callback za primanje komandi
                        client.setCallback(object : MqttCallback {
                            override fun connectionLost(cause: Throwable?) {
                                println("❌ MQTT connection lost: ${cause?.message}")
                            }

                            @RequiresPermission(Manifest.permission.VIBRATE)
                            override fun messageArrived(topic: String?, message: MqttMessage?) {
                                if (topic != null && message != null) {
                                    val payload = String(message.payload)
                                    println("📨 Command received: $topic -> $payload")
                                    handleRemoteCommand(topic, payload)
                                }
                            }

                            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                                // Ne koristimo
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                println("❌ Command subscription error: ${e.message}")
            }
        }
    }

    // HANDLE REMOTE COMMANDS
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun handleRemoteCommand(topic: String, payload: String) {
        println("🎮 Executing command: $topic -> $payload")

        try {
            val command = JSONObject(payload)
            val action = command.getString("action")

            when {
                topic.contains("flashlight") -> handleFlashlightCommand(action, command)
                topic.contains("camera") -> handleCameraCommand(action, command)
                topic.contains("vibrate") -> handleVibrateCommand(action, command)
                topic.contains("volume") -> handleVolumeCommand(action, command)
                topic.contains("system") -> handleSystemCommand(action, command)
                topic.contains("notification") -> handleNotificationCommand(action, command)
                else -> println("❓ Unknown command topic: $topic")
            }

            // Pošalji potvrdu
            sendCommandResponse(topic, "success", "Command executed successfully")

        } catch (e: Exception) {
            println("❌ Command execution error: ${e.message}")
            sendCommandResponse(topic, "error", e.message ?: "Unknown error")
        }
    }

    // FLASHLIGHT COMMANDS
    private fun handleFlashlightCommand(action: String, command: JSONObject) {
        when (action) {
            "on" -> {
                toggleFlashlight(true)
                runOnUiThread {
                    Toast.makeText(this, "🔦 Flashlight ON", Toast.LENGTH_SHORT).show()
                }
            }
            "off" -> {
                toggleFlashlight(false)
                runOnUiThread {
                    Toast.makeText(this, "🔦 Flashlight OFF", Toast.LENGTH_SHORT).show()
                }
            }
            "toggle" -> {
                flashlightOn = !flashlightOn
                toggleFlashlight(flashlightOn)
                runOnUiThread {
                    Toast.makeText(this, "🔦 Flashlight ${if (flashlightOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // CAMERA COMMANDS
    private fun handleCameraCommand(action: String, command: JSONObject) {
        when (action) {
            "open" -> {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    runOnUiThread {
                        Toast.makeText(this, "📷 Opening Camera", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "take_photo" -> {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startActivity(intent)
                runOnUiThread {
                    Toast.makeText(this, "📸 Taking Photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // VIBRATE COMMANDS
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun handleVibrateCommand(action: String, command: JSONObject) {
        when (action) {
            "short" -> {
                vibrator.vibrate(200)
                runOnUiThread {
                    Toast.makeText(this, "📳 Short Vibration", Toast.LENGTH_SHORT).show()
                }
            }
            "long" -> {
                vibrator.vibrate(1000)
                runOnUiThread {
                    Toast.makeText(this, "📳 Long Vibration", Toast.LENGTH_SHORT).show()
                }
            }
            "pattern" -> {
                val pattern = longArrayOf(0, 100, 200, 100, 200)
                vibrator.vibrate(pattern, -1)
                runOnUiThread {
                    Toast.makeText(this, "📳 Pattern Vibration", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // VOLUME COMMANDS
    private fun handleVolumeCommand(action: String, command: JSONObject) {
        when (action) {
            "up" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                runOnUiThread {
                    Toast.makeText(this, "🔊 Volume UP", Toast.LENGTH_SHORT).show()
                }
            }
            "down" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                runOnUiThread {
                    Toast.makeText(this, "🔉 Volume DOWN", Toast.LENGTH_SHORT).show()
                }
            }
            "mute" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                runOnUiThread {
                    Toast.makeText(this, "🔇 Volume MUTED", Toast.LENGTH_SHORT).show()
                }
            }
            "set" -> {
                val level = command.optInt("level", 50)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
                runOnUiThread {
                    Toast.makeText(this, "🔊 Volume set to $level%", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // SYSTEM COMMANDS
    private fun handleSystemCommand(action: String, command: JSONObject) {
        when (action) {
            "screen_on" -> {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "TelemetryApp:ScreenOn"
                )
                wakeLock.acquire(10000) // 10 seconds
                runOnUiThread {
                    Toast.makeText(this, "💡 Screen turned ON", Toast.LENGTH_SHORT).show()
                }
            }
            "restart_app" -> {
                runOnUiThread {
                    Toast.makeText(this, "🔄 Restarting app...", Toast.LENGTH_SHORT).show()
                }
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            }
        }
    }

    // NOTIFICATION COMMANDS
    private fun handleNotificationCommand(action: String, command: JSONObject) {
        when (action) {
            "show" -> {
                val title = command.optString("title", "Remote Command")
                val message = command.optString("message", "Command executed successfully")
                runOnUiThread {
                    Toast.makeText(this, "📱 $title: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // FLASHLIGHT HELPER
    private fun toggleFlashlight(on: Boolean) {
        try {
            cameraId?.let { id ->
                cameraManager.setTorchMode(id, on)
                flashlightOn = on
            }
        } catch (e: Exception) {
            println("❌ Flashlight error: ${e.message}")
        }
    }

    private fun sendCommandResponse(originalTopic: String, status: String, message: String) {
        telemetryScope.launch {
            try {
                val response = JSONObject().apply {
                    put("status", status)
                    put("message", message)
                    put("timestamp", System.currentTimeMillis())
                    put("original_topic", originalTopic)
                }

                // KORISTIMO Build.DEVICE umesto clientId
                val deviceId = Build.DEVICE
                val responseTopic = "response/${deviceId}/command"
                sendJsonMessage(responseTopic, response)

            } catch (e: Exception) {
                println("❌ Response send error: ${e.message}")
            }
        }
    }

    private fun startTelemetryCollection() {
        telemetryScope.launch {
            while (isActive) {
                collectAndSendDeviceInfo()
                updateMemoryInfo()
                delay(10000)
            }
        }
    }

    private fun updateMemoryInfo() {
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMB = runtime.totalMemory() / (1024 * 1024)

        runOnUiThread {
            memoryStatusText?.text = "${usedMB}MB/${totalMB}MB"
        }
    }

    private fun collectAndSendDeviceInfo() {
        try {
            val deviceInfo = JSONObject().apply {
                put("device_id", Build.DEVICE)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)

                val runtime = Runtime.getRuntime()
                put("total_memory", runtime.totalMemory())
                put("free_memory", runtime.freeMemory())
                put("used_memory", runtime.totalMemory() - runtime.freeMemory())

                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val wifiInfo = wifiManager.connectionInfo
                        put("wifi_signal_strength", wifiInfo.rssi)
                        put("wifi_ssid", wifiInfo.ssid ?: "unknown")

                        runOnUiThread {
                            wifiStatusText?.text = "${wifiInfo.rssi} dBm"
                        }
                    }
                } catch (e: Exception) {
                    put("wifi_signal_strength", -999)
                    put("wifi_ssid", "error")
                }

                try {
                    val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.READ_PHONE_STATE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        put("network_operator", telephonyManager.networkOperatorName ?: "unknown")
                        put("network_type", telephonyManager.dataNetworkType.toString())
                    }
                } catch (e: Exception) {
                    put("network_operator", "error")
                    put("network_type", "error")
                }

                put("timestamp", System.currentTimeMillis())
            }

            sendJsonMessage("telemetry/device", deviceInfo)

        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Device Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendBatteryData(level: Float, isCharging: Boolean, voltage: Int, temperature: Float) {
        try {
            val batteryData = JSONObject().apply {
                put("level", level)
                put("is_charging", isCharging)
                put("voltage", voltage)
                put("temperature", temperature)
                put("timestamp", System.currentTimeMillis())
            }

            sendJsonMessage("telemetry/battery", batteryData)
        } catch (e: Exception) {
            Toast.makeText(this, "Battery Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSensorData() {
        try {
            val sensorData = JSONObject().apply {
                put("ambient_temperature", currentTemperature)
                put("accelerometer_x", accelerometerData[0])
                put("accelerometer_y", accelerometerData[1])
                put("accelerometer_z", accelerometerData[2])
                put("gyroscope_x", gyroscopeData[0])
                put("gyroscope_y", gyroscopeData[1])
                put("gyroscope_z", gyroscopeData[2])
                put("timestamp", System.currentTimeMillis())
            }

            sendJsonMessage("telemetry/sensors", sensorData)
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun sendJsonMessage(topic: String, jsonData: JSONObject) {
        telemetryScope.launch {
            try {
                mqttClient?.let { client ->
                    if (client.isConnected) {
                        val message = MqttMessage(jsonData.toString().toByteArray()).apply {
                            qos = 1
                        }
                        client.publish(topic, message)
                        println("📤 Sent JSON message to $topic: ${jsonData.toString().take(100)}...")
                    } else {
                        println("MQTT not connected for topic: $topic")
                    }
                }
            } catch (e: Exception) {
                println("MQTT Send Error for $topic: ${e.message}")
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                    currentTemperature = it.values[0]
                    runOnUiThread {
                        temperatureStatusText?.text = "${currentTemperature}°C"
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    accelerometerData = it.values.clone()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroscopeData = it.values.clone()
                }
            }

            if (Random().nextInt(200) == 1) {
                telemetryScope.launch {
                    sendSensorData()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}

        sensorManager.unregisterListener(this)
        telemetryJob.cancel()

        try {
            mqttClient?.let { client ->
                if (client.isConnected) {
                    client.disconnect()
                }
            }
        } catch (e: Exception) {}
    }
}