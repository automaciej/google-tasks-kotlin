package pl.blizinski.googletasksstore.internal.sync

import pl.blizinski.googletasksstore.internal.db.OpStatus
import pl.blizinski.googletasksstore.internal.db.OpType
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingOpsMergeTest {

    private fun op(type: OpType, createdAt: Long = 0L) = PendingOpEntity(
        id = "$type-$createdAt",
        type = type,
        entityLocalId = "E1",
        listLocalId = "L1",
        payloadJson = "",
        createdAt = createdAt,
        status = OpStatus.PENDING,
    )

    private val processor = PendingOpsProcessor(FakeLocalTasksStore(), FakeNetworkTasksSource())

    @Test
    fun emptyListMergesToEmpty() {
        assertTrue(processor.merge(emptyList()).isEmpty())
    }

    @Test
    fun createThenDeleteCancelsOut() {
        val result = processor.merge(listOf(op(OpType.CREATE_TASK, 1), op(OpType.DELETE_TASK, 2)))
        assertTrue(result.isEmpty(), "CREATE + DELETE should cancel each other out")
    }

    @Test
    fun updateThenDeleteKeepsOnlyDelete() {
        val result = processor.merge(listOf(op(OpType.UPDATE_TASK, 1), op(OpType.DELETE_TASK, 2)))
        assertEquals(1, result.size)
        assertEquals(OpType.DELETE_TASK, result.first().type)
    }

    @Test
    fun multipleUpdatesKeepsLastOnly() {
        val ops = listOf(
            op(OpType.UPDATE_TASK, 1),
            op(OpType.UPDATE_TASK, 2),
            op(OpType.UPDATE_TASK, 3),
        )
        val result = processor.merge(ops)
        assertEquals(1, result.size)
        assertEquals(OpType.UPDATE_TASK, result.first().type)
        assertEquals(3L, result.first().createdAt, "Should keep the last UPDATE")
    }

    @Test
    fun createThenUpdateKeepsBoth() {
        val result = processor.merge(listOf(op(OpType.CREATE_TASK, 1), op(OpType.UPDATE_TASK, 2)))
        assertEquals(2, result.size)
        assertEquals(OpType.CREATE_TASK, result[0].type)
        assertEquals(OpType.UPDATE_TASK, result[1].type)
    }

    @Test
    fun createThenCompleteKeepsBoth() {
        val result = processor.merge(listOf(op(OpType.CREATE_TASK, 1), op(OpType.COMPLETE_TASK, 2)))
        assertEquals(2, result.size)
        assertEquals(OpType.CREATE_TASK, result[0].type)
        assertEquals(OpType.COMPLETE_TASK, result[1].type)
    }

    @Test
    fun singleOpPassesThrough() {
        listOf(OpType.CREATE_TASK, OpType.UPDATE_TASK, OpType.COMPLETE_TASK, OpType.DELETE_TASK)
            .forEach { type ->
                val result = processor.merge(listOf(op(type)))
                assertEquals(1, result.size)
                assertEquals(type, result.first().type)
            }
    }
}
