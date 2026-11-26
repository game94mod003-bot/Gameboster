package ir.abulix.psiphone

import kotlinx.coroutines.flow.MutableStateFlow

object Vpn {
    enum class S { IDLE, CONNECTING, CONNECTED, ERROR }
    val state = MutableStateFlow(S.IDLE)
    val error = MutableStateFlow("")
    val ping = MutableStateFlow(-1L)
    val stable = MutableStateFlow(0)
    val country = MutableStateFlow("")
    val countryCode = MutableStateFlow("")
    val ip = MutableStateFlow("")
    val down = MutableStateFlow(0L)
    val up = MutableStateFlow(0L)
    val hist = MutableStateFlow<List<Long>>(emptyList())
    val totalRx = MutableStateFlow(0L)
    val totalTx = MutableStateFlow(0L)
    fun reset() {
        ping.value = -1; stable.value = 0; country.value = ""; countryCode.value = ""; ip.value = ""
        down.value = 0; up.value = 0; hist.value = emptyList(); totalRx.value = 0; totalTx.value = 0
    }
}
