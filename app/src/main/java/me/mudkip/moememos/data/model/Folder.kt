package me.mudkip.moememos.data.model

import java.time.Instant

data class Folder(
    val name: String,
    val title: String,
    val parent: String? = null,
    val createTime: Instant? = null,
    val updateTime: Instant? = null,
)
