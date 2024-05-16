package com.toasterofbread.spmp.platform.playerservice

import com.toasterofbread.spmp.resources.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.zeromq.ZMQ.Socket
import org.zeromq.ZMsg
import spms.socketapi.shared.SpMsClientHandshake
import spms.socketapi.shared.SpMsSocketApi
import spms.socketapi.shared.SpMsServerHandshake
import java.util.concurrent.TimeoutException

internal suspend fun Socket.tryConnectToServer(
    server_url: String,
    handshake: SpMsClientHandshake,
    json: Json,
    timeout: Long? = null,
    shouldCancelConnection: () -> Boolean = { false },
    log: (String) -> Unit = { println(it) },
    setLoadState: ((PlayerServiceLoadState) -> Unit)? = null
): SpMsServerHandshake? = withContext(Dispatchers.IO) {
    check(connect(server_url))

    val handshake_message: ZMsg = ZMsg()
    handshake_message.add(json.encodeToString(handshake))
    check(handshake_message.send(this@tryConnectToServer))

    if (setLoadState != null) {
        setLoadState(
            PlayerServiceLoadState(
                true,
                getString("loading_splash_connecting_to_server_at_\$x").replace("\$x", server_url.split("://", limit = 2).last())
            )
        )
    }

    log("Waiting for reply from server at $server_url...")

    var reply: ZMsg?
    do {
        reply = recvMsg(timeout ?: 500)

        if (timeout != null && reply == null) {
            throw TimeoutException()
        }

        if (shouldCancelConnection()) {
            return@withContext null
        }
    }
    while (reply == null)

    val joined_reply: List<String> = SpMsSocketApi.decode(reply.map { it.data.decodeToString() })
    val server_handshake_data: String = joined_reply.first()

    log("Received reply handshake from server with the following content:\n$server_handshake_data")

    val server_handshake: SpMsServerHandshake
    try {
        server_handshake = json.decodeFromString(server_handshake_data)
    }
    catch (e: Throwable) {
        throw RuntimeException("Parsing reply handshake failed. You might be using an outdated server verison. $server_handshake_data", e)
    }

    return@withContext server_handshake
}

private fun Socket.recvMsg(timeout_ms: Long?): ZMsg? {
    receiveTimeOut = timeout_ms?.toInt() ?: -1
    val msg: ZMsg? = ZMsg.recvMsg(this)
    receiveTimeOut = -1
    return msg
}
