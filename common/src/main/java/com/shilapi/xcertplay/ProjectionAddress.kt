package com.shilapi.xcertplay

/** Both phone apps show an IPv4 address for a direct Wi-Fi connection. */
internal fun isProjectionIpv4(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.length in 1..3 && octet.all { it in '0'..'9' } && octet.toInt() <= 255
    }
}
