package com.example.peerlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.peerlink.ui.theme.PeerlinkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private lateinit var chatManager: ChatManager

    var isWifiP2pEnabled by mutableStateOf(false)
    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var connectedPeerName by mutableStateOf<String?>(null)
    private var isDiscovering by mutableStateOf(false)

    @SuppressLint("MissingPermission")
    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers = peerList.deviceList.toList()
        val connectedPeer = peers.find { it.status == WifiP2pDevice.CONNECTED }
        if (connectedPeer != null) {
            connectedPeerName = connectedPeer.deviceName
            isDiscovering = false
        }
    }

    @SuppressLint("MissingPermission")
    val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        if (info.groupFormed) {
            manager.requestPeers(channel, peerListListener)
            if (info.isGroupOwner) {
                chatManager.startServer()
            } else {
                info.groupOwnerAddress?.hostAddress?.let {
                    chatManager.connectToPeer(it)
                }
            }
            isDiscovering = false
        } else {
            connectedPeerName = null
            chatManager.close()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] != true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissions[Manifest.permission.NEARBY_WIFI_DEVICES] != true)
            ) {
                Log.e("MainActivity", "Permissions not granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatManager = ChatManager(lifecycleScope)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)

        registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })

        checkPermissions()

        setContent {
            PeerlinkTheme {
                val connectionState by chatManager.connectionState.collectAsState()
                var messages by remember { mutableStateOf(emptyList<Message>()) }

                LaunchedEffect(Unit) {
                    chatManager.messages.collect { newMessage ->
                        messages = messages + newMessage
                    }
                }

                LaunchedEffect(connectionState) {
                    if (connectionState is ChatManager.ConnectionState.Disconnected) {
                        messages = emptyList()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        peers = peers,
                        messages = messages,
                        connectionState = connectionState,
                        connectedPeerName = connectedPeerName,
                        isDiscovering = isDiscovering,
                        onDiscover = { discoverPeers() },
                        onConnect = { device -> connect(device) },
                        onSendMessage = { message -> chatManager.sendMessage(message) },
                        onDisconnect = { disconnect() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        disconnect()
    }

    private fun checkPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(requiredPermissions)
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        if (!isWifiP2pEnabled || chatManager.connectionState.value !is ChatManager.ConnectionState.Disconnected) return
        isDiscovering = true
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "Peer discovery started.")
            }

            override fun onFailure(reasonCode: Int) {
                Log.d("MainActivity", "Peer discovery failed: $reasonCode")
                isDiscovering = false
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "Connection to ${device.deviceName} initiated.")
            }

            override fun onFailure(reason: Int) {
                Log.d("MainActivity", "Connection failed: $reason")
            }
        })
    }

    private fun disconnect() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "Group removed successfully.")
            }

            override fun onFailure(reason: Int) {
                Log.d("MainActivity", "Group removal failed: $reason")
            }
        })
        chatManager.close()
        connectedPeerName = null
        isDiscovering = false
    }
}

@Composable
fun MainScreen(
    peers: List<WifiP2pDevice>,
    messages: List<Message>,
    connectionState: ChatManager.ConnectionState,
    connectedPeerName: String?,
    isDiscovering: Boolean,
    onDiscover: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onDiscover, enabled = connectionState is ChatManager.ConnectionState.Disconnected && !isDiscovering) {
                Text("Discover Peers")
            }
            Spacer(Modifier.width(8.dp))
            if (connectionState is ChatManager.ConnectionState.Connected) {
                Button(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }

        ConnectionStatus(connectionState, connectedPeerName)

        if (connectionState is ChatManager.ConnectionState.Disconnected) {
            if (isDiscovering) {
                RadarScan()
            }
            Text("Peers:", style = MaterialTheme.typography.headlineSmall)
            LazyColumn(modifier = Modifier.height(150.dp).fillMaxWidth()) {
                items(peers) { device ->
                    Text(
                        text = device.deviceName,
                        modifier = Modifier
                            .clickable { onConnect(device) }
                            .padding(8.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Chat:", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(messages.reversed()) { message ->
                val alignment = if (message.sender == Message.Sender.SELF) Alignment.CenterEnd else Alignment.CenterStart
                val backgroundColor = if (message.sender == Message.Sender.SELF) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentAlignment = alignment
                ) {
                    Box(
                        modifier = Modifier
                            .background(backgroundColor, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = message.text, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message") },
                enabled = connectionState is ChatManager.ConnectionState.Connected
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSendMessage(text)
                        text = ""
                    }
                },
                enabled = text.isNotBlank() && connectionState is ChatManager.ConnectionState.Connected
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun ConnectionStatus(state: ChatManager.ConnectionState, connectedPeerName: String?) {
    val (text, color) = when (state) {
        is ChatManager.ConnectionState.Disconnected -> "Disconnected" to Color.Red
        is ChatManager.ConnectionState.Connecting -> "Connecting..." to Color.Yellow
        is ChatManager.ConnectionState.Connected -> {
            val peerText = connectedPeerName ?: "Peer"
            "Connected to $peerText" to Color.Green
        }
    }
    Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun RadarScan() {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarAnimation"
    )
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = size.minDimension / 2
            drawCircle(Color.Gray, radius = radius, style = Stroke(width = 2.dp.toPx()))
            drawCircle(Color.Gray, radius = radius * 0.66f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(Color.Gray, radius = radius * 0.33f, style = Stroke(width = 2.dp.toPx()))

            rotate(rotation) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.9f to primaryColor,
                        1f to Color.Transparent
                    ),
                    startAngle = 0f,
                    sweepAngle = 120f,
                    useCenter = true,
                    alpha = 0.6f
                )
            }
        }
    }
}
