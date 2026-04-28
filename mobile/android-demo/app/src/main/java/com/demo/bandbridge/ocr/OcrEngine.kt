package com.demo.bandbridge.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.demo.bandbridge.image.LoadedImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine

class OcrEngine(
    private val context: Context
) {
    suspend fun recognize(uri: Uri): String = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, uri)
        recognizeInternal(image)
    }

    suspend fun recognize(image: LoadedImage): String = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ?: error("无法解码图片数据。")
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizeInternal(inputImage)
    }

    private suspend fun recognizeInternal(image: InputImage): String {
        val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val chineseText = chineseRecognizer.process(image).awaitText().trim()
        if (chineseText.isNotEmpty()) {
            chineseRecognizer.close()
            latinRecognizer.close()
            return chineseText
        }

        val latinText = latinRecognizer.process(image).awaitText().trim()
        chineseRecognizer.close()
        latinRecognizer.close()
        return latinText
    }
}

private suspend fun <T> Task<T>.awaitResult(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
    }
}

private suspend fun Task<com.google.mlkit.vision.text.Text>.awaitText(): String {
    return awaitResult().text
}
