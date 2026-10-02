package com.angkyria.karooworkout.data

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared OnStreamState consumers: each owner (workout gate, workout detail,
 * page fields) states the data types it wants; a type streams while any owner
 * wants it, so nothing runs that isn't drawn. Main thread only for [want].
 */
class StreamHub(private val karoo: KarooSystemService) {

    private val wanted = mutableMapOf<String, Set<String>>()
    private val consumers = mutableMapOf<String, String>() // dataTypeId -> consumerId

    private val _values = MutableStateFlow<Map<String, Map<String, Double>>>(emptyMap())

    /** Latest field values per streaming data type. */
    val values: StateFlow<Map<String, Map<String, Double>>> = _values

    fun want(owner: String, typeIds: Set<String>) {
        wanted[owner] = typeIds
        val all = wanted.values.flatten().toSet()
        (consumers.keys - all).forEach { typeId ->
            consumers.remove(typeId)?.let { karoo.removeConsumer(it) }
            _values.update { it - typeId }
        }
        (all - consumers.keys).forEach { typeId ->
            consumers[typeId] = karoo.addConsumer<OnStreamState>(OnStreamState.StartStreaming(typeId)) { event ->
                val fields = (event.state as? StreamState.Streaming)?.dataPoint?.values
                // consumer callbacks arrive off the main thread: atomic read-modify-write
                _values.update { if (fields.isNullOrEmpty()) it - typeId else it + (typeId to fields) }
            }
        }
    }

    fun clear() {
        consumers.values.forEach { karoo.removeConsumer(it) }
        consumers.clear()
        wanted.clear()
        _values.value = emptyMap()
    }
}
