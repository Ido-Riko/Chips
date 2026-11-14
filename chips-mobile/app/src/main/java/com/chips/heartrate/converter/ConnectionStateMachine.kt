// Ported from Ido-Riko/MiBand-Converter @ a472d64a6743454db0b63c902684be36426f2ff9.
// Owner-authorized reuse. CHIPS changes are listed in docs/HEART_RATE.md.
// Fixed: ConnectionStateMachine.kt
package com.chips.heartrate.converter

import android.util.Log

/**
 * State machine for managing Mi-Band connection states with proper transitions
 */
class ConnectionStateMachine {
    companion object {
        private const val TAG = "ConnectionStateMachine"
    }

    enum class State {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    enum class Event {
        START_SCAN,
        DEVICE_FOUND,
        CONNECTION_INITIATED,
        CONNECTED,
        DISCONNECTED,
        CONNECTION_FAILED,
        DISCONNECT_REQUESTED,
        SCAN_TIMEOUT,
        SCAN_FAILED
    }

    private var currentState: State = State.DISCONNECTED
    private var lastTransitionTime = System.currentTimeMillis()
    private val stateListeners = mutableListOf<StateChangeListener>()

    interface StateChangeListener {
        fun onStateChanged(oldState: State, newState: State, event: Event)
    }

    fun addListener(listener: StateChangeListener) {
        stateListeners.add(listener)
    }

    fun removeListener(listener: StateChangeListener) {
        stateListeners.remove(listener)
    }

    fun getCurrentState(): State = currentState

    fun getTimeSinceLastTransition(): Long =
        System.currentTimeMillis() - lastTransitionTime

    /**
     * Attempt to transition to a new state based on an event
     * @return true if transition was valid and executed, false if invalid
     */
    @Synchronized
    fun transition(event: Event): Boolean {
        val newState = when (currentState) {
            State.DISCONNECTED -> when (event) {
                Event.START_SCAN -> State.SCANNING
                Event.CONNECTION_INITIATED -> State.CONNECTING  // FIX: Allow direct connection
                else -> null
            }
            State.SCANNING -> when (event) {
                Event.DEVICE_FOUND -> State.CONNECTING
                Event.CONNECTION_INITIATED -> State.CONNECTING
                Event.SCAN_TIMEOUT -> State.DISCONNECTED
                Event.SCAN_FAILED -> State.DISCONNECTED
                else -> null
            }
            State.CONNECTING -> when (event) {
                Event.CONNECTED -> State.CONNECTED
                Event.CONNECTION_FAILED -> State.DISCONNECTED
                Event.DISCONNECTED -> State.DISCONNECTED
                Event.DISCONNECT_REQUESTED -> State.DISCONNECTING
                else -> null
            }
            State.CONNECTED -> when (event) {
                Event.DISCONNECTED -> State.DISCONNECTED
                Event.DISCONNECT_REQUESTED -> State.DISCONNECTING
                Event.CONNECTION_FAILED -> State.DISCONNECTED
                else -> null
            }
            State.DISCONNECTING -> when (event) {
                Event.DISCONNECTED -> State.DISCONNECTED
                Event.CONNECTION_FAILED -> State.DISCONNECTED
                else -> null
            }
        }

        return if (newState != null && newState != currentState) {
            val oldState = currentState
            currentState = newState
            lastTransitionTime = System.currentTimeMillis()

            Log.d(TAG, "State transition: $oldState -> $newState (event: $event)")

            // Notify listeners
            stateListeners.forEach { listener ->
                try {
                    listener.onStateChanged(oldState, newState, event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in state listener: ${e.message}", e)
                }
            }

            true
        } else {
            if (newState == null) {
                Log.w(TAG, "Invalid transition: $currentState + $event")
            }
            false
        }
    }

    /**
     * Force set state (use sparingly, only for initialization or error recovery)
     */
    @Synchronized
    fun forceState(state: State, reason: String = "forced") {
        if (state != currentState) {
            val oldState = currentState
            currentState = state
            lastTransitionTime = System.currentTimeMillis()
            Log.w(TAG, "Force state change: $oldState -> $state (reason: $reason)")
        }
    }

    /**
     * Check if we're stuck in a state for too long
     */
    fun isStuckInState(maxDurationMs: Long): Boolean {
        return getTimeSinceLastTransition() > maxDurationMs
    }
}
