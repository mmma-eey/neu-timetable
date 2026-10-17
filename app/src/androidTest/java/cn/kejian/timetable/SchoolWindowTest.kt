package cn.kejian.timetable

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic school-origin HTML exercises window handling without school credentials. */
@RunWith(AndroidJUnit4::class)
class SchoolWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            var done = false
            instrumentation.runOnMainSync { done = predicate() }
            if (done) return
            Thread.sleep(100)
        }
        fail("WebView condition timed out")
    }
    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { value = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return value
    }
    @Test fun asynchronousPopupRetainsOpenerAndReturnsToRoot() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SchoolWebActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor { webView(activity.window.decorView) != null }
            lateinit var root: WebView
            instrumentation.runOnMainSync {
                root = webView(activity.window.decorView)!!
                root.stopLoading()
                val original=root.webViewClient
                // Use a real HTTPS navigation with a local response to exercise school origins.
                root.webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=original.shouldOverrideUrlLoading(view,request)
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse =
                        WebResourceResponse("text/html","UTF-8","<html><head><title>root-ready</title></head><body>fixture</body></html>".byteInputStream())
                }
                root.loadUrl("https://webvpn.neu.edu.cn/synthetic-window-check")
            }
            waitFor { root.title == "root-ready" && root.url?.startsWith("https://webvpn.neu.edu.cn/") == true }
            assertTrue(evaluate(root, "document.location.href"), evaluate(root, "document.location.href").contains("webvpn.neu.edu.cn"))
            instrumentation.runOnMainSync { assertTrue("Root URL: ${root.url}", root.url?.startsWith("https://webvpn.neu.edu.cn/") == true) }
            // No user gesture: school SSO may open a window after an asynchronous request.
            evaluate(root, "setTimeout(function(){window.open('about:blank','synthetic_sso');},100); 'scheduled'")
            waitFor { webView(activity.window.decorView)?.let { it !== root } == true }
            lateinit var child: WebView
            instrumentation.runOnMainSync {
                child = webView(activity.window.decorView)!!
                assertTrue(child.settings.javaScriptEnabled)
                assertTrue(child.settings.domStorageEnabled)
                assertTrue(CookieManager.getInstance().acceptThirdPartyCookies(child))
            }
            waitFor { child.url == "about:blank" }
            assertEquals("true", evaluate(child, "window.opener !== null"))
            assertEquals("42", evaluate(child, "6 * 7"))
            assertEquals("\"root-ready\"", evaluate(root, "document.title"))
            instrumentation.runOnMainSync { child.evaluateJavascript("window.close()", null) }
            waitFor { webView(activity.window.decorView) === root }
            assertEquals("\"root-ready\"", evaluate(root, "document.title"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
