package zed.rainxch.tweaks.presentation.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zed.rainxch.core.domain.network.SlowDownloadDetector
import zed.rainxch.core.domain.repository.MirrorRepository

class AutoSuggestMirrorViewModel(
    private val detector: SlowDownloadDetector,
    private val mirrorRepository: MirrorRepository,
) : ViewModel() {
    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    init {
        viewModelScope.launch {
            detector.suggestMirror.collect {
                _isVisible.value = true
            }
        }
    }

    fun onMaybeLater() {
        _isVisible.value = false
        viewModelScope.launch {
            mirrorRepository.snoozeAutoSuggest(24L * 60 * 60 * 1000)
        }
    }

    fun onDontAskAgain() {
        _isVisible.value = false
        viewModelScope.launch {
            mirrorRepository.dismissAutoSuggestPermanently()
        }
    }

    fun onPickOneClicked() {
        _isVisible.value = false
    }

    fun dismiss() {
        _isVisible.value = false
    }
}
