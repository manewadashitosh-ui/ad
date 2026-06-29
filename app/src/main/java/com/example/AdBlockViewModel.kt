package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class VpnEvent {
    object RequestStart : VpnEvent()
    object RequestStop : VpnEvent()
}

class AdBlockViewModel : ViewModel() {

    private val _eventFlow = MutableSharedFlow<VpnEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    val isRunning = AdBlockManager.isRunning.asStateFlow()
    val totalBlocked = AdBlockManager.totalBlocked.asStateFlow()
    val totalAllowed = AdBlockManager.totalAllowed.asStateFlow()
    
    // UI-side filters for Logs
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _logFilter = MutableStateFlow(LogFilter.ALL)
    val logFilter = _logFilter.asStateFlow()

    val logs = combine(AdBlockManager.logs, _searchQuery, _logFilter) { rawLogs, query, filter ->
        rawLogs.filter { log ->
            val matchesQuery = query.isEmpty() || log.domain.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                LogFilter.ALL -> true
                LogFilter.BLOCKED -> log.isBlocked
                LogFilter.ALLOWED -> !log.isBlocked
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customBlocklist = AdBlockManager.customBlocklist.asStateFlow()
    val customAllowlist = AdBlockManager.customAllowlist.asStateFlow()

    val dnsProfile = AdBlockManager.dnsProfile.asStateFlow()
    val customDnsIp = AdBlockManager.customDnsIp.asStateFlow()

    private val _duration = MutableStateFlow("00:00:00")
    val duration = _duration.asStateFlow()

    init {
        // Start duration ticker
        viewModelScope.launch {
            while (true) {
                val start = AdBlockManager.connectionStartTime.value
                val running = AdBlockManager.isRunning.value
                if (running && start != null) {
                    val diffMs = System.currentTimeMillis() - start
                    val sec = (diffMs / 1000) % 60
                    val min = (diffMs / (1000 * 60)) % 60
                    val hr = (diffMs / (1000 * 60 * 60)) % 24
                    _duration.value = String.format("%02d:%02d:%02d", hr, min, sec)
                } else {
                    _duration.value = "00:00:00"
                }
                delay(1000)
            }
        }
    }

    fun toggleVpn() {
        viewModelScope.launch {
            if (isRunning.value) {
                _eventFlow.emit(VpnEvent.RequestStop)
            } else {
                _eventFlow.emit(VpnEvent.RequestStart)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateLogFilter(filter: LogFilter) {
        _logFilter.value = filter
    }

    fun addCustomBlock(domain: String) {
        if (domain.isNotEmpty()) {
            AdBlockManager.addCustomBlock(domain)
        }
    }

    fun removeCustomBlock(domain: String) {
        AdBlockManager.removeCustomBlock(domain)
    }

    fun addCustomAllow(domain: String) {
        if (domain.isNotEmpty()) {
            AdBlockManager.addCustomAllow(domain)
        }
    }

    fun removeCustomAllow(domain: String) {
        AdBlockManager.removeCustomAllow(domain)
    }

    fun changeDnsProfile(profile: String, customIp: String) {
        AdBlockManager.saveProfile(profile, customIp)
    }

    fun clearLogs() {
        AdBlockManager.clearLogs()
    }

    fun resetStats() {
        AdBlockManager.resetStats()
    }
}

enum class LogFilter {
    ALL, BLOCKED, ALLOWED
}
