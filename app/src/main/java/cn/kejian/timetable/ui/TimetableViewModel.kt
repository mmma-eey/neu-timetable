package cn.kejian.timetable.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.kejian.timetable.data.*
import cn.kejian.timetable.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class AppState(val term:Term=Term(),val lessons:List<Lesson> = emptyList(),val busy:Boolean=false,val message:String?=null,val preview:ImportPreview?=null,val backupTerm:Term?=null,val hasSnapshot:Boolean=false)
class TimetableViewModel(app:Application):AndroidViewModel(app){
    private val repo=TimetableRepository(app)
    private val _state=MutableStateFlow(AppState());val state=_state.asStateFlow()
    init { execute{reload()} }
    private suspend fun reload(){val t=withContext(Dispatchers.IO){repo.term()};val l=withContext(Dispatchers.IO){repo.lessons()};val snap=withContext(Dispatchers.IO){repo.hasSnapshot()};_state.update{it.copy(term=t,lessons=l,hasSnapshot=snap)}}
    private fun execute(task:suspend ()->Unit){if(_state.value.busy)return;viewModelScope.launch{_state.update{it.copy(busy=true,message=null)};try{task()}catch(e:CancellationException){throw e}catch(e:Exception){_state.update{it.copy(message=e.message?:"操作失败，请重试")}}finally{_state.update{it.copy(busy=false)}}}}
    fun dismissMessage(){_state.update{it.copy(message=null)}}
    fun dismissPreview(){_state.update{it.copy(preview=null,backupTerm=null)}}
    fun file(uri:Uri){execute{
        val resolver=getApplication<Application>().contentResolver
        var name="课表.xlsx";resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)}
        val bytes=withContext(Dispatchers.IO){resolver.openInputStream(uri)?.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=10*1024*1024){"文件超过 10 MB"};out.write(buffer,0,n)};out.toByteArray()}?:error("无法读取文件")}
        parse(bytes,name)
    }}
    fun downloaded(path:String){execute{val file=File(path);require(file.canonicalFile.parentFile==getApplication<Application>().cacheDir.canonicalFile){"下载文件位置无效"};try{val bytes=withContext(Dispatchers.IO){require(file.length()<=10*1024*1024){"文件过大"};file.readBytes()};parse(bytes,"教务网页课表.xlsx")}finally{file.delete()}}}
    private suspend fun parse(bytes:ByteArray,name:String){
        if(bytes.size>=2&&bytes[0]==0x50.toByte()&&bytes[1]==0x4b.toByte()){
            val p=withContext(Dispatchers.IO){NeuExcelParser().parse(bytes.inputStream(),name)};_state.update{it.copy(preview=p,backupTerm=null)}
        }else{
            require(bytes.toString(Charsets.UTF_8).trimStart().startsWith("{")){"此文件不是有效的 Excel 或课间备份；可能下载到了登录页，请重新导出"}
            val parsed=withContext(Dispatchers.IO){repo.parseBackup(bytes.toString(Charsets.UTF_8))};_state.update{it.copy(preview=ImportPreview(parsed.second,fileName=name),backupTerm=parsed.first)}
        }
    }
    fun review(id:String,start:Int,end:Int){val p=_state.value.preview?:return;_state.update{it.copy(preview=p.copy(lessons=p.lessons.map{r->if(r.id==id)r.copy(start=start,end=end,needsReview=false) else r}))}}
    fun commit(replace:Boolean,onlyConfirmed:Boolean){execute{
        val s=_state.value;val p=s.preview?:return@execute
        require(onlyConfirmed||p.lessons.none{it.needsReview}){"请先校对疑点，或选择仅导入已确认项"}
        require(s.backupTerm==null||replace){"备份恢复会替换当前学期，请选择替换"}
        val rows=p.lessons.filter{!it.needsReview};withContext(Dispatchers.IO){repo.importLessons(rows,replace,s.backupTerm)};reload();_state.update{it.copy(preview=null,backupTerm=null,message="已保存 ${rows.size} 条安排；实验课和课设已按合并格节次导入")}
    }}
    fun save(lesson:Lesson){execute{withContext(Dispatchers.IO){repo.save(lesson)};reload();_state.update{it.copy(message="课程已保存")}}}
    fun delete(id:String){execute{withContext(Dispatchers.IO){repo.delete(id)};reload();_state.update{it.copy(message="已删除这条上课安排")}}}
    fun settings(term:Term){execute{withContext(Dispatchers.IO){repo.setTerm(term)};reload();_state.update{it.copy(message="学期与作息已保存")}}}
    fun export(uri:Uri){execute{withContext(Dispatchers.IO){val text=repo.backup();getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use{it.write(text)}?:error("无法写入备份")};_state.update{it.copy(message="备份已导出，请妥善保存其中的课程与地点信息")}}}
    fun restore(){execute{withContext(Dispatchers.IO){repo.restoreSnapshot()};reload();_state.update{it.copy(message="已恢复导入前的课表")}}}
}
