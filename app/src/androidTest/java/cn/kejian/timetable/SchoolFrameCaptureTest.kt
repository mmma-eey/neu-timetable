package cn.kejian.timetable

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SchoolFrameCaptureTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun evaluate(view:WebView,code:String):String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync{view.evaluateJavascript(code){result=it;latch.countDown()}}
        assertTrue(latch.await(4,TimeUnit.SECONDS));return result
    }
    private fun waitFor(view:WebView,expression:String){
        repeat(100){if(evaluate(view,expression)=="true")return;Thread.sleep(50)}
        fail("Dynamic frame did not reach expected state")
    }
    private fun fixture(test:(WebView)->Unit){
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view:WebView
        try{
            instrumentation.runOnMainSync{
                view=WebView(activity);view.settings.javaScriptEnabled=true;view.settings.domStorageEnabled=true
                activity.addContentView(view,ViewGroup.LayoutParams(-1,-1))
                view.webViewClient=object:WebViewClient(){
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=
                        WebResourceResponse("text/html","UTF-8","<html><head><title>frame-fixture</title></head><body>fixture</body></html>".byteInputStream())
                }
                // Deliberately do not register document-start injection: reproduce the real
                // dynamic frame that had no hook despite the outer page being configured.
                view.loadUrl("https://webvpn.neu.edu.cn/synthetic-outer")
            }
            waitFor(view,"document.title==='frame-fixture'")
            instrumentation.runOnMainSync{SchoolExportCapture.installAfterLoad(view)}
            waitFor(view,"!!window.__kejianNeuExport")
            test(view)
        }finally{
            instrumentation.runOnMainSync{view.stopLoading();(view.parent as? ViewGroup)?.removeView(view);view.destroy();activity.finish()}
        }
    }
    @Test fun lateIframeAndSubsequentNavigationAreInstalled()=fixture{view->
        evaluate(view,"var frame=document.createElement('iframe');frame.id='schedule';frame.src='/synthetic-frame-one';document.body.appendChild(frame);true")
        waitFor(view,"document.querySelector('iframe').contentWindow.location.pathname==='/synthetic-frame-one'&&!!document.querySelector('iframe').contentWindow.__kejianNeuExport")
        evaluate(view,"document.querySelector('iframe').src='/synthetic-frame-two';true")
        waitFor(view,"document.querySelector('iframe').contentWindow.location.pathname==='/synthetic-frame-two'&&!!document.querySelector('iframe').contentWindow.__kejianNeuExport")
        assertEquals("2",evaluate(view,"document.querySelector('iframe').contentWindow.__kejianNeuExport.version"))
    }
    @Test fun vpnDecodedFormActionUsesGatewayRewriteAndKeepsPostFields()=fixture{view->
        evaluate(view,"var frame=document.createElement('iframe');frame.src='/synthetic-vpn-frame';document.body.appendChild(frame);true")
        waitFor(view,"document.querySelector('iframe').contentWindow.location.pathname==='/synthetic-vpn-frame'&&document.querySelector('iframe').contentWindow.document.title==='frame-fixture'&&!!document.querySelector('iframe').contentWindow.__kejianNeuExport")
        evaluate(view,"""
          (function(){
            var w=document.querySelector('iframe').contentWindow;
            var descriptor=Object.getOwnPropertyDescriptor(w.HTMLFormElement.prototype,'action');
            Object.defineProperty(w.HTMLFormElement.prototype,'action',{configurable:true,get:function(){return descriptor.get.call(this).replace('https://webvpn.neu.edu.cn','https://jwxt.neu.edu.cn');},set:descriptor.set});
            w.vpn_rewrite_url=function(url){return url.replace('https://jwxt.neu.edu.cn','https://webvpn.neu.edu.cn/https/synthetic-route');};
            var form=w.document.createElement('form');form.action='/jwapp/sys/kbapp/api/kbExport/downloadExportFile.do';form.method='post';form.target='_blank';
            var values={key:'synthetic+key / value',EXPORTTYPE:'02',XNXQDM:'fixture-term'};
            for(var name in values){var input=w.document.createElement('input');input.name=name;input.value=values[name];form.appendChild(input);}
            w.document.body.appendChild(form);form.submit();form.remove();return true;
          })();
        """.trimIndent())
        val request=runBlocking{withContext(Dispatchers.Main){SchoolExportCapture.poll(view)}}
        assertNotNull(request)
        assertEquals("https://webvpn.neu.edu.cn/https/synthetic-route/jwapp/sys/kbapp/api/kbExport/downloadExportFile.do",request!!.url)
        val values=android.net.Uri.parse("https://local.invalid/?${request.body}")
        assertEquals("synthetic+key / value",values.getQueryParameter("key"))
        assertEquals("02",values.getQueryParameter("EXPORTTYPE"))
        assertEquals("fixture-term",values.getQueryParameter("XNXQDM"))
    }
}
