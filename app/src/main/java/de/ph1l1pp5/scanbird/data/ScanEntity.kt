package de.ph1l1pp5.scanbird.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val pdfFileName: String
)
