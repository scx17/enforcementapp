package com.hdcollection.enforcement.gb28181

import java.security.MessageDigest

object SipMessage {

    fun buildRegister(
        deviceId: String,
        sipServer: String,
        sipPort: String,
        localIp: String,
        localPort: Int,
        callId: String,
        cseq: Int,
        expires: Int = 3600
    ): String {
        return """REGISTER sip:$sipServer:$sipPort SIP/2.0
Via: SIP/2.0/UDP $localIp:$localPort;rport;branch=z9hG4bK${callId.take(8)}
From: <sip:$deviceId@$sipServer>;tag=${callId.take(8)}
To: <sip:$deviceId@$sipServer>
Call-ID: $callId
CSeq: $cseq REGISTER
Contact: <sip:$deviceId@$localIp:$localPort>
Max-Forwards: 70
User-Agent: EnforcementApp/1.0
Expires: $expires
Content-Length: 0

""".replace("\n", "\r\n")
    }

    fun buildRegisterWithAuth(
        deviceId: String,
        sipServer: String,
        sipPort: String,
        localIp: String,
        localPort: Int,
        callId: String,
        cseq: Int,
        username: String,
        password: String,
        realm: String,
        nonce: String,
        expires: Int = 3600
    ): String {
        val uri = "sip:$sipServer:$sipPort"
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("REGISTER:$uri")
        val response = md5("$ha1:$nonce:$ha2")

        return """REGISTER sip:$sipServer:$sipPort SIP/2.0
Via: SIP/2.0/UDP $localIp:$localPort;rport;branch=z9hG4bK${callId.take(8)}aa
From: <sip:$deviceId@$sipServer>;tag=${callId.take(8)}
To: <sip:$deviceId@$sipServer>
Call-ID: $callId
CSeq: $cseq REGISTER
Contact: <sip:$deviceId@$localIp:$localPort>
Authorization: Digest username="$username", realm="$realm", nonce="$nonce", uri="$uri", algorithm=MD5, response="$response"
Max-Forwards: 70
User-Agent: EnforcementApp/1.0
Expires: $expires
Content-Length: 0

""".replace("\n", "\r\n")
    }

    fun buildInviteOk(
        callId: String,
        fromHeader: String,
        toHeader: String,
        cseq: String,
        viaHeader: String,
        localIp: String,
        rtpPort: Int,
        ssrc: String
    ): String {
        val sdp = buildSdp(localIp, rtpPort, ssrc)
        return """SIP/2.0 200 OK
Via: $viaHeader
From: $fromHeader
To: $toHeader;tag=enforceapp
Call-ID: $callId
CSeq: $cseq
Contact: <sip:$localIp:5060>
Content-Type: application/sdp
Content-Length: ${sdp.length}

$sdp""".replace("\n", "\r\n")
    }

    fun buildMessageOk(
        callId: String,
        fromHeader: String,
        toHeader: String,
        cseq: String,
        viaHeader: String
    ): String {
        return """SIP/2.0 200 OK
Via: $viaHeader
From: $fromHeader
To: $toHeader;tag=enforceapp
Call-ID: $callId
CSeq: $cseq
Content-Length: 0

""".replace("\n", "\r\n")
    }

    fun buildBye(callId: String): String {
        return """SIP/2.0 200 OK
Call-ID: $callId
Content-Length: 0

""".replace("\n", "\r\n")
    }

    private fun buildSdp(localIp: String, rtpPort: Int, ssrc: String): String {
        return """v=0
o=- 0 0 IN IP4 $localIp
s=Play
c=IN IP4 $localIp
t=0 0
m=video $rtpPort RTP/AVP 96
a=rtpmap:96 PS/90000
a=sendonly
y=$ssrc
""".replace("\n", "\r\n")
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractVia(headers: String): String {
        return headers.lines().firstOrNull { it.startsWith("Via:") }?.removePrefix("Via: ") ?: ""
    }

    fun extractHeader(message: String, header: String): String? {
        return message.lines().firstOrNull { it.startsWith("$header:") }
            ?.removePrefix("$header:")?.trim()
    }

    fun extractNonce(message: String): String? {
        return Regex("""nonce="([^"]+)"""").find(message)?.groupValues?.get(1)
    }

    fun extractRealm(message: String): String? {
        return Regex("""realm="([^"]+)"""").find(message)?.groupValues?.get(1)
    }
}
