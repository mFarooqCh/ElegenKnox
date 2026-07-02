package com.elegen.elegencashbook.domain.usecase

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.Transaction
import com.elegen.elegencashbook.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/** In-memory fake honouring the repository contract (soft delete keeps the row). */
private class FakeTransactionRepository : TransactionRepository {
    private val rows = MutableStateFlow<List<Pair<Transaction, Long?>>>(emptyList()) // tx to deletedAt

    override fun observeEntries(bookId: String): Flow<List<Transaction>> =
        rows.map { list ->
            list.filter { (tx, deletedAt) -> tx.bookId == bookId && deletedAt == null }
                .map { it.first }
                .sortedBy { it.createdAt }
        }

    override suspend fun add(
        bookId: String, type: EntryType, amount: Money, description: String, createdAt: Long,
    ): Transaction {
        val tx = Transaction(
            id = UUID.randomUUID().toString(), bookId = bookId, type = type,
            amount = amount, description = description, createdAt = createdAt, updatedAt = createdAt,
        )
        rows.value = rows.value + (tx to null)
        return tx
    }

    override suspend fun update(transaction: Transaction) {
        rows.value = rows.value.map { (tx, del) -> if (tx.id == transaction.id) transaction to del else tx to del }
    }

    override suspend fun softDelete(id: String) {
        rows.value = rows.value.map { (tx, del) -> if (tx.id == id) tx to System.currentTimeMillis() else tx to del }
    }

    override suspend fun restore(id: String) {
        rows.value = rows.value.map { (tx, del) -> if (tx.id == id) tx to null else tx to del }
    }
}

class UseCasesTest {

    private fun tx(paisa: Long, type: EntryType) = Transaction(
        id = UUID.randomUUID().toString(), bookId = "b", type = type,
        amount = Money(paisa), description = "", createdAt = 0, updatedAt = 0,
    )

    @Nested
    inner class BalanceMath {
        private val getBalance = GetBalance()

        @Test
        fun `empty book balances to zero`() {
            val s = getBalance(emptyList())
            assertEquals(Money.ZERO, s.totalIn)
            assertEquals(Money.ZERO, s.totalOut)
            assertEquals(Money.ZERO, s.net)
        }

        @Test
        fun `cash in and out are separated and net is exact`() {
            val s = getBalance(
                listOf(
                    tx(10, EntryType.CASH_IN),   // 0.10
                    tx(20, EntryType.CASH_IN),   // 0.20
                    tx(5, EntryType.CASH_OUT),   // 0.05
                )
            )
            assertEquals(Money(30), s.totalIn)
            assertEquals(Money(5), s.totalOut)
            assertEquals(Money(25), s.net)
            assertEquals("0.25", s.net.format())
        }

        @Test
        fun `net can go negative`() {
            val s = getBalance(listOf(tx(100, EntryType.CASH_OUT)))
            assertEquals(Money(-100), s.net)
            assertTrue(s.net.isNegative)
        }

        @Test
        fun `overflow throws instead of wrapping`() {
            val entries = listOf(tx(Long.MAX_VALUE, EntryType.CASH_IN), tx(1, EntryType.CASH_IN))
            assertThrows(ArithmeticException::class.java) { getBalance(entries) }
        }
    }

    @Nested
    inner class TransactionCrud {
        private val repo = FakeTransactionRepository()
        private val add = AddTransaction(repo)
        private val update = UpdateTransaction(repo)
        private val delete = DeleteTransaction(repo)
        private val restore = RestoreTransaction(repo)
        private val observe = ObserveBookEntries(repo)
        private val getBalance = GetBalance()

        @Test
        fun `add rejects zero and negative amounts`() = runTest {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { add("b", EntryType.CASH_IN, Money.ZERO, "", 1) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { add("b", EntryType.CASH_IN, Money(-1), "", 1) }
            }
        }

        @Test
        fun `update rejects non-positive amounts`() = runTest {
            val tx = add("b", EntryType.CASH_IN, Money(100), "", 1)
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { update(tx.copy(amount = Money.ZERO)) }
            }
        }

        @Test
        fun `delete then restore keeps balance correct at each step`() = runTest {
            add("b", EntryType.CASH_IN, Money.parse("10")!!, "", 1)
            val victim = add("b", EntryType.CASH_OUT, Money.parse("3")!!, "", 2)

            assertEquals(Money(700), getBalance(observe("b").first()).net)

            delete(victim.id)
            assertEquals(Money(1000), getBalance(observe("b").first()).net)
            assertEquals(1, observe("b").first().size)

            restore(victim.id)
            assertEquals(Money(700), getBalance(observe("b").first()).net)
            assertEquals(2, observe("b").first().size)
        }

        @Test
        fun `entries come back chronological`() = runTest {
            add("b", EntryType.CASH_IN, Money(2), "second", 200)
            add("b", EntryType.CASH_IN, Money(1), "first", 100)
            val list = observe("b").first()
            assertEquals(listOf("first", "second"), list.map { it.description })
        }
    }
}
