package cn.kejian.timetable.data

import android.content.Context
import androidx.room.Room
import cn.kejian.timetable.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.security.MessageDigest

class TimetableRepository(private val context: Context) {
    private val db=Room.databaseBuilder(context,Records.Store::class.java,"kejian.db").build()
    private val dao=db.dao()
    fun lessons(): List<Lesson> = dao.all().map { row -> val m=row.meeting;Lesson(id=m.id,title=row.title,code=row.code,day=m.day,start=m.start,end=m.end,weeks=m.weeks.split(',').mapNotNull{it.toIntOrNull()}.toSet(),teacher=m.teacher,campus=m.campus,room=m.room,source=m.source,raw=m.raw) }
    fun term(): Term = dao.term()?.let { row -> Term(row.name,LocalDate.parse(row.monday),row.weekCount,decodePeriods(JSONArray(row.periods))) }?:Term()
    fun setTerm(term: Term){term.validate();dao.term(Records.Semester().also{it.name=term.name;it.monday=term.monday.toString();it.weekCount=term.weekCount;it.periods=periodJson(term.periods).toString()})}
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun signature(r:Lesson)=listOf(r.title,r.code,r.day,r.start,r.end,r.weeks.sorted().joinToString(","),r.teacher,r.room,r.campus).joinToString("|")
    private fun saveRow(lesson: Lesson, deduplicate: Boolean){
        lesson.validated();require(!lesson.needsReview){"请先确认课程节次"}
        val c=Records.Course().also{it.id=hash(lesson.title+"|"+lesson.code);it.title=lesson.title;it.code=lesson.code};dao.course(c)
        dao.meeting(Records.Meeting().also { m ->
            m.id=if(deduplicate)hash(listOf(c.id,lesson.day,lesson.start,lesson.end,lesson.weeks.sorted().joinToString(","),lesson.teacher,lesson.room,lesson.campus).joinToString("|")) else lesson.id
            m.courseId=c.id;m.day=lesson.day;m.start=lesson.start;m.end=lesson.end;m.weeks=lesson.weeks.sorted().joinToString(",");m.teacher=lesson.teacher;m.campus=lesson.campus;m.room=lesson.room;m.source=lesson.source;m.raw=lesson.raw
        })
    }
    fun save(lesson: Lesson)=db.runInTransaction{saveRow(lesson,false)}
    fun delete(id:String)=dao.delete(id)
    fun importLessons(lessons:List<Lesson>,replace:Boolean,newTerm:Term?=null){
        lessons.forEach{it.validated();require(!it.needsReview){"还有未校对的课程"}}
        require(lessons.isNotEmpty()){ "没有可以导入的课程" };newTerm?.validate()
        checkpoint()
        db.runInTransaction {
            val existing=if(replace)emptyMap() else this.lessons().associateBy(::signature)
            if(replace){dao.clearMeetings();dao.clearCourses()};newTerm?.let{setTerm(it)}
            lessons.distinctBy(::signature).forEach{r->val old=existing[signature(r)];if(old!=null)saveRow(r.copy(id=old.id),false) else saveRow(r,true)}
        }
    }
    fun backup():String=JSONObject().put("schemaVersion",1).put("term",termJson(term())).put("lessons",JSONArray().also{a->lessons().forEach{a.put(lessonJson(it))}}).toString(2)
    private fun checkpoint(){val atomic=android.util.AtomicFile(java.io.File(context.filesDir,"before-import.json"));val stream=atomic.startWrite();try{stream.write(backup().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}}
    fun hasSnapshot()=java.io.File(context.filesDir,"before-import.json").exists()
    fun restoreSnapshot(){val file=java.io.File(context.filesDir,"before-import.json");val parsed=parseBackup(file.readText());if(parsed.second.isEmpty()){checkpoint();db.runInTransaction{dao.clearMeetings();dao.clearCourses();setTerm(parsed.first)}}else importLessons(parsed.second,true,parsed.first)}
    fun parseBackup(text:String):Pair<Term,List<Lesson>>{
        require(text.length<=10*1024*1024){"备份文件过大"};val root=JSONObject(text);require(root.getInt("schemaVersion")==1){"不支持此备份版本"}
        val t=root.getJSONObject("term");val term=Term(t.getString("name"),LocalDate.parse(t.getString("monday")),t.getInt("weekCount"),decodePeriods(t.getJSONArray("periods")));term.validate()
        val a=root.getJSONArray("lessons");require(a.length() in 0..5000){"备份课程数量不正确"}
        val lessons=(0 until a.length()).map{i->val r=a.getJSONObject(i);val weeks=r.getJSONArray("weeks");Lesson(title=r.getString("title"),code=r.optString("code"),day=r.getInt("day"),start=r.getInt("start"),end=r.getInt("end"),weeks=(0 until weeks.length()).map{weeks.getInt(it)}.toSet(),teacher=r.optString("teacher"),campus=r.optString("campus"),room=r.optString("room"),source=r.optString("source","备份恢复"),raw=r.optString("raw")).validated()}
        return term to lessons
    }
    private fun periodJson(periods:List<Period>)=JSONArray().also{a->periods.forEach{a.put(JSONObject().put("start",it.start).put("end",it.end))}}
    private fun decodePeriods(a:JSONArray)=(0 until a.length()).map{val p=a.getJSONObject(it);Period(p.getString("start"),p.getString("end"))}
    private fun termJson(t:Term)=JSONObject().put("name",t.name).put("monday",t.monday.toString()).put("weekCount",t.weekCount).put("periods",periodJson(t.periods))
    private fun lessonJson(r:Lesson)=JSONObject().put("title",r.title).put("code",r.code).put("day",r.day).put("start",r.start).put("end",r.end).put("weeks",JSONArray(r.weeks.sorted())).put("teacher",r.teacher).put("campus",r.campus).put("room",r.room).put("source",r.source).put("raw",r.raw)
}
