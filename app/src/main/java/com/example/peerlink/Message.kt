package com.example.peerlink

data class Message(val text: String, val sender: Sender) {
    enum class Sender {
        SELF,
        PEER
    }
}