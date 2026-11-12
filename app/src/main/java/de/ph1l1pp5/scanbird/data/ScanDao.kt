package de.ph1l1pp5.scanbird.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY createdAt DESC")
    fun allNewestFirst(): Flow<List<ScanEntity>>

    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Query("SELECT * FROM scans WHERE pdfFileName = :fileName LIMIT 1")
    suspend fun findByFile(fileName: String): ScanEntity?

    @Update
    suspend fun update(scan: ScanEntity)

    @Delete
    suspend fun delete(scan: ScanEntity)
}
