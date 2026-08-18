package com.selfmessenger.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Kleiner In-App-Event-Kanal: der app-globale Briefkasten-Client (MainNav) meldet
 * offline zugestellte Nachrichten, ein gerade offener ChatScreen zeigt sie live an.
 * Paar = (Kontakt-Pubkey, Klartext).
 */
object OfflineBus {
    private val _events = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()
    fun emit(pub: String, text: String) { _events.tryEmit(pub to text) }
}
