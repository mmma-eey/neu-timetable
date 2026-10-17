package cn.kejian.timetable

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/** Only the observed NEU Excel download form is captured; login and other forms are untouched. */
internal object SchoolExportCapture {
    data class Request(val url:String,val body:String)
    private const val SUFFIX="/sys/kbapp/api/kbExport/downloadExportFile.do"
    fun installBeforeNavigation(view:WebView){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(view,script,setOf("https://neu.edu.cn","https://*.neu.edu.cn"))
    }
    fun installAfterLoad(view:WebView){view.evaluateJavascript(script,null)}
    suspend fun poll(view:WebView):Request?=withTimeoutOrNull(1500){
        val raw=suspendCancellableCoroutine<String>{continuation->
            view.evaluateJavascript(script+"\n"+pollScript){if(continuation.isActive)continuation.resume(it?:"null")}
        }
        if(raw=="null")return@withTimeoutOrNull null
        val obj=runCatching{JSONObject(raw)}.getOrNull()?:return@withTimeoutOrNull null
        val url=obj.optString("url");val body=obj.optString("body")
        if(!SchoolBrowserDownload.schoolUrl(url)||!Uri.parse(url).path.orEmpty().endsWith(SUFFIX)||body.length>65536)return@withTimeoutOrNull null
        val params=Uri.parse("https://local.invalid/?$body")
        if(params.getQueryParameter("EXPORTTYPE")!="02"||params.getQueryParameter("key").isNullOrBlank())return@withTimeoutOrNull null
        Request(url,body)
    }
    private val script="""
      (function install(w,depth){
        if(depth>8)return;
        try{
          if(!w.__kejianNeuExport||w.__kejianNeuExport.version!==2){
            var state={pending:[],version:2};w.__kejianNeuExport=state;
            function capture(form){
              try{
                // The native polling reader cannot access a cross-origin embedded document.
                if(w!==w.top){try{void w.top.document;}catch(error){return false;}}
                var action=form.action;
                if(typeof w.vpn_rewrite_url==='function')action=w.vpn_rewrite_url(action);
                var url=new w.URL(action,w.location.href);
                if(url.protocol!=='https:'||!(url.hostname==='neu.edu.cn'||url.hostname.endsWith('.neu.edu.cn'))||!url.pathname.endsWith('$SUFFIX')||String(form.method).toUpperCase()!=='POST')return false;
                var data=new w.FormData(form),params=new w.URLSearchParams();
                data.forEach(function(value,key){if(typeof value!=='string')throw new Error('Not a text form');params.append(key,value);});
                if(params.get('EXPORTTYPE')!=='02'||!params.get('key'))return false;
                var body=params.toString();if(body.length>65536)return false;
                if(state.pending.length<1)state.pending.push({url:url.href,body:body});
                return true;
              }catch(error){return false;}
            }
            var original=w.HTMLFormElement.prototype.submit;
            w.HTMLFormElement.prototype.submit=function(){if(!capture(this))return original.apply(this,arguments);};
            w.document.addEventListener('submit',function(event){if(!event.defaultPrevented&&event.target instanceof w.HTMLFormElement&&capture(event.target)){event.preventDefault();event.stopImmediatePropagation();}},false);
          }
          if(!w.__kejianNeuExport.frameObserver){
            var seen=new w.WeakSet();
            function watchFrames(){
              var frames=w.document.querySelectorAll('iframe,frame');
              for(var j=0;j<frames.length;j++){
                var frame=frames[j];
                if(!seen.has(frame)){seen.add(frame);frame.addEventListener('load',function(){install(this.contentWindow,depth+1);});}
                install(frame.contentWindow,depth+1);
              }
            }
            w.__kejianNeuExport.frameObserver=new w.MutationObserver(watchFrames);
            w.__kejianNeuExport.frameObserver.observe(w.document,{childList:true,subtree:true});
            watchFrames();
          }
          for(var i=0;i<w.frames.length;i++)install(w.frames[i],depth+1);
        }catch(error){}
      })(window,0);
    """.trimIndent()
    private val pollScript="""
      (function find(w,depth){
        if(depth>8)return null;
        try{
          var s=w.__kejianNeuExport;if(s&&s.pending.length)return s.pending.shift();
          for(var i=0;i<w.frames.length;i++){var found=find(w.frames[i],depth+1);if(found)return found;}
        }catch(error){}
        return null;
      })(window,0);
    """.trimIndent()
}
