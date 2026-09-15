package com.usesonny.sdk

import android.content.Intent
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SonnyChatViewTest {
    @Test fun modalKeepsContentInsideSystemBarsAndKeyboardWithoutDoubleInsets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Sonny.configure(InstrumentationRegistry.getInstrumentation().targetContext, "lifecycle-site", "lifecycle-key")
        }
        ActivityScenario.launch(SonnyChatActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
                val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
                val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
                fun applyInsets(keyboard: Int): androidx.core.view.WindowInsetsCompat {
                    val insets = androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(bars, androidx.core.graphics.Insets.of(0, 48, 0, 24))
                        .setInsets(ime, androidx.core.graphics.Insets.of(0, 0, 0, keyboard)).build()
                    return androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
                }
                val result = applyInsets(220)
                assertEquals(48, root.paddingTop)
                assertEquals(220, root.paddingBottom)
                assertEquals(androidx.core.graphics.Insets.NONE, result.getInsets(bars or ime))
                applyInsets(0)
                assertEquals("Dismissing the keyboard must restore the bottom bar space", 24, root.paddingBottom)
            }
        }
    }

    @Test fun repeatedPresentationReusesTheChatActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val created = mutableListOf<SonnyChatActivity>()
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is SonnyChatActivity && stage == androidx.test.runner.lifecycle.Stage.CREATED) created.add(activity)
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
        try {
            ActivityScenario.launch(SonnyTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    Sonny.configure(activity, "lifecycle-site", "lifecycle-key")
                    Sonny.present(activity)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { Sonny.present(created.single()) }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { assertEquals("Presenting twice must leave one modal", 1, created.size) }
            }
        } finally {
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback); created.forEach { it.finish() } }
        }
    }

    @Test fun reattachedViewCanStillOpenTheSystemFilePicker() {
        ActivityScenario.launch(SonnyTestActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(
                android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addDataType("*/*"); addCategory(Intent.CATEGORY_OPENABLE) },
                android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
            try {
                scenario.onActivity { activity ->
                    Sonny.configure(activity, "lifecycle-site", "lifecycle-key")
                    val root = FrameLayout(activity)
                    activity.setContentView(root)
                    val view = SonnyChatView(activity)
                    root.addView(view)
                    root.removeView(view)
                    root.addView(view)
                    val chooser = Sonny.host!!.fileChooser
                    assertNotNull("Reattached chat must offer attachments", chooser)
                    assertTrue(chooser!!({}, fileParameters()))
                }
                instrumentation.waitForIdleSync()
                assertEquals("Attachments must use Android's document picker", 1, monitor.hits)
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun removingOldViewKeepsTheActiveViewsPicker() {
        ActivityScenario.launch(SonnyTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Sonny.configure(activity, "lifecycle-site", "lifecycle-key")
                val root = FrameLayout(activity)
                activity.setContentView(root)
                val first = SonnyChatView(activity)
                root.addView(first)
                val second = SonnyChatView(activity)
                root.addView(second)
                val activeChooser = Sonny.host!!.fileChooser
                root.removeView(first)
                assertSame("Disposing an older view must preserve the active picker", activeChooser, Sonny.host!!.fileChooser)
                assertSame(second, Sonny.host!!.webView.parent)
            }
        }
    }

    private fun fileParameters() = object : WebChromeClient.FileChooserParams() {
        override fun getMode() = MODE_OPEN
        override fun getAcceptTypes() = arrayOf("image/*")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT)
    }
}
