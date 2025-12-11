package com.example.peerlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class ChatManager(private val coroutineScope: CoroutineScope) {

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val peerDeviceName: String) : ConnectionState()
    }

    private val _messages = MutableSharedFlow<Message>()
    val messages = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    fun startServer() {
        if (_connectionState.value !is ConnectionState.Disconnected) return
        _connectionState.value = ConnectionState.Connecting

        backgroundScope.launch {
            try {
                serverSocket = ServerSocket(8888)
                socket = serverSocket?.accept()
                _connectionState.value = ConnectionState.Connected("Peer")
                receiveMessages()
            } catch (e: IOException) {
                close()
            }
        }
    }

    fun connectToPeer(hostAddress: String) {
        if (_connectionState.value !is ConnectionState.Disconnected) return
        _connectionState.value = ConnectionState.Connecting

        backgroundScope.launch {
            try {
                val clientSocket = Socket()
                clientSocket.connect(InetSocketAddress(hostAddress, 8888), 5000)
                socket = clientSocket
                _connectionState.value = ConnectionState.Connected("Peer")
                receiveMessages()
            } catch (e: IOException) {
                close()
            }
        }
    }

    fun sendMessage(messageText: String) {
        if (_connectionState.value is ConnectionState.Connected) {
            backgroundScope.launch {
                try {
                    socket?.getOutputStream()?.write(messageText.toByteArray())
                    coroutineScope.launch {
                        _messages.emit(Message(messageText, Message.Sender.SELF))
                    }
                } catch (e: IOException) {
                    close()
                }
            }
        }
    }

    private fun receiveMessages() {
        backgroundScope.launch {
            while (socket?.isConnected == true) {
                try {
                    val buffer = ByteArray(1024)
                    val byteCount = socket?.getInputStream()?.read(buffer)
                    if (byteCount == -1) {
                        break
                    }
                    if (byteCount != null && byteCount > 0) {
                        val message = Message(String(buffer, 0, byteCount), Message.Sender.PEER)
                        coroutineScope.launch {
                            _messages.emit(message)
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
            close()
        }
    }

    fun close() {
        if (_connectionState.value is ConnectionState.Disconnected) return
        _connectionState.value = ConnectionState.Disconnected
        try {
            socket?.close()
        } catch (e: IOException) {
        }
        try {
            serverSocket?.close()
        } catch (e: IOException) {
        }
        socket = null
        serverSocket = null
    }
}