package cn.kejian.timetable

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import cn.kejian.timetable.data.NeuExcelParser
import kotlinx.coroutines.*
import java.io.File

/** Preserve WebView windows and HTTP methods during school SSO; never replay navigation as GET. */
class SchoolWebActivity:ComponentActivity(){
    private var browser:WebView?=null
    private var browserHost:FrameLayout?=null
    private val windows=mutableListOf<WebView>()
    private var downloading by mutableStateOf(false)
    private var loadProgress by mutableIntStateOf(0)
    private var address by mutableStateOf("webvpn.neu.edu.cn/m/portal")
    private var problem by mutableStateOf<String?>(null)
    private var pageInfo by mutableStateOf(false)
    private var lastPageError:String?=null
    private var download:Job?=null
    private val browserDownload by lazy { SchoolBrowserDownload(cacheDir) }
    private var lastDownloadInfo by mutableStateOf("尚未接收导出")
    private val entry="https://webvpn.neu.edu.cn/m/portal"
    private val personalEntry="https://personal.neu.edu.cn/portal"
    private fun allowed(url:String):Boolean=try{val uri=Uri.parse(url);uri.scheme=="https"&&(uri.host=="neu.edu.cn"||uri.host?.endsWith(".neu.edu.cn")==true)}catch(_:Exception){false}
    private fun displayAddress(url:String?):String=try{val u=Uri.parse(url);(u.host?:"学校网页")+(u.encodedPath?:"").take(100)}catch(_:Exception){"学校网页"}

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge();setContent{
        MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF5555C6))){
            Scaffold(topBar={Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp)){
                Row{TextButton(onClick={navigateBack()}){Text("返回")};Text(address,Modifier.weight(1f).padding(top=12.dp),fontSize=11.sp,maxLines=2);TextButton(onClick={finish()}){Text("关闭")}}
                Row{TextButton(onClick={returnToPortal()}){Text("回导航")};TextButton(onClick={lastPageError=null;browser?.reload()}){Text("刷新")};TextButton(onClick={clearLogin()}){Text("退出登录")};TextButton(onClick={pageInfo=true}){Text("页面信息")}}
                Text(if(downloading)lastDownloadInfo else "从学校移动导航登录，再点击教务系统（新） → 我的课表 → 打印 Excel。",fontSize=12.sp,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
                TextButton(onClick={navigateRoot(personalEntry)}){Text("也可从一号通入口进入",fontSize=11.sp)}
                if(downloading){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={download?.cancel();downloading=false}){Text("取消本次接收")}}
                else if(loadProgress in 1..99)LinearProgressIndicator(progress={loadProgress/100f},modifier=Modifier.fillMaxWidth())
            }}){pad->AndroidView(modifier=Modifier.fillMaxSize().padding(pad),factory={context->
                FrameLayout(context).also { host->browserHost=host;val root=createSchoolWebView();windows.add(root);showWindow(root);root.loadUrl(entry) }
            })}
            BackHandler{navigateBack()}
            problem?.let{message->AlertDialog(onDismissRequest={problem=null},title={Text("学校网页提示")},text={Text(message)},confirmButton={TextButton(onClick={problem=null}){Text("知道了")}},dismissButton={TextButton(onClick={problem=null;returnToPortal()}){Text("回导航")}})}
            if(pageInfo)AlertDialog(onDismissRequest={pageInfo=false},title={Text("页面信息")},text={Text("地址：$address\n引擎：${WebView.getCurrentWebViewPackage()?.versionName?:"未知"}\n加载进度：$loadProgress%\n窗口数：${windows.size}\n最近错误：${lastPageError?:"未收到页面加载错误"}\n导出：$lastDownloadInfo\n\n仅显示域名、路径和引擎信息，不包含密码、Cookie 或网址查询参数。")},confirmButton={TextButton(onClick={pageInfo=false}){Text("关闭信息")}})
        }
    }
        lifecycleScope.launch{
            repeatOnLifecycle(Lifecycle.State.STARTED){
                while(isActive){
                    if(!downloading)for(view in windows.toList().asReversed()){
                        if(!allowed(view.url.orEmpty()))continue
                        val request=SchoolExportCapture.poll(view)?:continue
                        receiveInBrowser(request.url,view,request.body)
                        break
                    }
                    delay(350)
                }
            }
        }
    }

    private fun createSchoolWebView():WebView=WebView(this).apply{
        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
        settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort=true;settings.loadWithOverviewMode=true
        settings.builtInZoomControls=true;settings.displayZoomControls=false
        settings.setSupportMultipleWindows(true);settings.javaScriptCanOpenWindowsAutomatically=true
        CookieManager.getInstance().setAcceptCookie(true)
        // SSO may use framed endpoints. This setting is scoped to the school's WebViews only.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
        webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                // Do not cancel iframe/about:blank bootstraps or replay POST/redirects through loadUrl.
                if(!request.isForMainFrame)return false
                val url=request.url.toString()
                if(url=="about:blank"||allowed(url))return false
                problem="学校页面尝试打开未适配地址。可点回导航重试，或从浏览器下载 Excel 后导入。";return true
            }
            override fun onPageStarted(view:WebView,url:String,icon:Bitmap?){if(view===browser)address=displayAddress(url)}
            override fun onPageFinished(view:WebView,url:String){if(allowed(url))SchoolExportCapture.installAfterLoad(view);if(view===browser)address=displayAddress(url)}
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame){lastPageError="加载失败（${error.errorCode}）";problem="${displayAddress(request.url.toString())} 加载失败（${error.errorCode}）。可点击回导航重试；如持续白屏，请查看页面信息。"}}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame){lastPageError="HTTP ${response.statusCode}";problem="学校页面返回 HTTP ${response.statusCode}，可回导航重新进入。"}}
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel();lastPageError="证书验证失败";problem="学校页面证书验证失败，已停止加载。请检查手机日期、网络及系统网页引擎。"}
        }
        webChromeClient=object:WebChromeClient(){
            override fun onProgressChanged(view:WebView,newProgress:Int){if(view===browser)loadProgress=newProgress}
            override fun onCreateWindow(view:WebView,isDialog:Boolean,isUserGesture:Boolean,resultMsg:Message):Boolean{
                if(!allowed(view.url?:"")||windows.size>=5)return false
                // Keep the original window/opener relationship, cookies and request method intact.
                val child=createSchoolWebView();windows.add(child);showWindow(child)
                (resultMsg.obj as WebView.WebViewTransport).webView=child;resultMsg.sendToTarget();return true
            }
            override fun onCloseWindow(window:WebView){closeWindow(window)}
        }
        SchoolExportCapture.installBeforeNavigation(this)
        setDownloadListener{url,_,_,_,_->
            if(downloading)problem="正在接收文件，请先等待或取消。"
            else if(!allowed(url))problem="此导出不是已适配的学校 HTTPS 下载。请先在浏览器下载后从文件导入。"
            else if(Uri.parse(url).path.orEmpty().endsWith("/sys/kbapp/api/kbExport/downloadExportFile.do")){
                lastDownloadInfo="未捕获到原始 POST 表单"
                problem="未能接收到学校下载表单的原始参数，已停止重试，避免把 POST 错改为 GET。请回到课表重新打印 Excel，或手动下载后导入。"
            }
            else receiveInBrowser(url)
        }
    }
    private fun showWindow(view:WebView){
        browserHost?.removeAllViews();browserHost?.addView(view,FrameLayout.LayoutParams(-1,-1));browser=view
        address=displayAddress(view.url);loadProgress=view.progress
    }
    private fun closeWindow(view:WebView){
        val index=windows.indexOf(view);if(index<1)return
        for(i in windows.lastIndex downTo index){val closed=windows.removeAt(i);browserHost?.removeView(closed);closed.stopLoading();closed.destroy()}
        windows.lastOrNull()?.let(::showWindow)
    }
    private fun navigateBack(){
        val current=browser
        if(downloading){download?.cancel();downloading=false}
        else if(current?.canGoBack()==true)current.goBack()
        else if(windows.size>1&&current!=null)closeWindow(current)
        else finish()
    }
    private fun navigateRoot(url:String){
        download?.cancel();downloading=false;problem=null;lastPageError=null
        while(windows.size>1)closeWindow(windows.last())
        browser?.stopLoading();browser?.loadUrl(url)
    }
    private fun returnToPortal(){navigateRoot(entry)}
    private fun clearLogin(){
        download?.cancel();downloading=false;windows.forEach{it.stopLoading()}
        CookieManager.getInstance().removeAllCookies{CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();windows.forEach{it.clearCache(true);it.clearHistory()};navigateRoot(entry)}
    }
    private fun receiveInBrowser(url:String,capturedSource:WebView?=null,postBody:String?=null){
        // Download popups often remain about:blank. Use the still-live school page of the same origin.
        val target=Uri.parse(url)
        val source=capturedSource?:windows.lastOrNull { view->
            val page=Uri.parse(view.url?:"")
            allowed(view.url?:"") && page.host==target.host && page.port==target.port
        }
        if(source==null){problem="没有找到该导出对应的学校页面，请返回课表再试或使用手动文件导入";return}
        showWindow(source)
        downloading=true;lastDownloadInfo="正在启动原课表页接收"
        download=lifecycleScope.launch{
            // Let the download callback return before disposing its empty popup.
            yield()
            while(windows.size>1 && windows.last()!==source && windows.last().url.orEmpty().let{it.isEmpty()||it=="about:blank"})closeWindow(windows.last())
            showWindow(source)
            var temp:File?=null;var successful=false
            try{
                temp=browserDownload.receive(source,url,if(postBody==null)"GET" else "POST",postBody){lastDownloadInfo=it}
                withContext(Dispatchers.IO){temp.inputStream().use{NeuExcelParser().parse(it,"教务网页课表.xlsx")}}
                successful=true;setResult(Activity.RESULT_OK,Intent().putExtra("downloadPath",temp.absolutePath));finish()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){problem=e.message?:"接收失败，可在浏览器下载后手动导入";lastDownloadInfo=problem?:"接收失败"}
            finally{downloading=false;if(!successful)temp?.delete()}
        }
    }
    override fun onDestroy(){
        download?.cancel();browserHost?.removeAllViews();windows.forEach{it.stopLoading();it.destroy()};windows.clear();browser=null;browserHost=null;super.onDestroy()
    }
}
