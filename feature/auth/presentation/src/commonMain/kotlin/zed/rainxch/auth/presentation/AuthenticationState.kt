package zed.rainxch.auth.presentation

import zed.rainxch.auth.presentation.model.AuthLoginState

data class AuthenticationState(
    val loginState: AuthLoginState = AuthLoginState.LoggedOut,
    val copied: Boolean = false,
    val info: String? = null,
    val isPolling: Boolean = false,
    val pollIntervalSec: Int = 0,
    // PAT ("Personal Access Token") paste flow — alternate login path
    // for users on networks where the browser can't reach github.com
    // to complete the device-flow authorize step.
    val isPatSheetVisible: Boolean = false,
    val patInput: String = "",
    val patError: String? = null,
    val isPatSubmitting: Boolean = false,
    // Web OAuth flow is the default in 1.8.3. Device flow + PAT live behind
    // this expandable for users who need them (corp firewalls, GFW etc).
    val isAdvancedAuthVisible: Boolean = false,
    val isWebAuthInFlight: Boolean = false,
)
