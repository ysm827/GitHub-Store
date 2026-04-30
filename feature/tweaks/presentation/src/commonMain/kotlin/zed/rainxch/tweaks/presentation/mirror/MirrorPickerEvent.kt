package zed.rainxch.tweaks.presentation.mirror

sealed interface MirrorPickerEvent {
    data class MirrorRemovedNotice(val displayName: String) : MirrorPickerEvent

    data class OpenUrl(val url: String) : MirrorPickerEvent
}
