package cn.kejian.timetable.data

import cn.kejian.timetable.domain.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** File structure is interpreted locally. Never executes formulas, links or workbook scripts. */
class NeuExcelParser {
    private data class Cell(val col: Int,val row: Int)
    private fun cell(ref: String): Cell {
        val m=Regex("([A-Z]+)([0-9]+)").matchEntire(ref)?:error("单元格坐标无效")
        return Cell(m.groupValues[1].fold(0){a,c->a*26+c.code-64},m.groupValues[2].toInt())
    }
    private fun xml(bytes: ByteArray): XmlPullParser {
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE",true)) { "不支持含外部实体的文件" }
        return XmlPullParserFactory.newInstance().newPullParser().apply { setInput(ByteArrayInputStream(bytes),"UTF-8") }
    }
    fun parse(input: InputStream, fileName: String = "课表.xlsx"): ImportPreview {
        val parts=hashMapOf<String,ByteArray>();var total=0L;var entries=0
        ZipInputStream(input).use { zip ->
            while(true){val entry=zip.nextEntry?:break;require(++entries<=2000){"文件条目过多"}
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=50L*1024*1024){"课表文件解压后过大"};out.write(buffer,0,n)}
                if(entry.name.endsWith(".xml") || entry.name.endsWith(".rels"))parts[entry.name]=out.toByteArray()
            }
        }
        require(parts.containsKey("xl/workbook.xml")) { "文件不是有效的 Excel 工作簿，可能是登录页或其他文件" }
        val strings=mutableListOf<String>()
        parts["xl/sharedStrings.xml"]?.let { bytes ->
            val p=xml(bytes);var value:StringBuilder?=null
            while(p.eventType!=XmlPullParser.END_DOCUMENT){
                if(p.eventType==XmlPullParser.START_TAG && p.name=="si")value=StringBuilder()
                if(p.eventType==XmlPullParser.START_TAG && p.name=="t")value?.append(p.nextText())
                if(p.eventType==XmlPullParser.END_TAG && p.name=="si")strings+=value.toString()
                p.next()
            }
        }
        // Locate the first actual worksheet by workbook relationships, not ZIP entry ordering.
        val wb=xml(parts.getValue("xl/workbook.xml"));var relation=""
        while(wb.eventType!=XmlPullParser.END_DOCUMENT){if(wb.eventType==XmlPullParser.START_TAG&&wb.name=="sheet"){relation=(0 until wb.attributeCount).firstOrNull{wb.getAttributeName(it).endsWith("id")}?.let{wb.getAttributeValue(it)}?:"";break};wb.next()}
        var target="worksheets/sheet1.xml"
        parts["xl/_rels/workbook.xml.rels"]?.let { bytes -> val p=xml(bytes);while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG&&p.name=="Relationship"&&p.getAttributeValue(null,"Id")==relation)target=p.getAttributeValue(null,"Target");p.next()} }
        val sheet=if(target.startsWith('/'))target.drop(1) else "xl/$target"
        val p=xml(parts[sheet]?:throw IllegalArgumentException("找不到课表工作表"))
        val values=linkedMapOf<String,String>();val merges=hashMapOf<String,Cell>();var address="";var type="";var content=StringBuilder()
        while(p.eventType!=XmlPullParser.END_DOCUMENT){
            if(p.eventType==XmlPullParser.START_TAG)when(p.name){
                "c"->{address=p.getAttributeValue(null,"r")?:"";type=p.getAttributeValue(null,"t")?:"";content=StringBuilder()}
                "v","t"->content.append(p.nextText())
                "mergeCell"->{val range=p.getAttributeValue(null,"ref").split(':');if(range.size==2)merges[range[0]]=cell(range[1])}
            }
            if(p.eventType==XmlPullParser.END_TAG&&p.name=="c"&&address.isNotBlank())values[address]=if(type=="s")strings.getOrNull(content.toString().toIntOrNull()?:-1)?:"" else content.toString()
            p.next()
        }
        val dayNames=listOf("星期一","星期二","星期三","星期四","星期五","星期六","星期日")
        val dayColumns=hashMapOf<Int,Int>();val periodRows=hashMapOf<Int,Int>()
        val cn=listOf("一","二","三","四","五","六","七","八","九","十","十一","十二")
        values.forEach { (address,text) ->
            val c=cell(address);val day=dayNames.indexOf(text.trim())+1
            if(day>0){val end=merges[address]?:c;(c.col..end.col).forEach{dayColumns[it]=day}}
            val period=cn.indexOf(text.trim().removePrefix("第").removeSuffix("节"))+1
            if(text.startsWith("第")&&text.endsWith("节")&&period>0)periodRows[c.row]=period
        }
        require(dayColumns.values.toSet().size==7&&periodRows.isNotEmpty()) { "未识别出东北大学课表的星期和节次，请选择教务系统导出的 Excel" }
        val lessons=mutableListOf<Lesson>();val issues=mutableListOf<String>();var sourceCells=0
        values.forEach { (address,text) ->
            val c=cell(address);val day=dayColumns[c.col];val start=periodRows[c.row]
            if(day!=null&&start!=null&&text.isNotBlank()){
                sourceCells++;val end=periodRows[(merges[address]?:c).row]?:start
                var title="";var code="";val current=mutableListOf<Lesson>();var scheduleLines=0
                text.lines().forEach { raw ->
                    val line=raw.trim();val course=Regex("(.+?)\\s+(A\\d{6})").matchEntire(line)
                    if(course!=null){title=course.groupValues[1];code=course.groupValues[2]}
                    else if(line.startsWith("[实]")||line.startsWith("【实】")){title=line;code=""}
                    else {
                        val match=Regex("^([\\d\\-,，周单双()（）]+)\\s+(.+)$").matchEntire(line)
                        if(match!=null){scheduleLines++
                            if(title.isBlank()){issues+="$address：课程名称无法识别：$line"}
                            else try{
                                val detail=match.groupValues[2];val campusMatch=Regex("\\s+([^\\s]*校区)\\s+").find(detail)
                                val teacher=campusMatch?.let{detail.substring(0,it.range.first).trim()}?:""
                                val campus=campusMatch?.groupValues?.get(1)?:""
                                val room=campusMatch?.let{detail.substring(it.range.last+1).trim().split(Regex("\\s+人工智能\\d"))[0]}?:detail
                                current+=Lesson(title=title,code=code,day=day,start=start,end=end,weeks=parseWeeks(match.groupValues[1]),teacher=teacher,campus=campus,room=room,source=address,raw=line)
                            }catch(e:IllegalArgumentException){issues+="$address：${e.message}"}
                        }
                    }
                }
                if(current.isEmpty()&&scheduleLines==0)issues+="$address：未能解析的课程原文：$text"
                val mixed=current.map{it.title}.distinct().size>1&&end-start>=3
                current.forEach { r ->
                    val isFourPeriodPractice=r.title.startsWith("[实]")||r.title.startsWith("【实】")||r.title.contains("课程设计")||r.title.contains("课设")
                    lessons+=r.copy(needsReview=mixed&&!isFourPeriodPractice).validated()
                }
            }
        }
        require(lessons.isNotEmpty()) { "未解析出课程，请检查 Excel 模板" }
        return ImportPreview(lessons,issues,fileName,sourceCells)
    }
}
