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

                sendBatteryDataProtobuf(batteryPct, isCharging, voltage, temperature / 10f)
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
            } catch (e: Exception) { }

        } catch (e: Exception) {
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

                        // SUBSCRIBE NA PROTOBUF KOMANDE (prioritet)
                        client.subscribe("command/${deviceId}/protobuf")

                        // ZADRŽAVAMO I JSON KOMANDE ZA KOMPATIBILNOST
                        client.subscribe("command/${deviceId}/flashlight")
                        client.subscribe("command/${deviceId}/camera")
                        client.subscribe("command/${deviceId}/vibrate")
                        client.subscribe("command/${deviceId}/volume")
                        client.subscribe("command/${deviceId}/system")
                        client.subscribe("command/${deviceId}/notification")


                        client.setCallback(object : MqttCallback {
                            override fun connectionLost(cause: Throwable?) {
                            }

                            @RequiresPermission(Manifest.permission.VIBRATE)
                            override fun messageArrived(topic: String?, message: MqttMessage?) {
                                if (topic != null && message != null) {

                                    // DETERMINIŠI TIP PORUKE
                                    if (topic.endsWith("/protobuf")) {
                                        // PROTOBUF KOMANDA
                                        handleProtobufCommand(message.payload)
                                    }
                                }
                            }

                            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                        })
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun handleProtobufCommand(payload: ByteArray) {
        try {

            // MANUAL PROTOBUF PARSING (jednostavan approach)
            val commandData = parseSimpleProtobufCommand(payload)

            if (commandData != null) {

                when (commandData.type) {
                    0 -> { // FLASHLIGHT
                        when (commandData.action) {
                            0 -> handleFlashlightAction("on")
                            1 -> handleFlashlightAction("off")
                            2 -> handleFlashlightAction("toggle")
                        }
                    }
                    1 -> { // CAMERA
                        when (commandData.action) {
                            0 -> handleCameraAction("open")
                            1 -> handleCameraAction("take_photo")
                        }
                    }
                    2 -> { // VIBRATE
                        when (commandData.action) {
                            0 -> handleVibrateAction("short")
                            1 -> handleVibrateAction("long")
                            2 -> handleVibrateAction("pattern")
                        }
                    }
                    3 -> { // VOLUME
                        when (commandData.action) {
                            0 -> handleVolumeAction("up", 0)
                            1 -> handleVolumeAction("down", 0)
                            2 -> handleVolumeAction("mute", 0)
                            3 -> handleVolumeAction("set", commandData.volumeLevel)
                        }
                    }
                    4 -> { // SYSTEM
                        when (commandData.action) {
                            0 -> handleSystemAction("screen_on")
                            1 -> handleSystemAction("restart_app")
                        }
                    }
                    5 -> { // NOTIFICATION
                        when (commandData.action) {
                            0 -> handleNotificationAction(commandData.title ?: "Notification", commandData.message ?: "Command executed")
                        }
                    }
                }

                // Pošalji success response
                sendCommandResponseProtobuf(true, "Protobuf command executed successfully: Type ${commandData.type}")

            } else {
                sendCommandResponseProtobuf(false, "Failed to parse Protobuf command")
            }

        } catch (e: Exception) {
            sendCommandResponseProtobuf(false, "Protobuf command execution failed: ${e.message}")
        }
    }

    // SIMPLE PROTOBUF PARSER (bez library-ja)
    data class ProtobufCommandData(
        val type: Int,
        val action: Int,
        val volumeLevel: Int = 0,
        val title: String? = null,
        val message: String? = null
    )

    private fun parseSimpleProtobufCommand(payload: ByteArray): ProtobufCommandData? {
        try {
            var pos = 0
            var commandType = -1
            var action = -1
            var volumeLevel = 0
            var title: String? = null
            var message: String? = null

            while (pos < payload.size) {
                if (pos >= payload.size - 1) break

                val tag = payload[pos++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                when (fieldNum) {
                    1 -> { // type field
                        if (wireType == 0) {
                            val result = readVarint(payload, pos)
                            commandType = result.first
                            pos = result.second
                        }
                    }
                    10, 11, 12, 13, 14, 15 -> { // command data fields
                        if (wireType == 2) { // length-delimited
                            val lengthResult = readVarint(payload, pos)
                            val length = lengthResult.first
                            pos = lengthResult.second

                            if (pos + length <= payload.size) {
                                val subData = payload.sliceArray(pos until pos + length)
                                action = parseActionFromSubData(subData)

                                // Parse additional data for volume/notification
                                if (fieldNum == 13) { // volume
                                    volumeLevel = parseVolumeLevelFromSubData(subData)
                                } else if (fieldNum == 15) { // notification
                                    val notificationData = parseNotificationFromSubData(subData)
                                    title = notificationData.first
                                    message = notificationData.second
                                }

                                pos += length
                            }
                        }
                    }
                    else -> {
                        // Skip unknown fields
                        if (wireType == 0) {
                            val result = readVarint(payload, pos)
                            pos = result.second
                        } else if (wireType == 2) {
                            val lengthResult = readVarint(payload, pos)
                            pos = lengthResult.second + lengthResult.first
                        }
                    }
                }
            }

            return if (commandType >= 0 && action >= 0) {
                ProtobufCommandData(commandType, action, volumeLevel, title, message)
            } else null

        } catch (e: Exception) {
            return null
        }
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var value = 0
        var pos = startPos
        var shift = 0

        while (pos < data.size) {
            val byte = data[pos++].toInt() and 0xFF
            value = value or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) break
            shift += 7
            if (shift >= 32) break
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
            "on" -> {
                toggleFlashlight(true)
            }
            "off" -> {
                toggleFlashlight(false)
            }
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
            "short" -> {
                vibrator.vibrate(200)
            }
            "long" -> {
                vibrator.vibrate(1000)
            }
            "pattern" -> {
                val pattern = longArrayOf(0, 100, 200, 100, 200)
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun handleVolumeAction(action: String, level: Int) {
        when (action) {
            "up" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            "down" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            "mute" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            }
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


    // 🚀 PROTOBUF COMMAND RESPONSE!
    private fun sendCommandResponseProtobuf(success: Boolean, message: String) {
        telemetryScope.launch {
            try {
                // SIMPLE PROTOBUF RESPONSE ENCODING
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

        // Field 1: success (bool)
        buffer.add((1 shl 3).toByte()) // field 1, wire type 0
        buffer.add(if (success) 1 else 0)

        // Field 2: message (string)
        buffer.add(((2 shl 3) or 2).toByte()) // field 2, wire type 2
        val messageBytes = message.toByteArray()
        writeVarintToBuffer(buffer, messageBytes.size)
        buffer.addAll(messageBytes.toList())

        // Field 4: device_id (string)
        buffer.add(((4 shl 3) or 2).toByte()) // field 4, wire type 2
        val deviceIdBytes = deviceId.toByteArray()
        writeVarintToBuffer(buffer, deviceIdBytes.size)
        buffer.addAll(deviceIdBytes.toList())

        // Field 5: timestamp (int64)
        buffer.add((5 shl 3).toByte()) // field 5, wire type 0
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

    // PROTOBUF TELEMETRY FUNCTIONS
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