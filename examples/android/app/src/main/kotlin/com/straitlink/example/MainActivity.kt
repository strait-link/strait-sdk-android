package com.straitlink.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.strait.sdk.AppLifecycleState
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val app get() = application as App
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var log: TextView
    private var offLink: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this)
        val purchase = Button(this).apply { text = "Log a purchase event" }
        // Debug builds only: re-run the deferred check. With test mode on in the dashboard
        // (Get started), it gets your latest tap, like a first open after install.
        val recheck = Button(this).apply { text = "Check deferred link (test mode)" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            fitsSystemWindows = true // Android 15 draws edge to edge: keep the buttons out from under the bars
            addView(purchase)
            if (BuildConfig.DEBUG) addView(recheck)
            addView(log)
        })

        if (!app.configured) {
            show("Copy strait.example.properties to strait.properties, set your publishable key, rebuild.")
            return
        }
        val strait = app.strait
        // Direct links (straitsample://… or verified https links) and the deferred link after an install.
        offLink = strait.onLink { e -> runOnUiThread { show("${e.kind} link (${e.route}, ${e.appState}): matched=${e.matched} path=${e.path}") } }
        // The launch link (or null), then the deferred check once per install.
        if (savedInstanceState == null) strait.start(intent?.dataString)

        recheck.setOnClickListener { io.execute { strait.checkDeferred() } } // the result arrives in onLink above

        purchase.setOnClickListener {
            io.execute {
                val ok = strait.trackEvent("purchase", value = 4.99, currency = "USD", eventId = "order_${System.currentTimeMillis()}")
                runOnUiThread { show("event purchase sent: $ok") }
            }
        }
    }

    // A link while the app is running (background or foreground).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (app.configured) intent.dataString?.let(app.strait::handleUrl)
    }

    // App-state labels (closed / background / foreground) for each link.
    override fun onResume() { super.onResume(); if (app.configured) app.strait.onAppState(AppLifecycleState.ACTIVE) }
    override fun onPause() { super.onPause(); if (app.configured) app.strait.onAppState(AppLifecycleState.INACTIVE) }
    override fun onStop() { super.onStop(); if (app.configured) app.strait.onAppState(AppLifecycleState.BACKGROUND) }

    // The client outlives this Activity (rotation recreates it): drop the listener so it isn't leaked.
    override fun onDestroy() { offLink?.invoke(); io.shutdown(); super.onDestroy() }

    // Also to logcat (tag StraitExample): scripts/r8-smoke.sh reads the LinkEvent line from there.
    private fun show(line: String) { Log.i("StraitExample", line); log.append(line + "\n") }
}
