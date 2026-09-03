package com.scan128.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.Code128Writer
import com.scan128.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var isProcessing = false
    private var lastCandidate = ""
    private var stableCount = 0
    private var lastConfirmed = ""
    private var lastConfirmedAt = 0L

    private val stableRequired = 3
    private val duplicateCooldownMs = 4000L

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.statusText.text = "Permissão da câmara recusada."
                binding.cameraState.text = "Sem permissão"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.generateButton.setOnClickListener {
            generateBarcode(binding.numberInput.text.toString())
        }

        binding.copyButton.setOnClickListener {
            val value = digitsOnly(binding.numberInput.text.toString())
            if (value.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Scan128", value))
                Toast.makeText(this, "Número copiado.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.numberInput.setOnEditorActionListener { _, _, _ ->
            generateBarcode(binding.numberInput.text.toString())
            true
        }

        renderHistory()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.statusText.text = "A iniciar câmara…"

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                binding.cameraState.text = "Scanner ativo"
                binding.statusText.text = "Aponta o número para dentro da caixa."
            } catch (e: Exception) {
                binding.statusText.text = "Erro ao iniciar a câmara: ${e.message}"
                binding.cameraState.text = "Erro"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!binding.autoSwitch.isChecked || isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // ML Kit devolve bounding boxes no referencial da imagem já rodada.
                val rotation = imageProxy.imageInfo.rotationDegrees
                val frameWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                val frameHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

                // A moldura visual ocupa aproximadamente 90% da largura e 35% da altura, centrada.
                val roiLeft = frameWidth * 0.05f
                val roiRight = frameWidth * 0.95f
                val roiTop = frameHeight * 0.325f
                val roiBottom = frameHeight * 0.675f

                val candidates = mutableListOf<String>()

                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val centerX = box.exactCenterX()
                        val centerY = box.exactCenterY()

                        // Só considera linhas cujo centro esteja dentro da caixa de leitura.
                        if (centerX < roiLeft || centerX > roiRight ||
                            centerY < roiTop || centerY > roiBottom) {
                            continue
                        }

                        // Divide a linha para evitar juntar números de etiquetas diferentes.
                        val chunks = Regex("""\d{8,}""")
                            .findAll(line.text.replace(" ", ""))
                            .map { it.value }
                            .toList()

                        candidates.addAll(chunks)
                    }
                }

                // Prioriza os formatos que aparecem mais frequentemente no teu uso:
                // 356012... primeiro, depois 00..., e finalmente qualquer sequência longa.
                val raw = candidates
                    .distinct()
                    .sortedWith(
                        compareByDescending<String> {
                            when {
                                it.startsWith("356012") -> 3
                                it.startsWith("00") -> 2
                                else -> 1
                            }
                        }.thenByDescending { it.length }
                    )
                    .firstOrNull()
                    .orEmpty()

                if (raw.isBlank()) {
                    stableCount = 0
                    lastCandidate = ""
                    runOnUiThread {
                        binding.candidateText.text = "Sem número válido dentro da caixa."
                    }
                    return@addOnSuccessListener
                }

                if (raw == lastCandidate) {
                    stableCount++
                } else {
                    lastCandidate = raw
                    stableCount = 1
                }

                runOnUiThread {
                    binding.candidateText.text = "A validar: $raw  ($stableCount/$stableRequired)"
                }

                if (stableCount >= stableRequired) {
                    stableCount = 0
                    lastCandidate = ""
                    runOnUiThread {
                        confirmNumber(raw)
                    }
                }
            }
            .addOnFailureListener {
                runOnUiThread {
                    binding.statusText.text = "Erro no OCR: ${it.message}"
                }
            }
            .addOnCompleteListener {
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun confirmNumber(value: String) {
        val now = System.currentTimeMillis()

        if (value == lastConfirmed && now - lastConfirmedAt < duplicateCooldownMs) {
            binding.statusText.text = "Código já confirmado. Aponta para outro número."
            return
        }

        lastConfirmed = value
        lastConfirmedAt = now

        binding.numberInput.setText(value)
        binding.candidateText.text = ""
        binding.statusText.text = "Confirmado automaticamente: $value"

        generateBarcode(value, save = true)

        if (binding.vibrationSwitch.isChecked) {
            vibrate()
        }
    }

    private fun generateBarcode(rawValue: String, save: Boolean = true) {
        val value = digitsOnly(rawValue)

        if (value.isBlank()) {
            Toast.makeText(this, "Introduz um número.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.numberInput.setText(value)

        try {
            val width = 1200
            val height = 330
            val bitMatrix = Code128Writer().encode(
                value,
                BarcodeFormat.CODE_128,
                width,
                height
            )

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                    )
                }
            }

            binding.barcodeImage.setImageBitmap(bitmap)

            if (save) {
                addHistory(value)
            }
        } catch (e: Exception) {
            binding.statusText.text = "Erro ao gerar Code 128: ${e.message}"
        }
    }

    private fun digitsOnly(value: String): String =
        value.filter { it.isDigit() }

    private fun vibrate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 90), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 90), -1)
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun prefs() =
        getSharedPreferences("scan128", Context.MODE_PRIVATE)

    private fun getHistory(): MutableList<String> {
        val raw = prefs().getString("history", "") ?: ""
        return raw.split("|")
            .filter { it.isNotBlank() }
            .toMutableList()
    }

    private fun addHistory(value: String) {
        val items = getHistory()
        items.remove(value)
        items.add(0, value)

        val trimmed = items.take(12)
        prefs().edit()
            .putString("history", trimmed.joinToString("|"))
            .apply()

        renderHistory()
    }

    private fun renderHistory() {
        binding.historyContainer.removeAllViews()

        getHistory().forEach { value ->
            val button = Button(this).apply {
                text = value
                isAllCaps = false
                setOnClickListener {
                    binding.numberInput.setText(value)
                    generateBarcode(value, save = false)
                    binding.statusText.text = "Código carregado do histórico."
                }
            }
            binding.historyContainer.addView(button)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
