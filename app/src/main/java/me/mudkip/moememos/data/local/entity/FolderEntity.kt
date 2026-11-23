package me.mudkip.moememos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.mudkip.moememos.data.model.Folder
import java.time.Instant

@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "name"])
    ]
)
data class FolderEntity(
    @PrimaryKey
    val name: String,
    val accountKey: String,
    val title: String,
    val parent: String? = null,
    val createTime: Instant? = null,
    val updateTime: Instant? = null,
) {
    fun toFolder(): Folder {
        return Folder(
            name = name,
            title = title,
            parent = parent,
            createTime = createTime,
            updateTime = updateTime
        )
    }
}
