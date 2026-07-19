package dev.clipvault.app.ui

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.data.ClipQuery

class ClipPagingSource(
    private val app: ClipVaultApp,
    private val queryFactory: (limit: Int, offset: Int) -> ClipQuery,
) : PagingSource<Int, ClipItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ClipItem> {
        val offset = params.key ?: 0
        return try {
            val repository = app.repository() ?: return LoadResult.Page(emptyList(), null, null)
            val page = repository.query(queryFactory(params.loadSize.coerceAtMost(200), offset))
            LoadResult.Page(
                data = page.items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (page.hasMore) page.nextOffset else null,
            )
        } catch (error: RuntimeException) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ClipItem>): Int? {
        val anchor = state.anchorPosition ?: return null
        return state.closestPageToPosition(anchor)?.let { page ->
            page.prevKey?.plus(state.config.pageSize) ?: page.nextKey?.minus(state.config.pageSize)
        }?.coerceAtLeast(0)
    }
}
