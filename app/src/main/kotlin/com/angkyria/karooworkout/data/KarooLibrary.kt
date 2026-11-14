package com.angkyria.karooworkout.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Reads the Karoo's own workout library through the exported Hammerhead sync service
 * (read-only: transaction 9, getDocumentTypeList). Null on any failure, with one log line.
 */
class KarooLibrary(private val context: Context) {
    suspend fun read(): List<LibraryWorkout>? {
        var connection: ServiceConnection? = null
        return try {
            val result = withTimeout(TIMEOUT_MS) {
                val conn = Conn()
                connection = conn
                val binder = bind(conn) ?: return@withTimeout Fail("bind failed")
                // Own scope: a stuck transact cannot be interrupted, so the timeout abandons
                // this thread (its Parcels recycle when it returns; the late result is dropped).
                val job = CoroutineScope(Dispatchers.IO).async { readAll(binder) { isActive } }
                try {
                    job.await()
                } finally {
                    job.cancel()
                }
            }
            when (result) {
                is Fail -> {
                    Log.w(TAG, "library read failed: ${result.reason}")
                    null
                }
                is Ok -> {
                    Log.i(TAG, "library read ${result.workouts.size} workouts")
                    result.workouts
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "library read failed: timeout")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "library read failed: $e")
            null
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    private class Conn : ServiceConnection {
        var onBound: ((IBinder?) -> Unit)? = null
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            onBound?.invoke(service)
            onBound = null
        }
        override fun onServiceDisconnected(name: ComponentName?) = Unit
        override fun onNullBinding(name: ComponentName?) {
            onBound?.invoke(null)
            onBound = null
        }
    }

    /** Binds; null when the system refuses or hands no binder. Resumes on the main thread. */
    private suspend fun bind(conn: Conn): IBinder? = suspendCancellableCoroutine { cont ->
        conn.onBound = { binder -> if (cont.isActive) cont.resume(binder) }
        val intent = Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE))
        val ok = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            conn.onBound = null
            if (cont.isActive) cont.resume(null)
        }
    }

    private sealed interface Outcome
    private class Ok(val workouts: List<LibraryWorkout>) : Outcome
    private class Fail(val reason: String) : Outcome

    private fun readAll(binder: IBinder, active: () -> Boolean): Outcome {
        val loader = try {
            context.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        } catch (e: Exception) {
            return Fail("no package context: $e")
        }
        val out = ArrayList<LibraryWorkout>()
        var skip = 0
        for (pageNo in 0 until MAX_PAGES) {
            if (!active()) return Fail("cancelled")
            val items = page(binder, loader, skip) ?: return Fail("no reply at skip $skip")
            items.forEach { item -> item?.let { LibraryMapper.workout(it) }?.let(out::add) }
            if (items.size < PAGE) {
                return if (out.isEmpty()) Fail("no workouts mapped") else Ok(out)
            }
            skip += PAGE
        }
        return Fail("more than $MAX_PAGES pages")
    }

    private fun page(binder: IBinder, loader: ClassLoader, skip: Int): List<*>? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(DOC_TYPE)
            data.writeInt(skip)
            data.writeInt(PAGE)
            data.writeInt(0) // no options Bundle
            binder.transact(TX_GET_LIST, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return null
            val bundle = Bundle.CREATOR.createFromParcel(reply)
            bundle.classLoader = loader
            val wrapper = bundle.get(KEY_VALUE) ?: return null
            if (wrapper is List<*>) return wrapper
            val listField = generateSequence<Class<*>>(wrapper.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { List::class.java.isAssignableFrom(it.type) } ?: return null
            listField.isAccessible = true
            return listField.get(wrapper) as? List<*>
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "WorkoutExtension"
        const val PACKAGE = "io.hammerhead.datasyncservice"
        const val SERVICE = "io.hammerhead.datasyncservice.v2.DataSyncService"
        const val ACTION = "databaseOperationsController"
        const val DESCRIPTOR = "io.hammerhead.datasyncservice.v2.DatabaseOperationsAIDL"
        const val DOC_TYPE = "workout"
        const val KEY_VALUE = "value"
        const val TX_GET_LIST = 9
        const val PAGE = 10
        const val MAX_PAGES = 50
        const val TIMEOUT_MS = 5_000L
    }
}
