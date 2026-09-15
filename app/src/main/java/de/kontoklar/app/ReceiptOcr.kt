package de.kontoklar.app

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

fun scanReceipt(context: Context, imageUri: Uri, onResult: (ReceiptScan) -> Unit, onError: (String) -> Unit) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    runCatching { InputImage.fromFilePath(context, imageUri) }
        .onSuccess { image ->
            recognizer.process(image)
                .addOnSuccessListener { result -> onResult(parseReceiptText(result.text)) }
                .addOnFailureListener { error -> onError(error.localizedMessage ?: "Text auf dem Beleg konnte nicht erkannt werden.") }
                .addOnCompleteListener { recognizer.close() }
        }
        .onFailure {
            recognizer.close()
            onError(it.localizedMessage ?: "Das Belegbild konnte nicht geöffnet werden.")
        }
}
