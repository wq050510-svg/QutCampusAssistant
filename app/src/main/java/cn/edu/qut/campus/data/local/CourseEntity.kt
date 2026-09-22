package cn.edu.qut.campus.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import cn.edu.qut.campus.data.model.Course
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val classroom: String,
    val teacher: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeksDescription: String,
    val credit: Double,
    val isRetake: Boolean,
    val isPractice: Boolean,
    val colorHex: String
) {
    fun toModel(): Course {
        return Course(
            id = id,
            name = name,
            classroom = classroom,
            teacher = teacher,
            dayOfWeek = dayOfWeek,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            weeksDescription = weeksDescription,
            weeksList = Course.parseWeeks(weeksDescription),
            credit = credit,
            isRetake = isRetake,
            isPractice = isPractice,
            colorHex = colorHex
        )
    }

    companion object {
        fun fromModel(model: Course): CourseEntity {
            return CourseEntity(
                id = model.id,
                name = model.name,
                classroom = model.classroom,
                teacher = model.teacher,
                dayOfWeek = model.dayOfWeek,
                startPeriod = model.startPeriod,
                endPeriod = model.endPeriod,
                weeksDescription = model.weeksDescription,
                credit = model.credit,
                isRetake = model.isRetake,
                isPractice = model.isPractice,
                colorHex = model.colorHex
            )
        }
    }
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY dayOfWeek ASC, startPeriod ASC")
    fun getAllCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek ASC, startPeriod ASC")
    suspend fun getAllCoursesSync(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE dayOfWeek = :dayOfWeek ORDER BY startPeriod ASC")
    suspend fun getCoursesForDay(dayOfWeek: Int): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Query("DELETE FROM courses")
    suspend fun clearAll()
}
