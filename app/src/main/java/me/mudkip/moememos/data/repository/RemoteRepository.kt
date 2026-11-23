package me.mudkip.moememos.data.repository

import com.skydoves.sandwich.ApiResponse
import me.mudkip.moememos.data.model.Folder
import me.mudkip.moememos.data.model.Memo
import me.mudkip.moememos.data.model.MemoLocation
import me.mudkip.moememos.data.model.MemoLocationChange
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.model.Resource
import me.mudkip.moememos.data.model.User
import okhttp3.MediaType
import java.io.InputStream
import java.time.Instant

abstract class RemoteRepository {
    abstract suspend fun listMemos(): ApiResponse<List<Memo>>
    abstract suspend fun listArchivedMemos(): ApiResponse<List<Memo>>
    abstract suspend fun listWorkspaceMemos(pageSize: Int, pageToken: String?): ApiResponse<Pair<List<Memo>, String?>>

    abstract suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>? = null,
        createdAt: Instant? = null,
        location: MemoLocation? = null,
        folder: String? = null,
        title: String? = null,
    ): ApiResponse<Memo>

    abstract suspend fun updateMemo(
        remoteId: String,
        content: String? = null,
        resourceRemoteIds: List<String>? = null,
        visibility: MemoVisibility? = null,
        tags: List<String>? = null,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        location: MemoLocationChange? = null,
        folder: String? = null,
        title: String? = null,
    ): ApiResponse<Memo>

    abstract suspend fun deleteMemo(remoteId: String): ApiResponse<Unit>

    open suspend fun listFolders(): ApiResponse<List<Folder>> = ApiResponse.Success(emptyList())
    open suspend fun createFolder(title: String, parent: String? = null): ApiResponse<Folder> = ApiResponse.Failure.Exception(UnsupportedOperationException())
    open suspend fun updateFolder(name: String, title: String? = null, parent: String? = null): ApiResponse<Folder> = ApiResponse.Failure.Exception(UnsupportedOperationException())
    open suspend fun deleteFolder(name: String): ApiResponse<Unit> = ApiResponse.Success(Unit)

    abstract suspend fun listResources(): ApiResponse<List<Resource>>

    abstract suspend fun createResource(
        filename: String,
        type: MediaType?,
        contentLength: Long?,
        openInputStream: () -> InputStream,
        memoRemoteId: String? = null
    ): ApiResponse<Resource>

    abstract suspend fun deleteResource(remoteId: String): ApiResponse<Unit>
    abstract suspend fun getCurrentUser(): ApiResponse<User>
}
