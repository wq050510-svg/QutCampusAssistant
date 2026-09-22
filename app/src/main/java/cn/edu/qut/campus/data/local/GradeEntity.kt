package cn.edu.qut.campus.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import cn.edu.qut.campus.data.model.Grade
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "grades")
data class GradeEntity(
    @PrimaryKey val id: String,
    val courseName: String,
    val score: String,
    val scoreNumber: Double,
    val gradePoint: Double,
    val credit: Double,
    val courseType: String,
    val academicYear: String,
    val semester: String,
    val examNature: String = "正常考试",
    val college: String = ""
) {
    fun toModel() = Grade(id, courseName, score, scoreNumber, gradePoint, credit, courseType, academicYear, semester, examNature, college)

    companion object {
        fun fromModel(m: Grade) = GradeEntity(
            m.id, m.courseName, m.score, m.scoreNumber, m.gradePoint, m.credit, m.courseType, m.academicYear, m.semester, m.examNature, m.college
        )
    }
}

@Dao
interface GradeDao {
    @Query("SELECT * FROM grades ORDER BY academicYear DESC, semester DESC")
    fun getAllGrades(): Flow<List<GradeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrades(grades: List<GradeEntity>)

    @Query("SELECT COUNT(*) FROM grades")
    suspend fun getCount(): Int

    @Query("DELETE FROM grades")
    suspend fun clearAll()
}
