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
import android.location.LocationManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
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

    private lateinit var cameraManager: CameraManager
    private var flashlightOn = false
    private var cameraId: String? = null
    private lateinit var vibrator: Vibrator
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager

    private var batteryStatusText: TextView? = null
    private var temperatureStatusText: TextView? = null
    private var wifiStatusText: TextView? = null
    private var memoryStatusText: TextView? = null
    private var mqttStatusText: TextView? = null

    //find my device
    private var findDeviceActive = false
    private var findDeviceJob: Job? = null
    private lateinit var ringtoneManager: RingtoneManager
    private var findDeviceRingtone: Ringtone? = null
    private var findDeviceVibrateJob: Job? = null
    private var findDeviceFlashlightJob: Job? = null
    private var findDeviceAlarmJob: Job? = null

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
                    batteryStatusText?.text = "${batteryPct.toInt()}% ${if (isCharging) "C" else "N"}"
                }

                sendBatteryDataProtobuf(batteryPct, isCharging, voltage, temperature / 10f)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        initializeRemoteControl()
        initializeFindMyDevice()
        checkPermissions()
        initializeSensors()
        initializeMqtt()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startTelemetryCollection()
    }

    private fun initializeFindMyDevice() {
        ringtoneManager = RingtoneManager(this)
    }

    private fun initializeUI() {
        try {
            batteryStatusText = findViewById(R.id.battery_status)
            //temperatureStatusText = findViewById(R.id.temperature_status)
            wifiStatusText = findViewById(R.id.wifi_status)
            memoryStatusText = findViewById(R.id.memory_status)
            mqttStatusText = findViewById(R.id.mqtt_status)
        } catch (e: Exception) {
            Toast.makeText(this, "UI Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeRemoteControl() {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

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
            } catch (_: Exception) { }

        } catch (_: Exception) {
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CALL_PHONE)
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
                mqttClient = MqttClient(mqttBroker, clientId, null)
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                }

                mqttClient?.connect(options)

                var attempts = 0
                while (attempts < 10) {
                    if (mqttClient?.isConnected == true) {
                        runOnUiThread {
                            mqttStatusText?.text = "Connected"
                        }

                        subscribeToCommandTopics()
                        return@launch
                    }
                    delay(1000)
                    attempts++
                }

                throw Exception("Connection timeout after 10 seconds")

            } catch (e: Exception) {
                runOnUiThread {
                    mqttStatusText?.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "MQTT Error: ${e.message}", Toast.LENGTH_LONG).show()
                }

                delay(5000)
                initializeMqtt()
            }
        }
    }

    private fun subscribeToCommandTopics() {
        telemetryScope.launch {
            try {
                mqttClient?.let { client ->
                    if (client.isConnected) {
                        val deviceId = Build.DEVICE

                        client.subscribe("command/${deviceId}/protobuf")

                        client.setCallback(object : MqttCallback {
                            override fun connectionLost(cause: Throwable?) {
                                runOnUiThread {
                                    mqttStatusText?.text = "Reconnecting..."
                                }
                            }

                            @RequiresPermission(Manifest.permission.VIBRATE)
                            override fun messageArrived(topic: String?, message: MqttMessage?) {
                                if (topic != null && message != null) {
                                    if (topic.endsWith("/protobuf")) {
                                        handleProtobufCommand(message.payload)
                                    }
                                }
                            }

                            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                        })
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Subscribe Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun handleProtobufCommand(payload: ByteArray) {
        try {
            println("Payload size: ${payload.size} bytes")
            println("Raw bytes: ${payload.joinToString(" ") { "%02x".format(it) }}")

            val commandData = parseSimpleProtobufCommand(payload)

            if (commandData != null) {
                println("Type: ${commandData.type}, Action: ${commandData.action}")

                when (commandData.type) {
                    0 -> {
                        println("Executing flashlight command: ${commandData.action}")
                        when (commandData.action) {
                            0 -> handleFlashlightAction("on")
                            1 -> handleFlashlightAction("off")
                            2 -> handleFlashlightAction("toggle")
                        }
                        sendCommandResponseProtobuf(true, "Flashlight executed: Action ${commandData.action}")
                    }
                    1 -> {
                        println("Executing camera command: ${commandData.action}")
                        when (commandData.action) {
                            0 -> handleCameraAction("open")
                            1 -> handleCameraAction("take_photo")
                        }
                        sendCommandResponseProtobuf(true, "Camera executed: Action ${commandData.action}")
                    }
                    2 -> {
                        println("Executing vibrate command: ${commandData.action}")
                        when (commandData.action) {
                            0 -> handleVibrateAction("short")
                            1 -> handleVibrateAction("long")
                            2 -> handleVibrateAction("pattern")
                        }
                        sendCommandResponseProtobuf(true, "Vibrate executed: Action ${commandData.action}")
                    }
                    3 -> {
                        println("Executing volume command: ${commandData.action}, level: ${commandData.volumeLevel}")
                        when (commandData.action) {
                            0 -> handleVolumeAction("up", 0)
                            1 -> handleVolumeAction("down", 0)
                            2 -> handleVolumeAction("mute", 0)
                            3 -> handleVolumeAction("set", commandData.volumeLevel)
                        }
                        sendCommandResponseProtobuf(true, "Volume executed: Action ${commandData.action}, Level ${commandData.volumeLevel}")
                    }
                    4 -> {
                        println("Executing system command: ${commandData.action}")
                        when (commandData.action) {
                            0 -> handleSystemAction("screen_on")
                            1 -> handleSystemAction("restart_app")
                        }
                        sendCommandResponseProtobuf(true, "System executed: Action ${commandData.action}")
                    }
                    5 -> {
                        println("Executing notification command: ${commandData.title}, ${commandData.message}")
                        handleNotificationAction(
                            commandData.title ?: "Notification",
                            commandData.message ?: "Command executed"
                        )
                        sendCommandResponseProtobuf(true, "Notification shown: ${commandData.title}")
                    }
                    6 -> {
                        println("Executing URL command: ${commandData.url}")
                        handleUrlAction(commandData.url ?: "https://google.com")
                        sendCommandResponseProtobuf(true, "URL opened: ${commandData.url}")
                    }
                    7 -> {
                        println("Executing phone call: ${commandData.message}")
                        handlePhoneCall(commandData.message ?: "")
                        sendCommandResponseProtobuf(true, "Phone call initiated: ${commandData.message}")
                    }
                    8 -> { // FIND_MY_DEVICE
                        println("Find Device Action: ${commandData.action}")
                        when (commandData.action) {
                            0 -> {
                                println("ACTIVATING FIND MY DEVICE ALARM!")
                                startFindMyDevice()
                                return // ✅ Return after startFindMyDevice (šalje svoj response)
                            }
                            1 -> {
                                println("STOPPING FIND MY DEVICE ALARM!")
                                stopFindMyDevice()
                                return // ✅ DODAJ return after stopFindMyDevice (šalje svoj response)
                            }
                            2 -> {
                                println("PINGING DEVICE!")
                            }
                            else -> {
                                println("Unknown find device action: ${commandData.action}")
                                sendCommandResponseProtobuf(false, "Unknown find device action: ${commandData.action}")
                                return // ✅ Return after error
                            }
                        }
                    }

                    else -> {
                        println("Unknown command type: ${commandData.type}")
                        sendCommandResponseProtobuf(false, "Unknown command type: ${commandData.type}")
                        return
                    }
                }

            } else {
                sendCommandResponseProtobuf(false, "Failed to parse Protobuf command - check payload format")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            sendCommandResponseProtobuf(false, "Command execution failed: ${e.message}")
        }
    }


    data class ProtobufCommandData(
        val type: Int,
        val action: Int,
        val volumeLevel: Int = 0,
        val title: String? = null,
        val message: String? = null,
        val url: String? = null
    )

    private fun handlePhoneCall(phoneNumber: String) {
        try {
            if (phoneNumber.isBlank()) {
                sendCommandResponseProtobuf(false, "Phone number is empty")
                return
            }

            // Validacija broja telefona
            val cleanNumber = phoneNumber.replace(Regex("[^+\\d]"), "") // Ukloni sve osim + i brojevi

            if (cleanNumber.length < 3) {
                sendCommandResponseProtobuf(false, "Invalid phone number: $phoneNumber")
                return
            }

            // Provjeri dozvolu
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
                sendCommandResponseProtobuf(false, "Call permission denied")
                return
            }

            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$cleanNumber")
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Pokreni poziv
            startActivity(callIntent)

            sendCommandResponseProtobuf(true, "Call initiated to: $cleanNumber")

        } catch (e: SecurityException) {
            sendCommandResponseProtobuf(false, "Security error: ${e.message}")
        } catch (e: Exception) {
            sendCommandResponseProtobuf(false, "Call failed: ${e.message}")
        }
    }

    private fun handleUrlAction(url: String) {
        try {
            var targetUrl = url

            if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                targetUrl = "https://$targetUrl"
            }

            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))

            if (browserIntent.resolveActivity(packageManager) != null) {
                startActivity(browserIntent)

                sendCommandResponseProtobuf(true, "URL opened successfully: $targetUrl")
            } else {
                sendCommandResponseProtobuf(false, "No browser application available")
            }

        } catch (e: Exception) {
            sendCommandResponseProtobuf(false, "Failed to open URL: ${e.message}")
        }
    }


    private fun startFindMyDevice() {
        try {
            findDeviceActive = true
            println(" STARTING FIND MY DEVICE ALARM! ")

            // 1. Maksimalna svjetlina
            runOnUiThread {
                try {
                    val layoutParams = window.attributes
                    layoutParams.screenBrightness = 1.0f
                    window.attributes = layoutParams
                    println(" Screen brightness set to maximum")
                } catch (e: Exception) {
                    println("️ Brightness error: ${e.message}")
                }
            }

            // 2. Maksimalna glasnoća za sve dostupne streamove
            try {
                val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val maxMusicVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val maxRingVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                val maxNotificationVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)

                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVolume, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVolume, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRingVolume, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotificationVolume, 0)

            } catch (e: Exception) {
                println("Volume error: ${e.message}")
            }

            //  Pokreni alarm zvuk
            playFindDeviceAlarm()
            println("Alarm sound started")

            //  Vibriraj kontinuirano
            startContinuousVibration()
            println("Vibration started")

            //  Blink flashlight
            startFlashlightBlink()
            println("Flashlight blinking started")

            //  Pošalji lokaciju periodično
            startLocationTracking()
            println("Location tracking started")

            //  Prikaži full-screen alert
            showFindDeviceAlert()
            println("Alert dialog shown")

            sendCommandResponseProtobuf(true, "FIND MY DEVICE ACTIVATED! \n\n Alarm sound: ACTIVE\n Vibration: ACTIVE\n Flashlight: BLINKING\n Location tracking: ACTIVE\n Screen brightness: MAXIMUM")

        } catch (e: Exception) {
            println("Find My Device error: ${e.message}")
            e.printStackTrace()
            sendCommandResponseProtobuf(false, "Find My Device failed: ${e.message}")
        }
    }

    private fun stopFindMyDevice() {
        try {
            findDeviceActive = false

            // Zaustavi sve odvojene jobove
            findDeviceVibrateJob?.cancel()
            findDeviceFlashlightJob?.cancel()
            findDeviceAlarmJob?.cancel()

            // Zaustavi ringtone
            findDeviceRingtone?.stop()

            // Zaustavi vibraciju
            vibrator.cancel()

            toggleFlashlight(false)

            // Vrati normalnu svjetlinu
            runOnUiThread {
                val layoutParams = window.attributes
                layoutParams.screenBrightness = -1f // default
                window.attributes = layoutParams
            }

            sendCommandResponseProtobuf(true, "Find My Device STOPPED - All alarms deactivated")

        } catch (e: Exception) {
            sendCommandResponseProtobuf(false, "Stop Find My Device failed: ${e.message}")
        }
    }


    private fun playFindDeviceAlarm() {
        findDeviceAlarmJob = telemetryScope.launch {
            try {
                // Probaj različite vrste alarm tonova
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                if (alarmUri != null) {
                    findDeviceRingtone = RingtoneManager.getRingtone(this@MainActivity, alarmUri)

                    // Pokušaj postaviti glasnoću
                    findDeviceRingtone?.let { ringtone ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.volume = 1.0f
                        }
                    }
                }

                // Glavna alarm petlja
                while (findDeviceActive && isActive) {
                    try {
                        // Ringtone alarm
                        findDeviceRingtone?.play()

                        // Backup - ToneGenerator ako ringtone ne radi
                        try {
                            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
                            delay(100)
                            toneGen.release()
                        } catch (e: Exception) {
                            println("ToneGenerator error: ${e.message}")
                        }

                        delay(2000) // Pauza između alarma
                    } catch (e: Exception) {
                        println("Alarm play error: ${e.message}")
                        delay(1000)
                    }
                }

            } catch (e: Exception) {
                println("Alarm setup error: ${e.message}")
            }
        }
    }

    private fun startContinuousVibration() {
        findDeviceVibrateJob = telemetryScope.launch {
            while (findDeviceActive && isActive) {
                try {
                    // Jaka vibracija pattern
                    val pattern = longArrayOf(0, 800, 300, 800, 300, 800)
                    vibrator.vibrate(pattern, -1)
                    delay(3000)
                } catch (e: Exception) {
                    println("Vibration error: ${e.message}")
                    delay(1000)
                }
            }
        }
    }

    private fun startFlashlightBlink() {
        findDeviceFlashlightJob = telemetryScope.launch {
            while (findDeviceActive && isActive) {
                try {
                    toggleFlashlight(true)
                    delay(200)
                    toggleFlashlight(false)
                    delay(200)
                } catch (e: Exception) {
                    println("Flashlight blink error: ${e.message}")
                    delay(500)
                }
            }
        }
    }

    private fun startLocationTracking() {
        telemetryScope.launch {
            var locationCount = 0
            while (findDeviceActive && isActive && locationCount < 5) {
                try {
                    sendCurrentLocation()
                    locationCount++
                    delay(10000) // Šalji lokaciju svakih 10 sekundi
                } catch (e: Exception) {
                    println("Location tracking error: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private fun sendCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            try {
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

                location?.let {
                    val locationData = """
                 DEVICE LOCATION UPDATE #${System.currentTimeMillis() % 1000}:
                 Lat: ${it.latitude}
                 Lng: ${it.longitude}
                 Accuracy: ${it.accuracy}m
                 Time: ${Date(it.time)}
                ️ Google Maps: https://maps.google.com/?q=${it.latitude},${it.longitude}
                 Provider: ${it.provider}
            """.trimIndent()

                    sendCommandResponseProtobuf(true, locationData)
                } ?: run {
                    sendCommandResponseProtobuf(true, " Location: Searching for GPS signal... (attempt ${System.currentTimeMillis() % 100})")
                }
            } catch (e: Exception) {
                sendCommandResponseProtobuf(true, " Location error: ${e.message}")
            }
        } else {
            sendCommandResponseProtobuf(true, "📍 Location permission denied - enable location access!")
        }
    }

    private fun showFindDeviceAlert() {
        runOnUiThread {
            try {
                val alertDialog = AlertDialog.Builder(this)
                    .setTitle(" FIND MY DEVICE ACTIVATED! ")
                    .setMessage("""
                     Your device is being located remotely!
                    
                     Alarm: ACTIVE
                     Vibration: ACTIVE  
                     Flashlight: BLINKING
                     Location: TRACKING
                    
                    Tap 'FOUND IT!' to stop all alarms.
                """.trimIndent())
                    .setCancelable(false)
                    .setPositiveButton(" FOUND IT!") { dialog, _ ->
                        stopFindMyDevice()
                        dialog.dismiss()
                    }
                    .setNegativeButton(" SEND LOCATION") { _, _ ->
                        sendCurrentLocation()
                        // Ne zatvaraj dialog - ovako sam trenutno samo pozvati funkciju stopFindMyDevice ako hocu odmah da zatvorim
                        showFindDeviceAlert() // Prikaži ponovo
                    }
                    .create()

                alertDialog.show()

                // Make dialog prominent
                alertDialog.window?.let { window ->
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }

            } catch (e: Exception) {
                println("Alert dialog error: ${e.message}")
                Toast.makeText(this, " FIND MY DEVICE ACTIVE! Check notification!", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun parseActionFromBytes(data: ByteArray): Int {
        try {
            if (data.isEmpty()) return 0

            var pos = 0
            while (pos < data.size - 1) {
                val tag = data[pos++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                if (fieldNum == 1 && wireType == 0) {
                    val result = readVarint(data, pos)
                    println("     Action value found: ${result.first}")
                    return result.first
                }

                // Skip this field properly
                when (wireType) {
                    0 -> {
                        val result = readVarint(data, pos)
                        pos = result.second
                        println("      Skipped varint field $fieldNum = ${result.first}")
                    }
                    2 -> {
                        val lengthResult = readVarint(data, pos)
                        pos = lengthResult.second + lengthResult.first
                        println("    ️  Skipped string field $fieldNum")
                    }
                    else -> {
                        println("    ️  Unknown wire type: $wireType")
                        pos++
                    }
                }
            }
        } catch (e: Exception) {
            println(" Error parsing action: ${e.message}")
        }

        println("  No action field found, returning 0")
        return 0
    }

    private fun parseVolumeLevelFromBytes(data: ByteArray): Int {
        try {
            var pos = 0
            while (pos < data.size - 1) {
                val tag = data[pos++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                if (fieldNum == 2 && wireType == 0) { // volume_level field
                    val result = readVarint(data, pos)
                    println("    Volume level: ${result.first}")
                    return result.first
                }

                // Skip field
                when (wireType) {
                    0 -> {
                        val result = readVarint(data, pos)
                        pos = result.second
                    }
                    2 -> {
                        val lengthResult = readVarint(data, pos)
                        pos = lengthResult.second + lengthResult.first
                    }
                    else -> pos++
                }
            }
        } catch (e: Exception) {
            println("Error parsing volume level: ${e.message}")
        }
        return 0
    }

    private fun parseNotificationFromBytes(data: ByteArray): Pair<String?, String?> {
        var title: String? = null
        var message: String? = null

        try {
            var pos = 0
            while (pos < data.size) {
                if (pos >= data.size - 1) break

                val tag = data[pos++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                if (wireType == 2) { // string field
                    val lengthResult = readVarint(data, pos)
                    val length = lengthResult.first
                    pos = lengthResult.second

                    if (pos + length <= data.size) {
                        val stringValue = String(data.sliceArray(pos until pos + length), Charsets.UTF_8)

                        when (fieldNum) {
                            2 -> {
                                title = stringValue
                                println("    Notification title: ${title}")
                            }
                            3 -> {
                                message = stringValue
                                println("    Notification message: ${message}")
                            }
                        }

                        pos += length
                    } else {
                        break
                    }
                } else if (wireType == 0) {
                    val result = readVarint(data, pos)
                    pos = result.second
                } else {
                    pos++
                }
            }
        } catch (e: Exception) {
            println("Error parsing notification: ${e.message}")
        }

        return Pair(title, message)
    }

    private fun parseUrlFromBytes(data: ByteArray): String? {
        return try {
            String(data, Charsets.UTF_8)
        } catch (e: Exception) {
            println("Error parsing URL: ${e.message}")
            null
        }
    }

    /*
    private fun parseSimpleProtobufCommand(payload: ByteArray): ProtobufCommandData? {
        try {
            println("=== SIMPLE PROTOBUF PARSING ===")
            println("Raw bytes: ${payload.joinToString(" ") { "%02x".format(it) }}")

            var pos = 0
            var commandType = -1
            var action = -1
            var deviceId: String? = null
            var isField16Present = false

            while (pos < payload.size) {
                if (pos >= payload.size) break

                val tag = payload[pos++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                println("Field $fieldNum, WireType $wireType")

                when (wireType) {
                    0 -> { // Varint
                        val result = readVarint(payload, pos)
                        val value = result.first
                        pos = result.second

                        if (fieldNum == 1) {
                            commandType = value
                            println(" Command Type = $commandType")
                        }
                    }

                    2 -> { // Length-delimited
                        val lengthResult = readVarint(payload, pos)
                        val length = lengthResult.first
                        pos = lengthResult.second

                        if (pos + length > payload.size) break

                        val data = payload.sliceArray(pos until pos + length)

                        when (fieldNum) {
                            3 -> { // device_id
                                deviceId = String(data, Charsets.UTF_8)
                                println(" Device ID: $deviceId")
                            }

                            16 -> { // FIND MY DEVICE FIELD!
                                println(" FIELD 16 DETECTED - FIND MY DEVICE!")
                                isField16Present = true

                                // Parse action from field 16 data
                                if (data.size >= 2) {
                                    val actionTag = data[0].toInt() and 0xFF
                                    if ((actionTag shr 3) == 1 && (actionTag and 0x07) == 0) {
                                        action = data[1].toInt() and 0xFF
                                        println(" Action from field 16: $action")
                                    }
                                }
                            }

                            10, 11, 12, 13, 14, 15 -> { // Other command fields
                                println(" Other command field $fieldNum")
                                // Parse action if not already parsed
                                if (action == -1 && data.size >= 2) {
                                    val actionTag = data[0].toInt() and 0xFF
                                    if ((actionTag shr 3) == 1 && (actionTag and 0x07) == 0) {
                                        action = data[1].toInt() and 0xFF
                                        println(" Action: $action")
                                    }
                                }
                            }
                        }

                        pos += length
                    }
                }
            }

            if (isField16Present) {
                println(" FORCING commandType to 8 because field 16 detected!")
                commandType = 8
            }

            println("=== FINAL RESULT ===")
            println("Command Type: $commandType")
            println("Action: $action")
            println("Field 16 Present: $isField16Present")

            return if (commandType >= 0) {
                ProtobufCommandData(
                    type = commandType,
                    action = if (action >= 0) action else 0,
                    volumeLevel = 0,
                    title = null,
                    message = null,
                    url = null
                )
            } else {
                null
            }

        } catch (e: Exception) {
            println("PARSE ERROR: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    */

    // ZAMIJENI parseSimpleProtobufCommand() sa ovom PROŠIRENOM verzijom:
    private fun parseSimpleProtobufCommand(payload: ByteArray): ProtobufCommandData? {
        try {
            println("=== EXTENDED PROTOBUF PARSING ===")
            println("Raw bytes: ${payload.joinToString(" ") { "%02x".format(it) }}")

            var pos = 0
            var commandType = -1
            var action = -1
            var deviceId: String? = null
            var volumeLevel = 0
            var title: String? = null
            var message: String? = null
            var url: String? = null
            var isField16Present = false

            while (pos < payload.size) {
                if (pos >= payload.size) break

                val tag = payload[pos++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                println("Field $fieldNum, WireType $wireType")

                when (wireType) {
                    0 -> { // Varint
                        val result = readVarint(payload, pos)
                        val value = result.first
                        pos = result.second

                        if (fieldNum == 1) {
                            commandType = value
                            println("📌 Command Type = $commandType")
                        }
                    }

                    2 -> { // Length-delimited
                        val lengthResult = readVarint(payload, pos)
                        val length = lengthResult.first
                        pos = lengthResult.second

                        if (pos + length > payload.size) break

                        val data = payload.sliceArray(pos until pos + length)

                        when (fieldNum) {
                            3 -> { // device_id
                                deviceId = String(data, Charsets.UTF_8)
                                println("📱 Device ID: $deviceId")
                            }

                            10, 11, 12 -> { // Basic command fields (flashlight, camera, vibrate)
                                println("🔧 Basic command field $fieldNum")
                                action = parseActionFromBytes(data)
                                println("⚡ Action: $action")
                            }

                            13 -> { // VOLUME command
                                println("🔊 Volume command field")
                                action = parseActionFromBytes(data)
                                volumeLevel = parseVolumeLevelFromBytes(data)
                                println("⚡ Volume Action: $action, Level: $volumeLevel")
                            }

                            14 -> { // SYSTEM command
                                println("⚙️ System command field")
                                action = parseActionFromBytes(data)
                                println("⚡ System Action: $action")
                            }

                            15 -> { // NOTIFICATION command (također koristi za URL i Phone)
                                println("📢 Notification/URL/Phone command field")
                                action = parseActionFromBytes(data)
                                val notificationData = parseNotificationFromBytes(data)
                                title = notificationData.first
                                message = notificationData.second

                                // Za URL komande, URL je u message fieldu
                                if (title == "URL_LAUNCH" || commandType == 6) {
                                    url = message
                                    println("🌐 URL detected: $url")
                                }

                                println("📢 Title: '$title', Message: '$message'")
                            }

                            16 -> { // FIND MY DEVICE FIELD!
                                println("🚨 FIELD 16 DETECTED - FIND MY DEVICE!")
                                isField16Present = true
                                action = parseActionFromBytes(data)
                                println("⚡ Find Device Action: $action")
                            }
                        }

                        pos += length
                    }
                }
            }

            // 🔥 FORCE LOGIC: Ako je field 16 prisutan, FORCE commandType na 8
            if (isField16Present) {
                println("🔥 FORCING commandType to 8 because field 16 detected!")
                commandType = 8
            }

            println("=== FINAL RESULT ===")
            println("Command Type: $commandType")
            println("Action: $action")
            println("Volume Level: $volumeLevel")
            println("Title: '$title'")
            println("Message: '$message'")
            println("URL: '$url'")
            println("Field 16 Present: $isField16Present")

            return if (commandType >= 0) {
                ProtobufCommandData(
                    type = commandType,
                    action = if (action >= 0) action else 0,
                    volumeLevel = volumeLevel,
                    title = title,
                    message = message,
                    url = url
                )
            } else {
                null
            }

        } catch (e: Exception) {
            println("PARSE ERROR: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var value = 0
        var pos = startPos
        var shift = 0

        while (pos < data.size && shift < 32) {
            val byte = data[pos++].toInt() and 0xFF
            value = value or ((byte and 0x7F) shl shift)

            if ((byte and 0x80) == 0) {
                break // Most significant bit is 0, we're done
            }

            shift += 7
        }

        return Pair(value, pos)
    }

    private fun parseActionFromSubData(data: ByteArray): Int {
        if (data.size >= 2) {
            val tag = data[0].toInt() and 0xFF
            if ((tag shr 3) == 1 && (tag and 0x07) == 0) {
                return data[1].toInt() and 0xFF
            }
        }
        return 0
    }

    private fun parseVolumeLevelFromSubData(data: ByteArray): Int {
        var pos = 0
        var level = 0

        while (pos < data.size - 1) {
            val tag = data[pos++].toInt() and 0xFF
            val fieldNum = tag shr 3

            if (fieldNum == 2) { // volume_level field
                level = data[pos].toInt() and 0xFF
                break
            }
            pos++
        }

        return level
    }

    private fun parseNotificationFromSubData(data: ByteArray): Pair<String?, String?> {
        var pos = 0
        var title: String? = null
        var message: String? = null

        while (pos < data.size - 1) {
            val tag = data[pos++].toInt() and 0xFF
            val fieldNum = tag shr 3
            val wireType = tag and 0x07

            if (wireType == 2) { // string field
                val lengthResult = readVarint(data, pos)
                val length = lengthResult.first
                pos = lengthResult.second

                if (pos + length <= data.size) {
                    val stringValue = String(data.sliceArray(pos until pos + length))

                    when (fieldNum) {
                        2 -> title = stringValue
                        3 -> message = stringValue
                    }

                    pos += length
                }
            }
        }

        return Pair(title, message)
    }

    private fun handleFlashlightAction(action: String) {
        when (action) {
            "on" -> toggleFlashlight(true)
            "off" -> toggleFlashlight(false)
            "toggle" -> {
                flashlightOn = !flashlightOn
                toggleFlashlight(flashlightOn)
            }
        }
    }

    private fun handleCameraAction(action: String) {
        when (action) {
            "open" -> {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                }
            }
            "take_photo" -> {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startActivity(intent)
            }
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun handleVibrateAction(action: String) {
        when (action) {
            "short" -> vibrator.vibrate(200)
            "long" -> vibrator.vibrate(1000)
            "pattern" -> {
                val pattern = longArrayOf(0, 100, 200, 100, 200)
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun handleVolumeAction(action: String, level: Int) {
        when (action) {
            "up" -> audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun handleSystemAction(action: String) {
        when (action) {
            "screen_on" -> {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "TelemetryApp:ScreenOn"
                )
                wakeLock.acquire(10000)
            }
            "restart_app" -> {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun handleNotificationAction(title: String, message: String) {
        runOnUiThread {
            if (title == "URL_LAUNCH") {
                handleUrlAction(message)
            } else {
                Toast.makeText(this@MainActivity, "$title: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleFlashlight(on: Boolean) {
        try {
            cameraId?.let { id ->
                cameraManager.setTorchMode(id, on)
                flashlightOn = on
            }
        } catch (e: Exception) {
            println("Flashlight error: ${e.message}")
        }
    }

    private fun sendCommandResponseProtobuf(success: Boolean, message: String) {
        telemetryScope.launch {
            try {
                val responseBytes = createProtobufResponse(success, message, Build.DEVICE)
                val responseTopic = "response/${Build.DEVICE}/protobuf"
                sendProtobufMessage(responseTopic, responseBytes)

            } catch (e: Exception) {
                println("Protobuf response send error: ${e.message}")
            }
        }
    }

    private fun createProtobufResponse(success: Boolean, message: String, deviceId: String): ByteArray {
        val buffer = mutableListOf<Byte>()

        buffer.add((1 shl 3).toByte())
        buffer.add(if (success) 1 else 0)

        buffer.add(((2 shl 3) or 2).toByte())
        val messageBytes = message.toByteArray()
        writeVarintToBuffer(buffer, messageBytes.size)
        buffer.addAll(messageBytes.toList())

        buffer.add(((4 shl 3) or 2).toByte())
        val deviceIdBytes = deviceId.toByteArray()
        writeVarintToBuffer(buffer, deviceIdBytes.size)
        buffer.addAll(deviceIdBytes.toList())

        buffer.add((5 shl 3).toByte())
        writeVarintToBuffer(buffer, System.currentTimeMillis().toInt())

        return buffer.toByteArray()
    }

    private fun writeVarintToBuffer(buffer: MutableList<Byte>, value: Int) {
        var v = value
        while (v >= 0x80) {
            buffer.add(((v and 0xFF) or 0x80).toByte())
            v = v ushr 7
        }
        buffer.add((v and 0xFF).toByte())
    }

    private fun collectAndSendDeviceInfoProtobuf() {
        try {
            val runtime = Runtime.getRuntime()

            val deviceInfoBuilder = TelemetryProto.DeviceInfo.newBuilder()
                .setDeviceId(Build.DEVICE)
                .setModel(Build.MODEL)
                .setManufacturer(Build.MANUFACTURER)
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setApiLevel(Build.VERSION.SDK_INT)
                .setTotalMemory(runtime.totalMemory())
                .setFreeMemory(runtime.freeMemory())
                .setUsedMemory(runtime.totalMemory() - runtime.freeMemory())
                .setTimestamp(System.currentTimeMillis())

            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val wifiInfo = wifiManager.connectionInfo
                    deviceInfoBuilder
                        .setWifiSignalStrength(wifiInfo.rssi)
                        .setWifiSsid(wifiInfo.ssid ?: "unknown")

                    runOnUiThread {
                        wifiStatusText?.text = "${wifiInfo.rssi} dBm"
                    }
                }
            } catch (e: Exception) {
                deviceInfoBuilder
                    .setWifiSignalStrength(-999)
                    .setWifiSsid("error")
            }

            try {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    deviceInfoBuilder
                        .setNetworkOperator(telephonyManager.networkOperatorName ?: "unknown")
                        .setNetworkType(telephonyManager.dataNetworkType.toString())
                }
            } catch (e: Exception) {
                deviceInfoBuilder
                    .setNetworkOperator("error")
                    .setNetworkType("error")
            }

            val deviceInfo = deviceInfoBuilder.build()
            sendProtobufMessage("telemetry/device", deviceInfo.toByteArray())

        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Device Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendBatteryDataProtobuf(level: Float, isCharging: Boolean, voltage: Int, temperature: Float) {
        try {
            val batteryData = TelemetryProto.BatteryData.newBuilder()
                .setLevel(level)
                .setIsCharging(isCharging)
                .setVoltage(voltage)
                .setTemperature(temperature)
                .setTimestamp(System.currentTimeMillis())
                .build()

            sendProtobufMessage("telemetry/battery", batteryData.toByteArray())
        } catch (e: Exception) {
            Toast.makeText(this, "Battery Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSensorDataProtobuf() {
        try {
            val sensorData = TelemetryProto.SensorData.newBuilder()
                .setAmbientTemperature(currentTemperature)
                .setAccelerometerX(accelerometerData[0])
                .setAccelerometerY(accelerometerData[1])
                .setAccelerometerZ(accelerometerData[2])
                .setGyroscopeX(gyroscopeData[0])
                .setGyroscopeY(gyroscopeData[1])
                .setGyroscopeZ(gyroscopeData[2])
                .setTimestamp(System.currentTimeMillis())
                .build()

            sendProtobufMessage("telemetry/sensors", sensorData.toByteArray())
        } catch (e: Exception) {
            println("Sensor protobuf error: ${e.message}")
        }
    }

    private fun sendProtobufMessage(topic: String, data: ByteArray) {
        telemetryScope.launch {
            try {
                mqttClient?.let { client ->
                    if (client.isConnected) {
                        val message = MqttMessage(data).apply {
                            qos = 1
                        }
                        client.publish(topic, message)
                    } else {
                        println("MQTT not connected for topic: $topic")
                    }
                }
            } catch (e: Exception) {
                println("MQTT Send Error for $topic: ${e.message}")
            }
        }
    }

    private fun startTelemetryCollection() {
        telemetryScope.launch {
            while (isActive) {
                collectAndSendDeviceInfoProtobuf()
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
                    sendSensorDataProtobuf()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}

        sensorManager.unregisterListener(this)
        telemetryJob.cancel()

        try {
            mqttClient?.let { client ->
                if (client.isConnected) {
                    client.disconnect()
                }
            }
        } catch (_: Exception) {}
    }
}