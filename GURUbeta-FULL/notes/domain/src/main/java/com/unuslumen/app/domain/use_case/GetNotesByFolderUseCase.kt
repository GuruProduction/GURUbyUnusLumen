package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.NoteRepository
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory
class GetNotesByFolderUseCase(
    private val notesRepository: NoteRepository,
    @Named("defaultDispatcher") private val defaultDispatcher: CoroutineDispatcher
) {
    operator fun invoke(id: String, order: Order) = notesRepository.getNotesByFolder(id).map { list ->
        when (order.orderType) {
            is OrderType.ASC -> {
                when (order) {
                    is Order.Alphabetical -> list.sortedWith(compareBy({!it.pinned}, { it.title }))
                    is Order.DateCreated -> list.sortedWith(compareBy({!it.pinned}, { it.createdDate }))
                    is Order.DateModified -> list.sortedWith(compareBy({!it.pinned}, { it.updatedDate }))
                    else -> list.sortedWith(compareBy({!it.pinned}, { it.updatedDate }))
                }
            }
            is OrderType.DESC -> {
                when (order) {
                    is Order.Alphabetical -> list.sortedWith(compareBy({it.pinned}, { it.title })).reversed()
                    is Order.DateCreated -> list.sortedWith(compareBy({it.pinned}, { it.createdDate })).reversed()
                    is Order.DateModified -> list.sortedWith(compareBy({it.pinned}, { it.updatedDate })).reversed()
                    else -> list.sortedWith(compareBy({it.pinned}, { it.updatedDate })).reversed()
                }
            }
        }
    }.flowOn(defaultDispatcher)
}
