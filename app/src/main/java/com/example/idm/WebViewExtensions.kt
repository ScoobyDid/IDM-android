package com.example.idm

import android.webkit.WebView



fun WebView.showJsToast(
    text: String,
    cssClasses: List<String>? = null,
    durationMs: Int? = null
) {
    val escapedText = text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    val jsCssClassesArray = cssClasses?.joinToString(prefix = "['", postfix = "']", separator = "', '") {
        // escape each class name as well, though less critical than free text
        it.replace("'", "\\'")
    } ?: "null"

    val jsDuration = durationMs?.toString() ?: "null" // If durationMs is null, pass JavaScript 'null'

    val script = "Toast.feedback(_lang('$escapedText'), $jsCssClassesArray, $jsDuration);"

    this.post {
        this.evaluateJavascript(script){}
    }
}





fun WebView.showJsError(
    text: String,
    reportError: Boolean? = true
) {
    val escapedText = text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    val script = "ErrorHandler.feedback(_lang('$escapedText'), $reportError);"

    this.post {
        this.evaluateJavascript(script){}
    }
}

