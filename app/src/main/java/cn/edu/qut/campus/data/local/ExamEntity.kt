package cn.edu.qut.campus.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import cn.edu.qut.campus.data.model.Exam
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey val id: String,
    val courseName: String,
    val examTime: String,
    val classroom: String,
    val seatNumber: String,
    val campus: String
) {
    fun toModel() = Exam(id, courseName, examTime, classroom, seatNumber, campus)

    companion object {
        fun fromModel(m: Exam) = ExamEntity(m.id, m.courseName, m.examTime, m.classroom, m.seatNumber, m.campus)
    }
}

@Dao
interface ExamDao {
    @Query("SELECT * FROM exams ORDER BY examTime ASC")
    fun getAllExams(): Flow<List<ExamEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExams(exams: List<ExamEntity>)

    @Query("DELETE FROM exams")
    suspend fun clearAll()
}
