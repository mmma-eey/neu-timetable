package cn.kejian.timetable

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.kejian.timetable.domain.*
import cn.kejian.timetable.ui.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val Ink=Color(0xFF23273B)
private val Purple=Color(0xFF5555C6)
private val Paper=Color(0xFFFAFAF8)
private val Muted=Color(0xFF787D90)
private val Line=Color(0xFFE7E8EE)
private val courseColors=listOf(Color(0xFFE6E8FB),Color(0xFFE2EDE9),Color(0xFFF4E9DC),Color(0xFFE1EDF4),Color(0xFFEEE4EF))
private fun today():LocalDate=LocalDate.now(ZoneId.of("Asia/Shanghai"))
private fun weekday(day:Int)="周"+listOf("一","二","三","四","五","六","日")[day-1]
private fun shortTitle(title:String)=title.replace("习近平新时代中国特色社会主义思想概论","思政概论").replace("Java语言及程序设计","Java 程序设计")

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();setContent{
        MaterialTheme(colorScheme=lightColorScheme(primary=Purple,background=Paper,surface=Paper,onSurface=Ink,onBackground=Ink)){KejianApp()}
    }}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun KejianApp(vm:TimetableViewModel=viewModel()){
    val state by vm.state.collectAsStateWithLifecycle();var tab by remember{mutableIntStateOf(0)}
    val pager=rememberPagerState(initialPage=state.term.week(today()).coerceIn(1,state.term.weekCount)-1){state.term.weekCount}
    val scope=rememberCoroutineScope()
    val week=pager.currentPage+1
    fun goToWeek(value:Int){scope.launch{pager.animateScrollToPage(value.coerceIn(1,state.term.weekCount)-1)}}
    LaunchedEffect(state.term.monday){pager.scrollToPage(state.term.week(today()).coerceIn(1,state.term.weekCount)-1)}
    var chooseWeek by remember{mutableStateOf(false)};var selected by remember{mutableStateOf<Lesson?>(null)}
    var editing by remember{mutableStateOf<Lesson?>(null)};var add by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf<Lesson?>(null)}
    val snack=remember{SnackbarHostState()};val context=androidx.compose.ui.platform.LocalContext.current
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::file)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){it?.let(vm::export)}
    val web=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==Activity.RESULT_OK)r.data?.getStringExtra("downloadPath")?.let(vm::downloaded)}
    LaunchedEffect(state.message){state.message?.let{snack.showSnackbar(it);vm.dismissMessage()}}
    BackHandler(state.preview!=null){if(!state.busy)vm.dismissPreview()}
    Scaffold(containerColor=Paper,snackbarHost={SnackbarHost(snack)},topBar={
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp)){
            Text(state.term.name,color=Muted,fontSize=12.sp)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(if(state.preview!=null)"核对导入内容" else listOf("周课表","今日课程","导入课表","学期与设置")[tab],fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                if(state.preview!=null)TextButton(onClick=vm::dismissPreview,enabled=!state.busy){Text("取消")}
                else {TextButton(onClick={add=true},enabled=!state.busy){Text("＋添加")};if(tab==0)TextButton(onClick={tab=2}){Text("导入")}}
            }
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
        }
    },bottomBar={if(state.preview==null)NavigationBar(containerColor=Color.White){
        listOf("课表","今日","导入","设置").forEachIndexed{i,label->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={NavigationGlyph(i)},label={Text(label)})}
    }}){padding->Box(Modifier.fillMaxSize().padding(padding)){
        val preview=state.preview
        if(preview!=null)PreviewScreen(preview,state.busy,state.backupTerm!=null,vm::review,vm::commit)
        else when(tab){
            0->Column{
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=5.dp).border(1.dp,Line,RoundedCornerShape(12.dp)).padding(3.dp),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={goToWeek(week-1)},enabled=week>1){Text("‹",fontSize=23.sp)}
                    Column(Modifier.weight(1f).clickable{chooseWeek=true},horizontalAlignment=Alignment.CenterHorizontally){Text("第 $week 周 ⌄",fontSize=19.sp,fontWeight=FontWeight.Bold);Text("${state.term.startOfWeek(week).format(DateTimeFormatter.ofPattern("M/d"))} – ${state.term.startOfWeek(week).plusDays(6).format(DateTimeFormatter.ofPattern("M/d"))}",fontSize=10.sp,color=Muted)}
                    TextButton(onClick={goToWeek(week+1)},enabled=week<state.term.weekCount){Text("›",fontSize=23.sp)}
                    TextButton(onClick={goToWeek(state.term.week(today()))}){Text("回本周",fontSize=11.sp)}
                }
                if(state.term.week(today()) !in 1..state.term.weekCount)Text("当前日期不在所设教学周范围，正在浏览第 $week 周",Modifier.padding(horizontal=18.dp),fontSize=11.sp,color=Muted)
                HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth().weight(1f),beyondViewportPageCount=1,key={it}){page->
                    Column(Modifier.fillMaxSize()){
                        if(state.lessons.isEmpty())Welcome({tab=2},{add=true}) else WeekGrid(state.term,state.lessons,page+1){selected=it}
                    }
                }
            }
            1->TodayScreen(state.term,state.lessons){selected=it}
            2->ImportScreen({file.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/json","application/octet-stream"))},{web.launch(Intent(context,SchoolWebActivity::class.java))},state.busy)
            3->SettingsScreen(state,vm::settings,{export.launch("课间备份-${today()}.json")},{file.launch(arrayOf("application/json","application/octet-stream"))},vm::restore)
        }
    }}
    if(chooseWeek)AlertDialog(onDismissRequest={chooseWeek=false},title={Text("选择周次")},text={Column(Modifier.verticalScroll(rememberScrollState())){(1..state.term.weekCount).chunked(5).forEach{row->Row{row.forEach{w->TextButton(onClick={goToWeek(w);chooseWeek=false},modifier=Modifier.weight(1f)){Text("$w",color=if(w==week)Purple else Ink)}}}}}},confirmButton={TextButton(onClick={chooseWeek=false}){Text("关闭")}})
    selected?.let { r -> AlertDialog(onDismissRequest={selected=null},title={Text(r.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("${weekday(r.day)} · 第 ${r.start}–${r.end} 节")
        Text("${state.term.periods[r.start-1].start}–${state.term.periods[r.end-1].end}"+(if(r.end>=7)"（部分作息待校对）" else ""))
        Text("教师：${r.teacher.ifBlank{"待补充"}}\n教室：${r.room.ifBlank{"待补充"}}\n校区：${r.campus.ifBlank{"待补充"}}")
        Text("周次：${formatWeeks(r.weeks)}\n来源：${r.source}")
        if(state.lessons.any{r.conflicts(it)})Text("这条安排与其他课程存在重叠，请核对周次和节次。",color=Color(0xFF9A5818))
    }},confirmButton={TextButton(onClick={editing=r;selected=null}){Text("编辑")}},dismissButton={TextButton(onClick={deleting=r;selected=null}){Text("删除")}}) }
    if(add||editing!=null)LessonEditor(editing,onDismiss={add=false;editing=null},onSave={vm.save(it);add=false;editing=null})
    deleting?.let { r->AlertDialog(onDismissRequest={deleting=null},title={Text("删除这条上课安排？")},text={Text("${r.title}\n${weekday(r.day)} ${r.start}–${r.end} 节。其他时间的同名课程会保留。")},confirmButton={TextButton(onClick={vm.delete(r.id);deleting=null}){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}}) }
}

@Composable private fun NavigationGlyph(index:Int){
    val color=LocalContentColor.current
    Canvas(Modifier.size(24.dp)){
        val unit=size.width/24f
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(x1*unit,y1*unit),Offset(x2*unit,y2*unit),2*unit)
        when(index){
            0->{
                drawRect(color,Offset(3*unit,3*unit),Size(18*unit,18*unit),style=Stroke(2*unit))
                line(3f,9f,21f,9f);line(3f,15f,21f,15f);line(9f,3f,9f,21f);line(15f,3f,15f,21f)
            }
            1->{for(y in listOf(5f,12f,19f)){line(3f,y,6f,y);line(10f,y,21f,y)}}
            2->{line(12f,3f,12f,17f);line(6f,12f,12f,18f);line(12f,18f,18f,12f);line(4f,21f,20f,21f)}
            3->{
                drawCircle(color,7*unit,style=Stroke(2*unit));drawCircle(color,2*unit,style=Stroke(2*unit))
                for(i in 0..7){val a=i*Math.PI/4;line(12f+8*kotlin.math.cos(a).toFloat(),12f+8*kotlin.math.sin(a).toFloat(),12f+11*kotlin.math.cos(a).toFloat(),12f+11*kotlin.math.sin(a).toFloat())}
            }
        }
    }
}

@Composable private fun Welcome(onImport:()->Unit,add:()->Unit){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
    Spacer(Modifier.height(35.dp));Text("把本学期装进口袋",fontSize=23.sp,fontWeight=FontWeight.Bold);Text("在教务系统导出 Excel，或在应用内登录后打印课表。导入后可离线查看。",color=Muted,lineHeight=24.sp)
    Button(onClick=onImport,modifier=Modifier.fillMaxWidth()){Text("导入学校课表")};OutlinedButton(onClick=add,modifier=Modifier.fillMaxWidth()){Text("先手动添加课程")}
    Text("无广告 · 无需注册软件账号\n个人课表不会预装在安装包中",fontSize=12.sp,color=Muted,lineHeight=22.sp)
}}

@Composable private fun WeekGrid(term:Term,lessons:List<Lesson>,week:Int,onSelect:(Lesson)->Unit){
    val rowHeight=108.dp;val first=term.startOfWeek(week);val active=lessons.filter{it.active(week)}
    Row(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=9.dp)){
        Text("${first.monthValue}月",Modifier.width(31.dp).padding(top=9.dp),color=Muted,fontSize=10.sp)
        (0..6).forEach { offset->val date=first.plusDays(offset.toLong());val day=date.dayOfWeek.value;val current=date==today();Column(Modifier.weight(1f).padding(horizontal=1.dp).background(if(current)Purple else Color.White,RoundedCornerShape(8.dp)).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(weekday(day),fontSize=11.sp,color=if(current)Color.White else Muted);Text("${date.monthValue}/${date.dayOfMonth}",fontSize=11.sp,color=if(current)Color.White else Ink)} }
    }
    Row(Modifier.fillMaxWidth().weightInColumn().verticalScroll(rememberScrollState()).padding(horizontal=7.dp)){
        Column(Modifier.width(31.dp)){(1..12).forEach{p->Column(Modifier.height(rowHeight).fillMaxWidth(),verticalArrangement=Arrangement.Center){Text("$p",fontSize=11.sp,color=Muted);Text(term.periods[p-1].start,fontSize=8.sp,color=Muted)}}}
        listOf(7,1,2,3,4,5,6).forEach { day->
            val rows=active.filter{it.day==day}.sortedBy{it.start};val lanes=mutableListOf<Int>();val placements=rows.map { r->var slot=lanes.indexOfFirst{it<r.start};if(slot<0){slot=lanes.size;lanes+=r.end}else lanes[slot]=r.end;r to slot };val count=lanes.size.coerceAtLeast(1)
            BoxWithConstraints(Modifier.weight(1f).height(rowHeight*12)){
                val laneWidth=maxWidth/count
                Column{repeat(12){Box(Modifier.fillMaxWidth().height(rowHeight).border(.5.dp,Line))}}
                placements.forEach { (r,lane)->Column(Modifier.offset(x=laneWidth*lane,y=rowHeight*(r.start-1)).width(laneWidth).height(rowHeight*(r.end-r.start+1)).padding(1.dp).background(courseColors[abs(r.title.hashCode()%courseColors.size)],RoundedCornerShape(5.dp)).clickable{onSelect(r)}.padding(horizontal=3.dp,vertical=7.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    if(count>1&&rows.any{r.conflicts(it)})Text("冲突",fontSize=8.sp,color=Color(0xFF9A5818))
                    Text(shortTitle(r.title),fontSize=10.sp,fontWeight=FontWeight.Bold,lineHeight=14.sp)
                    Text("教师\n${r.teacher.ifBlank{"待补充"}}",fontSize=9.sp,lineHeight=13.sp)
                    Text("教室\n${r.room.ifBlank{"待补充"}}",fontSize=9.sp,lineHeight=13.sp)
                } }
            }
        }
    }
}
// A separate modifier helper avoids leaking a ColumnScope outside the week-grid caller.
private fun Modifier.weightInColumn():Modifier = this.fillMaxHeight()

@Composable private fun TodayScreen(term:Term,lessons:List<Lesson>,select:(Lesson)->Unit){
    var date by remember{mutableStateOf(today())};val week=term.week(date);val list=lessons.filter{it.day==date.dayOfWeek.value&&it.active(week)}.sortedBy{it.start}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={date=date.minusDays(1)}){Text("‹")};Text("${date.monthValue}月${date.dayOfMonth}日 ${weekday(date.dayOfWeek.value)}",Modifier.weight(1f));TextButton(onClick={date=date.plusDays(1)}){Text("›")};TextButton(onClick={date=today()}){Text("今天")}}
        Text(if(week in 1..term.weekCount)"第 $week 周 · ${list.size} 项安排" else "当前日期不在教学周范围",color=Muted)
        if(list.isEmpty())Text("当天没有已确认课程",Modifier.padding(vertical=30.dp),fontSize=21.sp)
        list.forEach { r->Card(onClick={select(r)},colors=CardDefaults.cardColors(containerColor=courseColors[abs(r.title.hashCode()%courseColors.size)]),modifier=Modifier.fillMaxWidth()){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text("${term.periods[r.start-1].start}–${term.periods[r.end-1].end} · 第 ${r.start}–${r.end} 节",fontSize=12.sp);Text(r.title,fontWeight=FontWeight.Bold,fontSize=19.sp);Text("${r.room}\n${r.teacher} · ${r.campus}",lineHeight=23.sp);if(r.end>=7)Text("部分作息时间待校对",fontSize=11.sp,color=Muted)}
        }}
    }
}

@Composable private fun ImportScreen(file:()->Unit,web:()->Unit,busy:Boolean){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEEEEFB))){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){Text("从移动导航进入教务",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("先打开学校移动导航页并登录，再点击“教务系统（新）” → 我的课表 → 打印 Excel。应用会尝试自动接收文件并进入校对页。",lineHeight=25.sp);Button(onClick=web,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("打开学校移动导航")};Text("遇到 JSON 页面可点击“回导航”重新进入。学校下载接收需在登录后验证；特殊导出仍可使用下面的文件导入。",fontSize=11.sp,lineHeight=18.sp,color=Muted)}}
    OutlinedButton(onClick=file,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("选择 Excel / 课间备份文件")}
    Text("导入规则",fontWeight=FontWeight.Bold,fontSize=17.sp)
    Text("实验课、课设默认按合并格的四节课导入。只有混合格里的其他课程等疑点，才需要确认实际节次。",lineHeight=25.sp)
    Text("先解析和预览，确认后保存。导入后，查看课表不需要联网。",fontSize=13.sp,color=Muted,lineHeight=23.sp)
}}

@Composable private fun PreviewScreen(p:ImportPreview,busy:Boolean,isBackup:Boolean,review:(String,Int,Int)->Unit,commit:(Boolean,Boolean)->Unit){
    var replace by remember(p.fileName){mutableStateOf(true)};var onlyConfirmed by remember(p.fileName){mutableStateOf(false)};var confirm by remember{mutableStateOf(false)}
    val pending=p.lessons.filter{it.needsReview}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text(p.fileName,color=Muted,fontSize=12.sp);Text("${p.lessons.size} 条候选安排 · ${pending.size} 条待校对",fontSize=20.sp,fontWeight=FontWeight.Bold)
        if(isBackup)Text("恢复备份将同时恢复学期与作息，并替换当前课表。",color=Color(0xFF925E24))
        if(p.issues.isNotEmpty())Card{Column(Modifier.padding(14.dp)){Text("未完整识别的内容，请核对原表",fontWeight=FontWeight.Bold);p.issues.forEach{Text(it,fontSize=12.sp,lineHeight=21.sp)}}}
        if(pending.isEmpty())Text("没有需要手选节次的记录。实验课和课设已采用合并格节次。",color=Muted,lineHeight=24.sp)
        pending.forEach { r->var option by remember(r.id){mutableIntStateOf(-1)};var menu by remember(r.id){mutableStateOf(false)};val choices=listOf(1 to 2,3 to 4,5 to 6,7 to 8,5 to 8,9 to 10,11 to 12,9 to 12)
            Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFAF0DF))){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(r.title,fontWeight=FontWeight.Bold);Text("${weekday(r.day)} · ${formatWeeks(r.weeks)}\n原格：第 ${r.start}–${r.end} 节 · ${r.source}\n${r.teacher} · ${r.room}",fontSize=12.sp,lineHeight=21.sp)
                Text("混合格中的这门课，实际是第几节？",fontSize=12.sp)
                Row(verticalAlignment=Alignment.CenterVertically){Box{TextButton(onClick={menu=true}){Text(if(option<0)"选择实际节次 ⌄" else "${choices[option].first}–${choices[option].second} 节 ⌄")};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){choices.forEachIndexed{i,c->DropdownMenuItem(text={Text("${c.first}–${c.second} 节")},onClick={option=i;menu=false})}}};Button(onClick={val c=choices[option];review(r.id,c.first,c.second)},enabled=!busy&&option>=0){Text("确认节次")}}
            }}
        }
        if(pending.isNotEmpty())Row(Modifier.fillMaxWidth().clickable(enabled=!busy){onlyConfirmed=!onlyConfirmed},verticalAlignment=Alignment.CenterVertically){Checkbox(checked=onlyConfirmed,onCheckedChange=null);Text("仅导入已确认项，暂时跳过 ${pending.size} 条疑点",fontSize=12.sp)}
        if(!isBackup)Row(Modifier.fillMaxWidth().clickable(enabled=!busy){replace=!replace},verticalAlignment=Alignment.CenterVertically){Checkbox(checked=replace,onCheckedChange=null);Text(if(replace)"替换当前学期（保留恢复快照）" else "合并到当前课表（相同安排去重）",fontSize=12.sp)}
        Button(onClick={confirm=true},enabled=!busy&&(pending.isEmpty()||onlyConfirmed)&&p.lessons.any{!it.needsReview},modifier=Modifier.fillMaxWidth()){Text("确认保存 ${p.lessons.count{!it.needsReview}} 条安排")}
        Text("全部候选安排",fontWeight=FontWeight.Bold)
        p.lessons.filter{!it.needsReview}.forEach { r->Column(Modifier.fillMaxWidth().border(1.dp,Line,RoundedCornerShape(10.dp)).padding(13.dp)){Text(r.title,fontWeight=FontWeight.Medium);Text("${weekday(r.day)} ${r.start}–${r.end} 节 · ${formatWeeks(r.weeks)}\n${r.teacher} · ${r.room}",fontSize=12.sp,color=Muted,lineHeight=20.sp)} }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(replace||isBackup)"替换当前课表？" else "合并到当前课表？")},text={Text("将保存 ${p.lessons.count{!it.needsReview}} 条安排。${if(pending.isNotEmpty())"跳过 ${pending.size} 条疑点记录。" else ""}导入前的课表可在设置中恢复。${if(p.issues.isNotEmpty())"文件中还有 ${p.issues.size} 项未识别内容，请确保已检查原表。" else ""}")},confirmButton={TextButton(onClick={confirm=false;commit(replace||isBackup,onlyConfirmed)}){Text("保存")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}

@Composable private fun LessonEditor(lesson:Lesson?,onDismiss:()->Unit,onSave:(Lesson)->Unit){
    var title by remember{mutableStateOf(lesson?.title?:"")};var teacher by remember{mutableStateOf(lesson?.teacher?:"")};var room by remember{mutableStateOf(lesson?.room?:"")};var campus by remember{mutableStateOf(lesson?.campus?:"浑南校区")}
    var day by remember{mutableStateOf((lesson?.day?:1).toString())};var start by remember{mutableStateOf((lesson?.start?:1).toString())};var end by remember{mutableStateOf((lesson?.end?:2).toString())};var weeks by remember{mutableStateOf(lesson?.let{formatWeeks(it.weeks)}?:"1-18周")};var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(lesson==null)"添加上课安排" else "编辑上课安排")},text={Column(Modifier.heightIn(max=490.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
        EditField("课程名称",title){title=it};EditField("教师",teacher){teacher=it};EditField("教室",room){room=it};EditField("校区",campus){campus=it}
        EditField("星期（1=周一，7=周日）",day,true){day=it};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){EditField("起始节",start,true){start=it}};Box(Modifier.weight(1f)){EditField("结束节",end,true){end=it}}}
        EditField("周次，如 1-8周,10-18周 或 1-18周单",weeks){weeks=it};if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    }},confirmButton={TextButton(onClick={try{val r=Lesson(id=lesson?.id?:java.util.UUID.randomUUID().toString(),title=title.trim(),code=lesson?.code?:"",day=day.toInt(),start=start.toInt(),end=end.toInt(),weeks=parseWeeks(weeks),teacher=teacher.trim(),room=room.trim(),campus=campus.trim(),source=lesson?.source?:"手动添加",raw=lesson?.raw?:"").validated();onSave(r)}catch(e:Exception){error=e.message?:"请检查填写内容"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable private fun EditField(label:String,value:String,numeric:Boolean=false,onChange:(String)->Unit){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label,fontSize=12.sp)},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Number else KeyboardType.Text),singleLine=true)}

@Composable private fun SettingsScreen(state:AppState,save:(Term)->Unit,export:()->Unit,restoreFile:()->Unit,restoreSnapshot:()->Unit){
    var name by remember(state.term){mutableStateOf(state.term.name)};var date by remember(state.term){mutableStateOf(state.term.monday.toString())};var count by remember(state.term){mutableStateOf(state.term.weekCount.toString())};var periods by remember(state.term){mutableStateOf(state.term.periods)};var error by remember{mutableStateOf("")};var restoreConfirm by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
        Text("学期",fontSize=18.sp,fontWeight=FontWeight.Bold);EditField("学期名称",name){name=it};EditField("第 1 周周一（年-月-日）",date){date=it};Text("每周从周日开始；第 1 周起于所填周一的前一天。",fontSize=12.sp,color=Muted);EditField("教学周数（暂填 20，可修改）",count,true){count=it}
        Text("逐节作息",fontSize=18.sp,fontWeight=FontWeight.Bold);Text("第 7–8 节起始和晚间节次为推算值，请按学校作息校对。",fontSize=12.sp,color=Muted,lineHeight=22.sp)
        periods.forEachIndexed{i,p->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("第 ${i+1} 节",fontSize=12.sp,modifier=Modifier.width(47.dp));OutlinedTextField(value=p.start,onValueChange={v->periods=periods.mapIndexed{j,x->if(i==j)x.copy(start=v) else x}},modifier=Modifier.weight(1f),singleLine=true,label={Text("开始",fontSize=11.sp)});OutlinedTextField(value=p.end,onValueChange={v->periods=periods.mapIndexed{j,x->if(i==j)x.copy(end=v) else x}},modifier=Modifier.weight(1f),singleLine=true,label={Text("结束",fontSize=11.sp)})}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={try{val t=Term(name,LocalDate.parse(date),count.toInt(),periods);t.validate();save(t);error=""}catch(e:Exception){error=e.message?:"日期或时间格式不正确"}},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("保存学期与作息")}
        HorizontalDivider();Text("数据与隐私",fontSize=18.sp,fontWeight=FontWeight.Bold)
        OutlinedButton(onClick=export,enabled=!state.busy&&state.lessons.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("导出本地备份")}
        OutlinedButton(onClick=restoreFile,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("从备份文件恢复")}
        if(state.hasSnapshot)OutlinedButton(onClick={restoreConfirm=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("恢复上次导入前的课表")}
        Text("课间 0.1.7 · 无广告\n课表保存在本机，无需注册软件账号。只有学校登录和下载需要联网。学校密码不由课程表读取或保存。备份包含课程、教师及地点信息，请妥善保管。\n\n已保存 ${state.lessons.size} 条安排。本版本管理一个当前学期，换学期前建议导出备份。",fontSize=12.sp,color=Muted,lineHeight=23.sp)
    }
    if(restoreConfirm)AlertDialog(onDismissRequest={restoreConfirm=false},title={Text("恢复导入前的课表？")},text={Text("当前课表将被快照替换。建议先导出当前备份。")},confirmButton={TextButton(onClick={restoreConfirm=false;restoreSnapshot()}){Text("恢复")}},dismissButton={TextButton(onClick={restoreConfirm=false}){Text("取消")}})
}
