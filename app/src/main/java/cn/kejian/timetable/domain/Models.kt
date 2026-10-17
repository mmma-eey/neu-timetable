package cn.kejian.timetable.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Lesson(
    val id: String = UUID.randomUUID().toString(), val title: String, val code: String = "",
    val day: Int, val start: Int, val end: Int, val weeks: Set<Int>,
    val teacher: String = "", val campus: String = "", val room: String = "",
    val source: String = "手动添加", val raw: String = "", val needsReview: Boolean = false
) {
    fun active(week: Int) = week in weeks && !needsReview
    fun conflicts(other: Lesson) = id != other.id && day == other.day &&
        start <= other.end && end >= other.start && weeks.any { it in other.weeks }
    fun validated(): Lesson {
        require(title.isNotBlank() && title.length <= 300) { "请填写课程名称（最多 300 字）" }
        require(day in 1..7 && start in 1..12 && end in start..12) { "星期或起止节次不正确" }
        require(weeks.isNotEmpty() && weeks.all { it in 1..60 }) { "周次须在 1–60 周之间" }
        return this
    }
}
data class Period(val start: String, val end: String)
data class Term(val name: String = "2026–2027 秋季学期", val monday: LocalDate = LocalDate.of(2026,8,31), val weekCount: Int = 20, val periods: List<Period> = defaultPeriods()) {
    // Keep the persisted first Monday for compatibility with existing settings/backups.
    // NEU teaching weeks run from the preceding Sunday through Saturday.
    fun week(date: LocalDate) = Math.floorDiv(ChronoUnit.DAYS.between(startOfWeek(1),date),7L).toInt()+1
    fun mondayOf(week: Int): LocalDate = monday.plusWeeks((week-1).toLong())
    fun startOfWeek(week: Int): LocalDate = mondayOf(week).minusDays(1)
    fun validate() {
        require(monday.dayOfWeek.value == 1) { "第一周起始日须为周一" }
        require(weekCount in 1..60 && periods.size == 12) { "学期周数须在 1–60 之间" }
        periods.forEachIndexed { i,p ->
            val a=java.time.LocalTime.parse(p.start);val b=java.time.LocalTime.parse(p.end)
            require(a < b && (i==0 || a >= java.time.LocalTime.parse(periods[i-1].end))) { "第 ${i+1} 节时间无效或与前一节重叠" }
        }
    }
}
fun defaultPeriods() = listOf("08:30/09:15","09:25/10:10","10:30/11:15","11:25/12:10","14:00/14:45","14:55/15:40","16:00/16:45","16:55/17:40","18:30/19:15","19:25/20:10","20:20/21:05","21:15/22:00").map { val p=it.split('/');Period(p[0],p[1]) }
fun parseWeeks(text: String): Set<Int> {
    val normalized=text.replace('，',',').replace('、',',').replace('－','-').replace('—','-').replace('至','-').replace("第","").replace(" ","")
    val result=sortedSetOf<Int>()
    normalized.split(',').filter { it.isNotBlank() }.forEach { part ->
        val m=Regex("^(\\d+)(?:-(\\d+))?(?:周)?(?:[（(]?([单双])[）)]?周?)?$").matchEntire(part)
            ?: throw IllegalArgumentException("无法识别周次：$part；示例 1-8周,10-18周")
        val a=m.groupValues[1].toInt();val b=m.groupValues[2].toIntOrNull()?:a
        require(a in 1..60 && b in a..60) { "周次须在 1–60，且起始不大于结束" }
        (a..b).filterTo(result) { m.groupValues[3].isEmpty() || it%2 == if(m.groupValues[3]=="单")1 else 0 }
    }
    require(result.isNotEmpty()) { "请填写有效周次" };return result
}
fun formatWeeks(weeks: Set<Int>): String {
    val sorted=weeks.sorted();if(sorted.isEmpty())return ""
    val groups=mutableListOf<String>();var a=sorted.first();var b=a
    for(w in sorted.drop(1)){if(w==b+1)b=w else{groups+=if(a==b)"$a" else "$a-$b";a=w;b=w}}
    groups+=if(a==b)"$a" else "$a-$b";return groups.joinToString(",")+"周"
}
data class ImportPreview(val lessons: List<Lesson>, val issues: List<String> = emptyList(), val fileName: String = "", val sourceCells: Int = 0)
