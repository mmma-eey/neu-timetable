package cn.kejian.timetable

import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/** Browser-owned XHR retains the school session. Native code pulls bounded chunks explicitly. */
internal class SchoolBrowserDownload(private val cacheDir: File, private val timeoutMillis: Long = 25000) {
    private var busy = false

    private suspend fun evaluate(view: WebView, script: String): String = withTimeout(3000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result ?: "null") }
            }
        }
    }

    suspend fun receive(view: WebView, url: String, method:String="GET", body:String?=null, report: (String) -> Unit): File {
        require(schoolUrl(url)) { "只接收学校 HTTPS 导出" }
        require(method=="GET" || method=="POST" && Uri.parse(url).path.orEmpty().endsWith("/sys/kbapp/api/kbExport/downloadExportFile.do") && body!=null && body.length<=65536) { "导出请求格式尚未适配" }
        val page=Uri.parse(view.url.orEmpty());val target=Uri.parse(url)
        require(schoolUrl(view.url.orEmpty()) && page.host==target.host && page.port==target.port) { "原课表页已跳转，请回到课表重新导出" }
        check(!busy) { "已有文件正在接收" }
        busy = true
        val id = UUID.randomUUID().toString()
        var file: File? = null
        var complete = false
        var stage = "启动网页接收"
        try {
            report(stage)
            // No origin-scoped bridge or acknowledgement is required on the handset's WebView.
            val started = evaluate(view, script(url, id,method,body))
            require(started == "\"started\"") { "网页接收脚本未能启动，请返回课表页重试" }
            file = File.createTempFile("school-browser-", ".xlsx", cacheDir)
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    file.outputStream().buffered().use { output ->
                        var total = 0
                        var expected = 0
                        var lastReport = ""
                        while (true) {
                            val raw = evaluate(view, pollScript(id))
                            val event = runCatching { JSONObject(raw) }.getOrElse { throw IOException("网页数据转换失败，请返回课表重试") }
                            val phase = event.optString("phase")
                            val status = event.optInt("status")
                            val mime = event.optString("mime").take(80)
                            val detail = if (status > 0) "（HTTP $status，$mime）" else ""
                            when (phase) {
                                "requesting" -> stage = "网页已启动，等待服务器响应"
                                "downloading" -> stage = "服务器正在发送：${event.optInt("loaded") / 1024} KB$detail"
                                "ready" -> {
                                    require(event.getInt("seq") == expected++) { "接收分块顺序异常" }
                                    val bytes = Base64.decode(event.getString("data"), Base64.NO_WRAP)
                                    require(bytes.size in 1..32768) { "接收分块大小异常" }
                                    total += bytes.size; require(total <= MAX_BYTES) { "文件超过 10 MB" }
                                    output.write(bytes)
                                    stage = "已接收 ${total / 1024} KB$detail"
                                }
                                "done" -> {
                                    require(total > 0 && total == event.getInt("size")) { "文件接收不完整" }
                                    withContext(Dispatchers.Main) { report("接收完成，正在校验 Excel$detail") }
                                    break
                                }
                                "error" -> throw IOException(when (event.optString("code")) {
                                    "html" -> "学校返回了 HTML 网页，尚未收到 Excel$detail。这不能确定是登录失效，可在浏览器下载后手动导入。"
                                    "file" -> "收到的内容不是 XLSX 文件$detail，请使用教务系统的 Excel 导出"
                                    "size" -> "文件超过 10 MB"
                                    "http" -> "导出请求失败$detail，请返回课表重试"
                                    "origin" -> "导出跳转到其他站点，当前接收方式尚未适配"
                                    "networkTimeout" -> "服务器在 20 秒内未完成导出响应$detail，请返回课表重试或手动导入"
                                    "unsupported" -> "当前网页环境不支持文件接收，请使用手动 Excel 导入"
                                    else -> "网页请求失败$detail，可能是网络或跨站限制，请返回课表重试或手动导入"
                                })
                                else -> throw IOException("网页接收状态丢失，页面可能已跳转，请返回课表重试")
                            }
                            if (stage != lastReport) { lastReport = stage; withContext(Dispatchers.Main) { report(stage) } }
                            if (phase != "ready") delay(150)
                        }
                    }
                }
            }
            complete = true
            return file
        } catch (e: TimeoutCancellationException) {
            throw IOException("接收未能完成，停在：$stage。请返回课表重试或手动导入 Excel", e)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                // Do not wait for a destroyed or unresponsive renderer when cancelling.
                view.evaluateJavascript("(function(){var s=window[${JSONObject.quote(id)}];if(s){s.cancelled=true;if(s.xhr)s.xhr.abort();delete window[${JSONObject.quote(id)}];}})()", null)
            }
            busy = false
            if (!complete) file?.delete()
        }
    }

    private fun script(url: String, id: String,method:String,body:String?): String = """
        (function(){
          var key=${JSONObject.quote(id)}, target=${JSONObject.quote(url)};
          var s={phase:'requesting',status:0,mime:'',loaded:0,offset:0,seq:0}; window[key]=s;
          function fail(code){if(!s.cancelled && s.phase!=='error'){s.phase='error';s.code=code;}}
          try {
            if(typeof XMLHttpRequest!=='function'){fail('unsupported');return 'started';}
            var x=new XMLHttpRequest();s.xhr=x;x.open(${JSONObject.quote(method)},target,true);x.responseType='arraybuffer';x.withCredentials=true;x.timeout=20000;
            if(${JSONObject.quote(method)}==='POST')x.setRequestHeader('Content-Type','application/x-www-form-urlencoded;charset=UTF-8');
            x.onreadystatechange=function(){if(x.readyState===2){s.status=x.status;s.mime=(x.getResponseHeader('Content-Type')||'未提供').substring(0,80);if(Number(x.getResponseHeader('Content-Length'))>$MAX_BYTES){fail('size');x.abort();}else if(s.phase!=='error')s.phase='downloading';}};
            x.onprogress=function(e){s.loaded=e.loaded;if(e.loaded>$MAX_BYTES){fail('size');x.abort();}else if(s.phase!=='error')s.phase='downloading';};
            x.onerror=function(){fail('network');};x.ontimeout=function(){fail('networkTimeout');};
            x.onload=function(){
              try {
              if(s.cancelled||s.phase==='error')return;
              s.status=x.status;s.mime=(x.getResponseHeader('Content-Type')||'未提供').substring(0,80);
              if(x.status<200||x.status>=300){fail('http');return;}
              var finalUrl=x.responseURL||target;
              if(typeof window.vpn_rewrite_url==='function')finalUrl=window.vpn_rewrite_url(finalUrl);
              var final=new URL(finalUrl,location.href);
              if(final.protocol!=='https:' || !(final.hostname==='neu.edu.cn'||final.hostname.endsWith('.neu.edu.cn'))){fail('origin');return;}
              var bytes=new Uint8Array(x.response||new ArrayBuffer(0));
              if(bytes.length>$MAX_BYTES){fail('size');return;}
              if(!(bytes[0]===80&&bytes[1]===75)){var head='';for(var i=0;i<Math.min(bytes.length,512);i++)head+=String.fromCharCode(bytes[i]);fail(/(<html|<!doctype|<head|<body|<script)/i.test(head)?'html':'file');return;}
              s.bytes=bytes;s.phase='ready';
              }catch(error){fail('network');}
            };
            x.send(${body?.let{JSONObject.quote(it)}?:"null"});
          }catch(error){fail('network');}
          return 'started';
        })();
    """.trimIndent()

    private fun pollScript(id: String): String = """
        (function(){
          var s=window[${JSONObject.quote(id)}];if(!s)return {phase:'missing'};
          var value={phase:s.phase,status:s.status,mime:s.mime,loaded:s.loaded,code:s.code};
          if(s.phase==='ready'){
            value.size=s.bytes.length;
            if(s.offset>=s.bytes.length){value.phase='done';return value;}
            var end=Math.min(s.offset+32768,s.bytes.length),text='';
            for(var i=s.offset;i<end;i++)text+=String.fromCharCode(s.bytes[i]);
            value.data=btoa(text);value.seq=s.seq++;s.offset=end;
          }
          return value;
        })();
    """.trimIndent()

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        fun schoolUrl(url: String): Boolean = runCatching { val u=Uri.parse(url); u.scheme=="https" && (u.host=="neu.edu.cn" || u.host?.endsWith(".neu.edu.cn")==true) }.getOrDefault(false)
    }
}
