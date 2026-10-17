package cn.kejian.timetable.data;

import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

/** Java annotation processing keeps Room independent of the Kotlin symbol-processing plugin. */
public final class Records {
    @Entity(tableName="courses")
    public static class Course {
        @PrimaryKey @NonNull public String id="";
        @NonNull public String title="";
        @NonNull public String code="";
    }
    @Entity(tableName="meetings", foreignKeys=@ForeignKey(entity=Course.class,parentColumns="id",childColumns="courseId",onDelete=ForeignKey.CASCADE),indices=@Index("courseId"))
    public static class Meeting {
        @PrimaryKey @NonNull public String id="";
        @NonNull public String courseId="";
        public int day,start,end;
        @NonNull public String weeks="";
        @NonNull public String teacher="";
        @NonNull public String campus="";
        @NonNull public String room="";
        @NonNull public String source="";
        @NonNull public String raw="";
    }
    @Entity(tableName="semester")
    public static class Semester {
        @PrimaryKey public int id=1;
        @NonNull public String name="";
        @NonNull public String monday="";
        public int weekCount=20;
        @NonNull public String periods="";
    }
    public static class FullRule {
        @Embedded public Meeting meeting;
        @NonNull public String title="";
        @NonNull public String code="";
    }
    @Dao
    public interface TimetableDao {
        @Query("SELECT m.*, c.title, c.code FROM meetings m JOIN courses c ON m.courseId=c.id ORDER BY m.day,m.start") List<FullRule> all();
        @Query("SELECT * FROM semester WHERE id=1") Semester term();
        @Upsert void course(Course course);
        @Upsert void meeting(Meeting meeting);
        @Upsert void term(Semester term);
        @Query("DELETE FROM meetings WHERE id=:id") void delete(String id);
        @Query("DELETE FROM meetings") void clearMeetings();
        @Query("DELETE FROM courses") void clearCourses();
    }
    @Database(entities={Course.class,Meeting.class,Semester.class},version=1,exportSchema=false)
    public abstract static class Store extends RoomDatabase {
        public abstract TimetableDao dao();
    }
}
