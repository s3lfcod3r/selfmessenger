package com.selfmessenger.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Collections

/** Welcher Chat gerade offen ist (Pubkey) — für „gelesen"-Bestätigungen. */
object AppState { @Volatile var openChatPub: String? = null }

/** Empfangene Nachrichten, die noch als „gelesen" zu bestätigen sind (bis der Chat geöffnet wird). */
object PendingRead {
    private val map = Collections.synchronizedMap(HashMap<String, MutableSet<String>>())
    fun add(pub: String, id: String) { synchronized(map) { map.getOrPut(pub) { mutableSetOf() }.add(id) } }
    fun take(pub: String): Set<String> = synchronized(map) { HashSet(map.remove(pub) ?: emptySet()) }
}

/** Status-Bestätigungen (zugestellt/gelesen) für MEINE gesendeten Nachrichten. Tripel = (pub, id, status). */
object AckBus {
    private val _events = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    fun emit(pub: String, id: String, status: String) { _events.tryEmit(Triple(pub, id, status)) }
}
