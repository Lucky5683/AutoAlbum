package com.autoalbum.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutomationCoordinator {
    enum class State {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPED
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val listeners = mutableListOf<(State) -> Unit>()

    @Synchronized
    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(_state.value)
    }

    @Synchronized
    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun start() {
        if (_state.value != State.RUNNING) {
            _state.value = State.RUNNING
            notifyListeners()
        }
    }

    @Synchronized
    fun pause() {
        if (_state.value == State.RUNNING) {
            _state.value = State.PAUSED
            notifyListeners()
        }
    }

    @Synchronized
    fun stop() {
        if (_state.value != State.STOPPED) {
            _state.value = State.STOPPED
            notifyListeners()
        }
    }

    @Synchronized
    fun reset() {
        if (_state.value != State.IDLE) {
            _state.value = State.IDLE
            notifyListeners()
        }
    }

    fun onIncomingCall() {
        if (_state.value == State.RUNNING) {
            pause()
        }
    }

    fun onScreenLocked() {
        stop()
    }

    private fun notifyListeners() {
        val current = _state.value
        listeners.toList().forEach { it(current) }
    }
}
