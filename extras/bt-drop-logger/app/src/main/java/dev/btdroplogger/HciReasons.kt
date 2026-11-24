package dev.btdroplogger

/** Bluetooth Core Spec HCI error codes, as seen in disconnect "reason" fields. */
object HciReasons {
    private val names = mapOf(
        0x05 to "Authentication failure",
        0x06 to "PIN or key missing",
        0x08 to "CONNECTION TIMEOUT (link supervision timeout: radio link lost - range, interference, body blocking)",
        0x0E to "Connection rejected: unacceptable address",
        0x13 to "REMOTE USER TERMINATED (the AirPod chose to disconnect: case, ear, switching to another device, or firmware)",
        0x14 to "Remote device terminated: low resources",
        0x15 to "Remote device terminated: power off",
        0x16 to "CONNECTION TERMINATED BY LOCAL HOST (the phone chose to disconnect: an app, the Bluetooth stack, or the system)",
        0x1A to "Unsupported remote feature",
        0x1F to "Unspecified error",
        0x22 to "LMP/LL response timeout (the AirPod stopped answering link-layer requests)",
        0x23 to "LMP error transaction collision",
        0x28 to "Instant passed",
        0x2A to "Different transaction collision",
        0x3B to "Unacceptable connection parameters",
        0x3D to "Connection terminated due to MIC failure (encryption problem)",
        0x3E to "Connection failed to be established",
    )

    private val pattern = Regex("""(?i)reason[^0-9a-fx]{0,40}?(?:\(|\b)(0x[0-9a-f]{1,2})\b""")

    /** If the line contains an HCI reason code, return a human description of it. */
    fun describe(line: String): String? {
        val m = pattern.find(line) ?: return null
        val code = m.groupValues[1].removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
        val name = names[code] ?: "HCI error"
        return "reason 0x%02X = %s".format(code, name)
    }
}
