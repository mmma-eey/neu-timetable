package cn.kejian.timetable

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import cn.kejian.timetable.data.NeuExcelParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic XHR responses in a synthetic school page: no real network or school credentials. */
@RunWith(AndroidJUnit4::class)
class SchoolBrowserDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun evaluate(view: WebView, script: String): String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync { view.evaluateJavascript(script){result=it;latch.countDown()} }
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun fixture(test: (Activity, WebView, SchoolBrowserDownload) -> Unit) {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,SchoolWebActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var view:WebView
            for (attempt in 0..100){
                var ready=false
                instrumentation.runOnMainSync{webView(activity.window.decorView)?.let{view=it;ready=true}}
                if (ready) break
                Thread.sleep(50)
            }
            val receiver=SchoolBrowserDownload(activity.cacheDir, 1500)
            instrumentation.runOnMainSync {
                view.stopLoading()
                view.webViewClient=object:WebViewClient(){
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=
                        WebResourceResponse("text/html","UTF-8","<html><head><title>fixture</title></head><body>Export fixture</body></html>".byteInputStream())
                }
                view.loadUrl("https://webvpn.neu.edu.cn/synthetic-export")
            }
            for (attempt in 0..100){
                if(evaluate(view,"document.title")=="\"fixture\"")break
                Thread.sleep(50)
            }
            test(activity,view,receiver)
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
    private fun respond(view:WebView,bytes:ByteArray,headers:Map<String,String> = emptyMap()) {
        instrumentation.runOnMainSync {
            view.webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=
                    WebResourceResponse("text/html","UTF-8",200,"OK",headers,bytes.inputStream())
            }
        }
    }
    @Test fun receivesMultipleChunksWithoutMessageBridgeOrFetchAndUsesBrowserCredentials()=fixture { _,view,receiver ->
        val expected=ByteArray(70000){(it%251).toByte()};expected[0]=80;expected[1]=75
        respond(view,expected)
        evaluate(view,"window.fetch=function(){throw new Error('fetch disabled')};window.__kejianExcel=undefined;var send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){window.fixtureCredentials=this.withCredentials;return send.apply(this,arguments)};true")
        var metadata=""
        val file=runBlocking {withContext(Dispatchers.Main){receiver.receive(view,"https://webvpn.neu.edu.cn/synthetic-export.xlsx"){metadata=it}}}
        try {
            assertArrayEquals(expected,file.readBytes())
            assertTrue(metadata,metadata.contains("HTTP 200"));assertTrue(metadata.contains("text/html"))
            assertEquals("true",evaluate(view,"window.fixtureCredentials"))
        } finally {file.delete()}
    }
    @Test fun rejectsActualHtmlWithoutDeclaringLoginExpiredAndRemovesPartialFile()=fixture { activity,view,receiver ->
        respond(view,"<html>Export intermediate page</html>".toByteArray())
        val before=activity.cacheDir.listFiles()!!.filter{it.name.startsWith("school-browser-")}.map{it.name}.toSet()
        val error=runCatching {runBlocking {withContext(Dispatchers.Main){receiver.receive(view,"https://webvpn.neu.edu.cn/export.xlsx") {}}}}.exceptionOrNull()
        assertNotNull(error);assertTrue(error!!.message!!,error.message!!.contains("不能确定是登录失效"))
        assertEquals(before,activity.cacheDir.listFiles()!!.filter{it.name.startsWith("school-browser-")}.map{it.name}.toSet())
    }
    @Test fun enforcesSizeLimitAndRejectsNonSchoolRedirect()=fixture { _,view,receiver ->
        evaluate(view,"window.XMLHttpRequest=function(){this.open=function(m,u){this.responseURL=u};this.getResponseHeader=function(){return ''};this.abort=function(){};this.send=function(){this.status=200;this.response=new ArrayBuffer(10485761);this.onload()}};true")
        val large=runCatching {runBlocking {withContext(Dispatchers.Main){receiver.receive(view,"https://webvpn.neu.edu.cn/export.xlsx") {}}}}.exceptionOrNull()
        assertTrue(large?.message.orEmpty(),large?.message?.contains("10 MB")==true)
        evaluate(view,"window.XMLHttpRequest=function(){this.open=function(){};this.getResponseHeader=function(){return ''};this.abort=function(){};this.send=function(){this.status=200;this.responseURL='https://untrusted.invalid/file.xlsx';this.onload()}};true")
        val outside=runCatching {runBlocking {withContext(Dispatchers.Main){receiver.receive(view,"https://webvpn.neu.edu.cn/export.xlsx") {}}}}.exceptionOrNull()
        assertTrue(outside?.message.orEmpty(),outside?.message?.contains("其他站点")==true)
    }
    @Test fun vpnVirtualHttpResponseUrlIsValidatedAsActualHttpsGateway()=fixture { _,view,receiver ->
        evaluate(view,"window.vpn_rewrite_url=function(url){return url.replace('http://jwxt.neu.edu.cn','https://webvpn.neu.edu.cn/http/synthetic-route')};window.XMLHttpRequest=function(){this.open=function(){};this.getResponseHeader=function(){return 'application/octet-stream'};this.abort=function(){};this.send=function(){this.status=200;this.responseURL='http://jwxt.neu.edu.cn/jwapp/sys/kbapp/api/kbExport/downloadExportFile.do';this.response=new Uint8Array([80,75,3,4]).buffer;this.onload()}};true")
        val file=runBlocking {withContext(Dispatchers.Main){receiver.receive(view,"https://webvpn.neu.edu.cn/file.xlsx") {}}}
        try{assertArrayEquals(byteArrayOf(80,75,3,4),file.readBytes())}finally{file.delete()}
    }
    @Test fun cancelAbortsRequestAndDeletesTemporaryFile()=fixture { activity,view,receiver ->
        evaluate(view,"window.fixtureAborted=false;window.XMLHttpRequest=function(){this.open=function(){};this.send=function(){};this.abort=function(){window.fixtureAborted=true}};true")
        val before=activity.cacheDir.listFiles()!!.filter{it.name.startsWith("school-browser-")}.map{it.name}.toSet()
        runBlocking {
            val job=launch(Dispatchers.Main) {receiver.receive(view,"https://webvpn.neu.edu.cn/export.xlsx") {}}
            delay(400);job.cancelAndJoin()
        }
        assertEquals("true",evaluate(view,"window.fixtureAborted"))
        assertEquals(before,activity.cacheDir.listFiles()!!.filter{it.name.startsWith("school-browser-")}.map{it.name}.toSet())
    }
    @Test fun stalledRequestReportsItsStageAndCleansUp()=fixture { activity,view,receiver ->
        evaluate(view,"window.XMLHttpRequest=function(){this.open=function(){};this.send=function(){};this.abort=function(){}};true")
        val before=activity.cacheDir.listFiles()!!.filter{it.name.startsWith("school-browser-")}.map{it.name}.toSet()
        val started=System.nanoTime()
        val error=runCatching {runBlocking {withContext(Dispatchers.Main){receiver.receive(view,"https://webvpn.neu.edu.cn/export.xlsx") {}}}}.exceptionOrNull()
        assertTrue(error?.message.orEmpty(),error?.message?.contains("等待服务器响应")==true)
        assertTrue("Stalled request did not fail promptly",System.nanoTime()-started<5_000_000_000L)
        assertEquals(before,activity.cacheDir.listFiles()!!.filter{it.name.startsWith("school-browser-")}.map{it.name}.toSet())
    }
    @Test fun loginAndWordFormsRemainNativeSubmissions()=fixture { activity,view,_ ->
        val submitted=AtomicInteger()
        instrumentation.runOnMainSync{
            view.webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse{
                    if(request.isForMainFrame&&request.method=="POST")submitted.incrementAndGet()
                    return WebResourceResponse("text/html","UTF-8","<html><head><title>fixture</title></head><body>Native form response</body></html>".byteInputStream())
                }
            }
        }
        listOf("/tpass/login" to "02","/jwapp/sys/kbapp/api/kbExport/downloadExportFile.do" to "01").forEachIndexed { index,(action,type)->
            evaluate(view,"(function(){var f=document.createElement('form');f.method='post';f.action='$action';f.target='_self';for(var p of [['key','synthetic'],['EXPORTTYPE','$type']]){var i=document.createElement('input');i.name=p[0];i.value=p[1];f.appendChild(i);}document.body.appendChild(f);f.submit();return true;})()")
            for(attempt in 0..100){if(submitted.get()==index+1)break;Thread.sleep(30)}
            assertEquals(index+1,submitted.get())
            for(attempt in 0..100){if(evaluate(view,"document.title")=="\"fixture\"")break;Thread.sleep(30)}
        }
        instrumentation.runOnMainSync{assertFalse(activity.isFinishing)}
    }
    @Test fun downloadListenerReceivesAndValidatesWorkbook()=downloadThroughListener(false)
    @Test fun blankDownloadPopupRestoresOriginalPageAndImportsWorkbook()=downloadThroughListener(true)
    @Test fun neuGenerationJsonThenPostFormRetainsKeyAndImportsWorkbook()=downloadThroughListener(false,true)
    private fun downloadThroughListener(popup:Boolean,postFlow:Boolean=false)=fixture { activity,view,_ ->

        val bytes=ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                fun part(name:String,xml:String){zip.putNextEntry(ZipEntry(name));zip.write(xml.toByteArray());zip.closeEntry()}
                part("xl/workbook.xml","<workbook><sheets><sheet name=\"Test\" id=\"rId1\"/></sheets></workbook>")
                val days=listOf("星期一","星期二","星期三","星期四","星期五","星期六","星期日")
                val headers=days.mapIndexed{i,day->"<c r=\"${('B'.code+i).toChar()}1\" t=\"inlineStr\"><is><t>$day</t></is></c>"}.joinToString("")
                part("xl/worksheets/sheet1.xml","<worksheet><sheetData><row r=\"1\">$headers</row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>第一节</t></is></c><c r=\"B2\" t=\"inlineStr\"><is><t>课程甲 A123456\n1-2周 教师甲 浑南校区 教室甲</t></is></c></row></sheetData></worksheet>")
            }
        }.toByteArray()
        val before=activity.cacheDir.listFiles()!!.map{it.name}.toSet()
        val headers=mapOf("Content-Disposition" to "attachment; filename=synthetic.xlsx", "Cache-Control" to "no-store")
        val generated=AtomicInteger();val posted=AtomicInteger();val wrongGet=AtomicInteger();val bodyValid=AtomicBoolean()
        if(postFlow){
            instrumentation.runOnMainSync{
                view.webViewClient=object:WebViewClient(){
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse{
                        if(request.url.path.orEmpty().endsWith("genMyExportFile.do")){
                            if(request.method=="POST")generated.incrementAndGet()
                            return WebResourceResponse("application/json","UTF-8","""{"code":"0","datas":{"genMyExportFile":"synthetic+key/with space"}}""".byteInputStream())
                        }
                        if(request.url.path.orEmpty().endsWith("downloadExportFile.do")){
                            if(request.method=="POST")posted.incrementAndGet() else wrongGet.incrementAndGet()
                            bodyValid.set(request.requestHeaders.entries.any{it.key.equals("X-Synthetic-Body-Valid",true)&&it.value=="yes"})
                            if(request.method=="POST")return WebResourceResponse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","UTF-8",200,"OK",headers,bytes.inputStream())
                            return WebResourceResponse("text/html","UTF-8",405,"Method Not Allowed",emptyMap(),"<html>POST required</html>".byteInputStream())
                        }
                        return WebResourceResponse("text/html","UTF-8","fixture".byteInputStream())
                    }
                }
            }
        }else respond(view,bytes,headers)
        var exporter=view
        if(popup){
            evaluate(view,"window.open('about:blank','export_fixture');true")
            for(attempt in 0..100){
                instrumentation.runOnMainSync {webView(activity.window.decorView)?.let{exporter=it}}
                if(exporter!==view)break
                Thread.sleep(50)
            }
            assertNotSame(view,exporter)
            instrumentation.runOnMainSync {assertFalse("Fixture must reproduce a detached original page",view.isAttachedToWindow)}
            respond(exporter,bytes,headers)
        }
        if(postFlow){
            assertEquals("\"object\"",evaluate(view,"typeof window.__kejianNeuExport"))
            evaluate(view,"""
              (function(){
                var prefix='/https/synthetic-proxy/jwapp',key='synthetic+key/with space';
                var open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(method,url){this.fixtureUrl=String(url);return open.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(body){
                  if(this.fixtureUrl.indexOf('downloadExportFile.do')>=0){var p=new URLSearchParams(body);this.setRequestHeader('X-Synthetic-Body-Valid',p.get('key')===key&&p.get('EXPORTTYPE')==='02'&&p.get('XNXQDM')==='synthetic-term'&&p.get('genExportFileUrl')==='/sys/kbapp/api/wdkbcx/genMyExportFile.do'&&JSON.parse(p.get('querySetting'))[0].name==='XNXQDM'?'yes':'no');}
                  return send.apply(this,arguments);
                };
                var generated=new XMLHttpRequest();generated.open('POST',prefix+'/sys/kbapp/api/wdkbcx/genMyExportFile.do',false);generated.send('EXPORTTYPE=02&XNXQDM=synthetic-term');
                var reply=JSON.parse(generated.responseText);
                var fields={genExportFileUrl:'/sys/kbapp/api/wdkbcx/genMyExportFile.do',XNXQDM:'synthetic-term',EXPORTTYPE:'02',querySetting:JSON.stringify([{name:'XNXQDM',value:'synthetic-term',linkOpt:'AND',builder:'equal'}]),key:reply.datas.genMyExportFile};
                var form=document.createElement('form');form.method='post';form.action=prefix+'/sys/kbapp/api/kbExport/downloadExportFile.do';form.target='_blank';form.id='scheduleWord';form.style.display='none';
                for(var name in fields){var input=document.createElement('input');input.name=name;input.value=fields[name];form.appendChild(input);}
                document.body.appendChild(form);form.submit();form.remove();return true;
              })();
            """.trimIndent())
        }else instrumentation.runOnMainSync {exporter.loadUrl("https://webvpn.neu.edu.cn/synthetic-file.xlsx")}
        for(attempt in 0..100){
            var done=false
            instrumentation.runOnMainSync {done=activity.isFinishing}
            if(done)break
            Thread.sleep(100)
        }
        val received=activity.cacheDir.listFiles()!!.singleOrNull{it.name.startsWith("school-browser-") && it.name !in before}
        assertNotNull("DownloadListener did not produce a workbook",received)
        try {
            assertArrayEquals(bytes,received!!.readBytes())
            val preview=received.inputStream().use{NeuExcelParser().parse(it)}
            assertEquals(1,preview.lessons.size);assertEquals("课程甲",preview.lessons.single().title)
            assertEquals("教师甲",preview.lessons.single().teacher);assertEquals("教室甲",preview.lessons.single().room)
            instrumentation.runOnMainSync {assertTrue(activity.isFinishing)}
            if(postFlow){assertEquals(1,generated.get());assertEquals(1,posted.get());assertEquals(0,wrongGet.get());assertTrue("POST key, term or encoding was lost",bodyValid.get())}
        } finally {received?.delete()}
    }
}
