package cn.kejian.timetable

import cn.kejian.timetable.domain.*
import cn.kejian.timetable.data.NeuExcelParser
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.io.File

class TimetableTests {
    @Test fun weekBoundaries(){
        val t=Term();assertEquals(1,t.week(LocalDate.of(2026,8,31)));assertEquals(2,t.week(LocalDate.of(2026,9,6)))
        assertEquals(2,t.week(LocalDate.of(2026,9,7)));assertEquals(7,t.week(LocalDate.of(2026,10,17)))
        assertEquals(1,t.week(LocalDate.of(2026,8,30)));assertEquals(0,t.week(LocalDate.of(2026,8,29)))
        assertEquals(LocalDate.of(2026,10,18),t.startOfWeek(8));assertEquals(LocalDate.of(2026,10,24),t.startOfWeek(8).plusDays(6))
        assertEquals(8,t.week(LocalDate.of(2026,10,18)));assertEquals(8,t.week(LocalDate.of(2026,10,24)));assertEquals(9,t.week(LocalDate.of(2026,10,25)))
        for(w in 1..t.weekCount) for(d in 0L..6L) assertEquals(w,t.week(t.startOfWeek(w).plusDays(d)))
        assertEquals(21,t.week(t.startOfWeek(21)))
    }
    @Test fun weekSetsPreserveGaps(){
        val w=parseWeeks("1-8周,10-18周");assertFalse(9 in w);assertEquals(17,w.size)
        assertEquals(setOf(1,3,5,7),parseWeeks("1-8周单"));assertEquals(setOf(2,4,6,8),parseWeeks("1-8周双"))
        assertEquals(w,parseWeeks(formatWeeks(w)))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidWeeks(){parseWeeks("18-10周")}
    @Test fun conflictsNeedOverlappingWeeksAndPeriods(){
        val a=Lesson(title="课程甲",day=1,start=1,end=2,weeks=setOf(1,2))
        assertFalse(a.conflicts(a.copy(id="other",weeks=setOf(3))))
        assertFalse(a.conflicts(a.copy(id="other",start=3,end=4)))
        assertTrue(a.conflicts(a.copy(id="other",start=2,end=3)))
    }
    @Test fun actualExportHasCorrectRulesAndLimitedReview(){
        val file=File(System.getProperty("courseFixture")?:"")
        org.junit.Assume.assumeTrue("可选私人集成样本未提供",file.isFile)
        val p=file.inputStream().use{NeuExcelParser().parse(it)}
        assertTrue(p.lessons.isNotEmpty());assertTrue(p.sourceCells>0)
        p.lessons.forEach { it.validated() }
        assertTrue(p.lessons.filter{it.title.startsWith("[实]")||it.title.contains("课程设计")}.none{it.needsReview})
    }
    @Test fun anonymousWorkbookPreservesSundayAndMixedCellReview(){
        fun cell(address:String,text:String)="""<c r="$address" t="inlineStr"><is><t>$text</t></is></c>"""
        val days=listOf("日","一","二","三","四","五","六")
        val headers=days.mapIndexed{i,d->cell("${('B'.code+i).toChar()}1","星期$d")}.joinToString("")
        val labels=listOf("一","二","三","四").mapIndexed{i,d->cell("A${i+2}","第${d}节")}.joinToString("")
        val course="示例理论课 A100001\n1-8周 示例教师 示例校区 示例教室\n[实]示例实验\n1-8周 示例教师 示例校区 示例实验室"
        val parts=mapOf("xl/workbook.xml" to "<workbook/>","xl/worksheets/sheet1.xml" to "<worksheet><sheetData>$headers$labels${cell("B2",course)}</sheetData><mergeCells><mergeCell ref=\"B2:B5\"/></mergeCells></worksheet>")
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use{zip->parts.forEach{(name,xml)->zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(xml.toByteArray());zip.closeEntry()}}
        val p=NeuExcelParser().parse(bytes.toByteArray().inputStream())
        assertEquals(2,p.lessons.size);assertTrue(p.issues.isEmpty())
        assertTrue(p.lessons.all{it.day==7&&it.start==1&&it.end==4})
        assertTrue(p.lessons.single{it.code=="A100001"}.needsReview)
        assertFalse(p.lessons.single{it.title.startsWith("[实]")}.needsReview)
        assertTrue(p.lessons.all{it.teacher=="示例教师"})
    }
    @Test(expected=Exception::class) fun rejectsHtmlDisguisedAsWorkbook(){NeuExcelParser().parse("<html>登录</html>".byteInputStream())}
}
