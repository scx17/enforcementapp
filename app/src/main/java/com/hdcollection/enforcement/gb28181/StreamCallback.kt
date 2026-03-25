package com.hdcollection.enforcement.gb28181

interface StreamCallback {
    fun onRegistered(deviceId: String)
    fun onRegistrationFailed(reason: String)
    fun onStreamStartRequested(channelId: String, rtpIp: String, rtpPort: Int)
    fun onStreamStopRequested(channelId: String)
    fun onIntercomReceived(callerInfo: String)
}
