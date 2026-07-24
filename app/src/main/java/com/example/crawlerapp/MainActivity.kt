package com.example.crawlerapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor

/*
 * MainActivity
 * XIAO ESP32S3からのJPEGストリームを受信し、YOLOv11を用いたリアルタイム物体検知を行う。
 * FPS維持のため、フレームスキップとメモリ再利用を最適化する
 */
class MainActivity : AppCompatActivity() {

    //通信状態確認用変数
    private lateinit var statusTextView: TextView
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private lateinit var fpsTextView: TextView

    // 【メモリ最適化】GC頻度を下げるため、Bitmapインスタンスを再利用する
    private var tempBitmap: Bitmap? = null

    private val CLASS_NAMES = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed",
        "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fpsTextView = findViewById(R.id.fpsTextView)

        statusTextView = findViewById(R.id.statusTextView)

        // OpenCVライブラリを初期化し、成功した場合はトースト通知を表示する
        if (OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCVの読み込み成功！", Toast.LENGTH_SHORT).show()
        }

        // AIモデル（ONNX）の読み込み設定
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = assets.open("yolo11s.onnx").readBytes()
            val options = OrtSession.SessionOptions().apply {
                // 【性能最適化】CPUのスレッド数を指定して推論の並列化を促進
                setIntraOpNumThreads(4)
                try {
                    // 【ハードウェアアクセラレーション】AndroidのNPU(Neural Processing Unit)を有効化し、負荷を軽減
                    addNnapi()
                } catch (e: Exception) {
                    // NNAPI非対応端末では自動的にCPU推論にフォールバックされる
                }
            }
            ortSession = ortEnvironment?.createSession(modelBytes, options)
            Toast.makeText(this, "AIモデルの準備完了！", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "AIモデルの読み込みエラー: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Wi-Fi接続監視（カメラモジュールとの通信確立待ち）
        val imageView = findViewById<ImageView>(R.id.cameraImageView)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { fpsTextView.text = "映像取得中..." }
                startStream(network, imageView)

                startStatusMonitoring(network)
            }
        })
    }

    // UDP通信を用いて本体からのステータスやセンサー情報を受信する関数
    private fun startStatusMonitoring(network: Network) {
        thread {
            var socket: java.net.DatagramSocket? = null

            // ▼ 追加：停止モードのトグル状態と、前回のボタン状態を保持する変数
            // （パケットを受信するたびに初期化されないよう、whileループの外に置きます）
            var isStopModeToggled = false
            var lastBtn0State = "0"

            try {
                // 1. カメラ側がブロードキャストしているポート 8888 でソケットを開く
                socket = java.net.DatagramSocket(8888)

                // 【重要】Androidがモバイル回線ではなく、カメラのWi-Fi経由で通信するようにソケットを固定
                network.bindSocket(socket)

                // 2. 受信タイムアウトを2秒に設定（2秒間パケットが来なければ圏外とみなす）
                socket.soTimeout = 2000

                val buffer = ByteArray(1024)
                val packet = java.net.DatagramPacket(buffer, buffer.size)

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        // UDPパケットの受信待ち（ブロック処理）
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length).trim()

                        // センサー検知やコマンド実行のアラートをUIで表示する
                        if (text.contains("ALERT:SENSOR_DETECTED")) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "⚠️ センサーが物体を検知しました！", Toast.LENGTH_SHORT).show()
                            }
                        } else if (text.contains("CMD:SUCCESS")) { // ★成功パターン
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "✨ コマンド実行成功！", Toast.LENGTH_SHORT).show()
                            }
                        } else if (text.contains("CMD:FAILED")) { // ★失敗パターン
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "❌ コマンド実行失敗...", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // 有線 ➔ UDPで本体から届くデータ例: "RSSI:-45,DIR:8,BTN0:1"
                        val pairs = text.split(",")
                        var rssi = ""
                        var btn0 = "0"
                        var modeStr = "DRIVE" // デフォルト

                        for (pair in pairs) {
                            val kv = pair.split(":")
                            if (kv.size == 2) {
                                when (kv[0]) {
                                    "RSSI" -> rssi = kv[1]
                                    "BTN0" -> btn0 = kv[1]
                                    "MODE" -> modeStr = kv[1] // 本体から送られてくるモードを受け取る
                                }
                            }
                        }

                        // ▼ 追加：ボタンが「0」から「1」に変わった瞬間（立ち上がりエッジ）を検知してトグル反転
                        if (btn0 == "1" && lastBtn0State == "0") {
                            isStopModeToggled = !isStopModeToggled
                        }
                        // 現在のボタン状態を次回のために保存
                        lastBtn0State = btn0

                        // UI（TextView）への反映
                        runOnUiThread {
                            if (rssi == "-100" || rssi.isEmpty()) {
                                statusTextView.text = "コントローラ: 切断 (本体は起動中)"
                                statusTextView.setTextColor(android.graphics.Color.YELLOW)
                            } else {
                                val displayText: String
                                val displayColor: Int

                                // ▼ 変更：生データの btn0 ではなく、トグル状態の変数 (isStopModeToggled) で判定する
                                if (isStopModeToggled) {
                                    displayText = "リモコン: 接続中\n   (${rssi} dBm)\n【停止モード】"
                                    displayColor = android.graphics.Color.RED
                                } else {
                                    // 本体のモード名（MODE）に応じて表示を分岐
                                    when (modeStr) {
                                        "ATTACHMENT" -> {
                                            displayText = "リモコン: 接続中\n   (${rssi} dBm)\n【アタッチメントモード】"
                                            displayColor = android.graphics.Color.CYAN
                                        }
                                        else -> {
                                            displayText = "リモコン: 接続中\n   (${rssi} dBm)\n【走行モード】"
                                            displayColor = android.graphics.Color.GREEN
                                        }
                                    }
                                }

                                statusTextView.text = displayText
                                statusTextView.setTextColor(displayColor)
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 2秒間一度もUDPデータが届かなかった場合（ロボットの電源OFF、またはWi-Fi圏外）
                        runOnUiThread {
                            statusTextView.text = "本体マイコン応答なし (通信断絶)"
                            statusTextView.setTextColor(android.graphics.Color.GRAY)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // アプリ終了時などにソケットを確実に閉じる
                socket?.close()
            }
        }
    }

    // JSON形式の文字列からRSSIの値を抽出する関数
    private fun extractRssi(json: String): String {
        return json.substringAfter("\"rssi\": ").substringBefore("}")
    }

    // カメラからのストリームデータを取得し、画像として表示する
    private fun startStream(network: Network, imageView: ImageView) {
        thread {
            val streamUrl = "http://192.168.4.1/stream"
            var lastBoxes = listOf<FloatArray>()
            var skipCounter = 0
            var lostCounter = 0

            val mat = Mat()
            var resultBitmap: Bitmap? = null
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()

            try {
                val url = URL(streamUrl)
                val connection = network.openConnection(url) as HttpURLConnection
                connection.connectTimeout = 5000
                val inputStream = BufferedInputStream(connection.inputStream)
                val buffer = ByteArray(1024 * 512)
                var bufferLength = 0

                // 連続的に受信と描画を繰り返す
                while (true) {
                    val read = inputStream.read(buffer, bufferLength, buffer.size - bufferLength)
                    if (read == -1) break
                    bufferLength += read

                    // JPEGフレームの切り出し（デリミタ検索）
                    var startIndex = -1
                    var endIndex = -1
                    for (i in 0 until bufferLength - 1) {
                        if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xD8.toByte()) startIndex = i
                        if (startIndex != -1 && buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xD9.toByte()) {
                            endIndex = i + 2
                            break
                        }
                    }

                    if (startIndex != -1 && endIndex != -1) {
                        val bitmap = BitmapFactory.decodeByteArray(buffer, startIndex, endIndex - startIndex)
                        if (bitmap != null) {
                            // FPSカウント
                            frameCount++
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastFpsTime >= 1000) {
                                val fps = frameCount
                                runOnUiThread { fpsTextView.text = "FPS: $fps" }
                                frameCount = 0
                                lastFpsTime = currentTime
                            }

                            Utils.bitmapToMat(bitmap, mat)
                            Core.flip(mat, mat, 1)

                            // 【性能最適化】フレームスキップ処理
                            // 8フレームに1回のみ推論実行。リアルタイム性とAIの負荷バランスを最適化
                            skipCounter++
                            if (skipCounter % 8 == 0) {
                                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
                                val newBoxes = runYoloInference(mat)
                                if (newBoxes.isNotEmpty()) {
                                    lastBoxes = newBoxes
                                    lostCounter = 0
                                } else {
                                    // 【UX向上】即座に枠を消すとチラつくため、数フレーム結果を維持
                                    lostCounter++
                                    if (lostCounter > 3) lastBoxes = emptyList()
                                }
                            }

                            // 描画処理：AIが認識した矩形を表示
                            if (lastBoxes.isNotEmpty()) {
                                for (box in lastBoxes) {
                                    val x1 = box[0].toDouble().coerceIn(0.0, mat.cols().toDouble())
                                    val y1 = box[1].toDouble().coerceIn(0.0, mat.rows().toDouble())
                                    val x2 = box[2].toDouble().coerceIn(0.0, mat.cols().toDouble())
                                    val y2 = box[3].toDouble().coerceIn(0.0, mat.rows().toDouble())
                                    val label = CLASS_NAMES.getOrNull(box[5].toInt()) ?: "unknown"

                                    Imgproc.rectangle(mat, Point(x1, y1), Point(x2, y2), Scalar(0.0, 255.0, 0.0, 255.0), 3)
                                    Imgproc.putText(mat, label, Point(x1, y1 - 10), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 255.0, 0.0, 255.0), 2)
                                }
                            }

                            // 【性能最適化】RGB_565利用でメモリ消費とGC頻度を抑制
                            if (resultBitmap == null) {
                                resultBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.RGB_565)
                            }
                            Utils.matToBitmap(mat, resultBitmap)
                            val finalBitmap = resultBitmap
                            runOnUiThread { imageView.setImageBitmap(finalBitmap) }
                            bitmap.recycle()
                        }
                        val remaining = bufferLength - endIndex
                        System.arraycopy(buffer, endIndex, buffer, 0, remaining)
                        bufferLength = remaining
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // YOLOを用いた推論実行関数
    private fun runYoloInference(mat: Mat): List<FloatArray> {
        val session = ortSession ?: return emptyList()
        val env = ortEnvironment ?: return emptyList()

        // 入力サイズ調整（AIモデルの入力形式に合わせる）
        val inputSize = 640
        val imgWidth = mat.cols().toDouble()
        val imgHeight = mat.rows().toDouble()
        val scale = minOf(inputSize / imgWidth, inputSize / imgHeight)
        val newW = (imgWidth * scale).toInt()
        val newH = (imgHeight * scale).toInt()
        val xOffset = (inputSize - newW) / 2
        val yOffset = (inputSize - newH) / 2

        // 【前処理】リサイズとパディング（アスペクト比を維持）
        val resizedMat = Mat()
        Imgproc.resize(mat, resizedMat, Size(newW.toDouble(), newH.toDouble()))
        val paddedMat = Mat(inputSize, inputSize, mat.type(), Scalar(0.0, 0.0, 0.0))
        resizedMat.copyTo(paddedMat.submat(yOffset, yOffset + newH, xOffset, xOffset + newW))

        // FloatBufferへのデータ転送（NCHW形式への変換）
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4)
        byteBuffer.order(java.nio.ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val bitmap = tempBitmap ?: Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.RGB_565).also { tempBitmap = it }
        Utils.matToBitmap(paddedMat, bitmap)

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val channelSize = inputSize * inputSize
        for (i in 0 until channelSize) {
            val color = pixels[i]
            floatBuffer.put(i, ((color shr 16) and 0xFF) / 255.0f)
            floatBuffer.put(i + channelSize, ((color shr 8) and 0xFF) / 255.0f)
            floatBuffer.put(i + 2 * channelSize, ((color and 0xFF) / 255.0f))
        }
        resizedMat.release()
        paddedMat.release()

        // 推論の実行
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        val results = try { session.run(mapOf(session.inputNames.iterator().next() to inputTensor)) } catch (e: Exception) { return emptyList() }

        val output = results[0].value as Array<Array<FloatArray>>
        val allBoxes = mutableListOf<FloatArray>()

        // 【推論後処理】
        // 8400個の候補から信頼度が高いものを抽出
        for (i in 0 until 8400) {
            var maxClassConf = 0.0f
            var classId = 0
            for (c in 0 until 80) {
                val conf = output[0][c + 4][i]
                if (conf > maxClassConf) {
                    maxClassConf = conf
                    classId = c
                }
            }

            // 【精度最適化】動的閾値判定
            // 人(Person=0)は認識しやすく(0.4)、それ以外は誤認識防止のため厳格に(0.7)判定
            val isPerson = (classId == 0)
            val requiredConfidence = if (isPerson) 0.4f else 0.7f

            if (maxClassConf > requiredConfidence) {
                val cx = output[0][0][i]
                val cy = output[0][1][i]
                val w = output[0][2][i]
                val h = output[0][3][i]

                // 元画像座標へのスケーリング復元
                val x1 = ((cx - w / 2) - xOffset) / scale
                val y1 = ((cy - h / 2) - yOffset) / scale
                val x2 = ((cx + w / 2) - xOffset) / scale
                val y2 = ((cy + h / 2) - yOffset) / scale
                allBoxes.add(floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), maxClassConf, classId.toFloat()))
            }
        }
        inputTensor.close()
        results.close()

        // 【最適化】重複検知（NMS）を適用して整理
        return applyNMS(allBoxes, 0.4f)
    }

    // IoU（交差範囲）計算による重複判断ロジック
    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        val x1 = maxOf(box1[0], box2[0])
        val y1 = maxOf(box1[1], box2[1])
        val x2 = minOf(box1[2], box2[2])
        val y2 = minOf(box1[3], box2[3])
        val intersectionArea = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val box1Area = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val box2Area = (box2[2] - box2[0]) * (box2[3] - box2[1])
        val unionArea = box1Area + box2Area - intersectionArea
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    // NMS（非最大値抑制）：複数の重なる矩形を最も確信度の高い1つに集約する
    private fun applyNMS(boxes: MutableList<FloatArray>, threshold: Float): List<FloatArray> {
        val sortedBoxes = boxes.sortedByDescending { it[4] }.toMutableList()
        val selectedBoxes = mutableListOf<FloatArray>()
        while (sortedBoxes.isNotEmpty()) {
            val best = sortedBoxes.removeAt(0)
            selectedBoxes.add(best)
            sortedBoxes.removeIf { candidate -> calculateIoU(best, candidate) > threshold }
        }
        return selectedBoxes
    }
}