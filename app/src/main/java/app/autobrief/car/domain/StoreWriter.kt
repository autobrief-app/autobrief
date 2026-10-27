package app.autobrief.car.domain

import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.AutoBriefCore.Store
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.TripRecord
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executor

/**
 * Writes the logbook's files for the core one at a time, in order, on [thread]: a save hands over the value to write and
 * returns at once. Every file is rewritten whole; a tap that rewrote the ledger, the outbox and the trips file on the main
 * thread kept it busy for about 7 s on a loaded two-core head unit, and Android closed the app (rehearsal 40486795890,
 * the 비업무 and 정정 저장 taps).
 *
 * - Saves handed over while earlier ones are still being written wait together. Of the saves of one file only the newest
 *   value is written, and the files are written ledger first, then trips, outbox, open trip, settings and the space in
 *   use. Whatever stops the app between two of them leaves the ledger ahead of the rest, which loading repairs
 *   ([Ledger.repair] rebuilds the rows from it): never a row, a sending copy or a cleared mark ahead of the ledger, and
 *   never an open trip cleared before its row is stored.
 * - A task (reading a space, wiping one, changing the stored queue of a space not in use) runs after everything handed
 *   over before it and before everything handed over after it.
 * - A write that fails is tried again after a pause (1 s, doubling up to a minute), and nothing behind it is written
 *   before it: the order holds while storage is full.
 *
 * With no [thread] each save is written on the calling thread before it returns, and a failed write is thrown there
 * (tests of what is written, not of where).
 */
class StoreWriter(private val store: Store, private val thread: Executor?, private val log: (String) -> Unit) {
    /** The files, in the order they are written when saves of several wait together. */
    private enum class File { LEDGER, TRIPS, OUTBOX, OPEN_TRIP, SETTINGS, ACTIVE_SPACE }

    /** A file of a space; the marker of the space in use belongs to none. */
    private data class Key(val file: File, val space: Space?)

    private sealed class Step(val seq: Long)

    /** Saves waiting together: the newest value for each file. Once it is being written, new saves start the next batch. */
    private class Batch(seq: Long) : Step(seq) {
        val saves = HashMap<Key, (Store) -> Unit>()
        var started = false
    }

    /** Store work in the order of the saves; a read reports its failure to its caller instead of being tried again. */
    private class Task(seq: Long, val retry: Boolean, val failed: (Exception) -> Unit, val run: (Store) -> Unit) : Step(seq)

    private val lock = Object()
    private val steps = ArrayDeque<Step>()
    private var nextSeq = 1L
    private var doneSeq = 0L
    private var draining = false

    fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>) = save(File.LEDGER, space) { it.saveLedger(space, chainId, entries) }
    fun saveTrips(space: Space, trips: List<TripRecord>) = save(File.TRIPS, space) { it.saveTrips(space, trips) }

    /** [written] runs on the writing thread once these items are on the disk. */
    fun saveOutbox(space: Space, items: List<OutboxItem>, written: () -> Unit = {}) = save(File.OUTBOX, space) {
        it.saveOutbox(space, items)
        written()
    }

    fun saveOpenTrip(space: Space, trip: OpenTripRecord?) = save(File.OPEN_TRIP, space) { it.saveOpenTrip(space, trip) }
    fun saveSettings(space: Space, settings: AppSettings) = save(File.SETTINGS, space) { it.saveSettings(space, settings) }
    fun setActiveSpace(space: Space) = save(File.ACTIVE_SPACE, null) { it.setActiveSpace(space) }

    /** Empties [space] once everything handed over before is written; saves handed over after it go into the empty space. */
    fun wipe(space: Space) = task(retry = true) { it.wipe(space) }

    /** The stored queue of a space not in use, changed by [edit] once everything handed over before is written. */
    fun updateOutbox(space: Space, edit: (List<OutboxItem>) -> List<OutboxItem>) =
        task(retry = true) { it.saveOutbox(space, edit(it.load(space).outbox)) }

    /** What [block] reads once everything handed over before is written; its failure completes the result. */
    fun <T> read(block: (Store) -> T): CompletableDeferred<T> {
        val result = CompletableDeferred<T>()
        task(retry = false, failed = { result.completeExceptionally(it) }) { result.complete(block(it)) }
        return result
    }

    /** Returns once everything handed over before is written. */
    suspend fun awaitWritten() {
        read { }.await()
    }

    /**
     * Blocks until everything handed over before is written, at most [timeoutMs]; false when it was not. For the end of
     * the process, never on the writing thread itself.
     */
    fun flush(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            val target = steps.lastOrNull()?.seq ?: return true
            while (doneSeq < target) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) return false
                lock.wait(leftMs)
            }
        }
        return true
    }

    private fun save(file: File, space: Space?, write: (Store) -> Unit) {
        synchronized(lock) {
            val last = steps.lastOrNull()
            val batch = if (last is Batch && !last.started) last else Batch(nextSeq++).also { steps.addLast(it) }
            batch.saves[Key(file, space)] = write
        }
        drainSoon()
    }

    private fun task(retry: Boolean, failed: (Exception) -> Unit = {}, run: (Store) -> Unit) {
        synchronized(lock) { steps.addLast(Task(nextSeq++, retry, failed, run)) }
        drainSoon()
    }

    private fun drainSoon() {
        synchronized(lock) {
            if (draining) return
            draining = true
        }
        if (thread == null) drain() else thread.execute(::drain)
    }

    private fun drain() {
        var pauseMs = RETRY_FIRST_MS
        while (true) {
            val step = synchronized(lock) {
                steps.firstOrNull() ?: run {
                    draining = false
                    lock.notifyAll()
                    return
                }
            }
            val failure = try {
                run(step)
                null
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                synchronized(lock) {
                    steps.removeFirst()
                    doneSeq = step.seq
                    lock.notifyAll()
                }
                pauseMs = RETRY_FIRST_MS
                continue
            }
            log("STORE_WRITE_FAILED error=${failure::class.java.simpleName}")
            if (thread == null) {
                synchronized(lock) { draining = false }
                throw failure
            }
            try {
                Thread.sleep(pauseMs)
            } catch (e: InterruptedException) {
                // the thread is being shut down: what is left stays queued for the next save to write
                synchronized(lock) { draining = false }
                Thread.currentThread().interrupt()
                return
            }
            pauseMs = (pauseMs * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    /** Runs [step]; a batch drops each file once it is written, so a retry starts at the one that failed. */
    private fun run(step: Step) {
        when (step) {
            is Batch -> {
                val order = synchronized(lock) {
                    step.started = true
                    step.saves.keys.sortedWith(compareBy({ it.file.ordinal }, { it.space?.ordinal ?: -1 }))
                }
                for (key in order) {
                    step.saves.getValue(key)(store)
                    step.saves.remove(key)
                }
            }
            is Task -> try {
                step.run(store)
            } catch (e: Exception) {
                if (step.retry) throw e
                step.failed(e)
            }
        }
    }

    companion object {
        const val RETRY_FIRST_MS = 1_000L
        const val RETRY_MAX_MS = 60_000L
    }
}
