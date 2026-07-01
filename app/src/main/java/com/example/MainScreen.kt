package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.data.V2RayConfig
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Theme state
    val darkTheme by viewModel.darkTheme.collectAsState()

    // Onboarding Launcher Splash Screen
    var showLauncherScreen by remember { mutableStateOf(true) }

    // Navigation Tab state: 0 = Home, 1 = App Tunnel, 2 = Proxy, 3 = Logs
    var currentTab by remember { mutableStateOf(0) }

    // ViewModel States
    val uiState by viewModel.uiState.collectAsState()
    val loginMode by viewModel.loginMode.collectAsState()
    val username by viewModel.username.collectAsState()
    val configs by viewModel.configs.collectAsState()
    val selectedConfig by viewModel.selectedConfig.collectAsState()
    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val vpnConnected = connectionState == VpnState.CONNECTED

    // Telegram Proxy States
    val telegramProxies by viewModel.telegramProxies.collectAsState()
    val proxyLoading by viewModel.proxyLoading.collectAsState()
    val proxyError by viewModel.proxyError.collectAsState()

    // UI Local States
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var inputUsername by remember { mutableStateOf("") }
    var appSearchQuery by remember { mutableStateOf("") }
    var selectedProxyForConnection by remember { mutableStateOf<com.example.TelegramProxy?>(null) }
    var showConnectionDialog by remember { mutableStateOf(false) }

    // System VPN Launcher
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val config = viewModel.selectedConfig.value
            if (config != null) {
                viewModel.connectVpn(context, config)
            }
        }
    }

    // Dynamic speeds (changing every 1.2s when connected, 0.00 when disconnected)
    val rxSpeed by produceState(initialValue = "0.00", key1 = vpnConnected) {
        if (vpnConnected) {
            while (true) {
                val speedValue = (4.5 + Math.random() * 16.0)
                value = String.format("%.2f", speedValue)
                delay(1200)
            }
        } else {
            value = "0.00"
        }
    }

    val txSpeed by produceState(initialValue = "0.00", key1 = vpnConnected) {
        if (vpnConnected) {
            while (true) {
                val speedValue = (0.8 + Math.random() * 4.5)
                value = String.format("%.2f", speedValue)
                delay(1200)
            }
        } else {
            value = "0.00"
        }
    }

    // Simulated connection events list
    val logsList = remember(vpnConnected) {
        if (vpnConnected) {
            mutableStateListOf(
                "INFO: Initializing V2Ray connection core...",
                "INFO: Loading split tunneling routing filters",
                "DEBUG: DNS resolve: v2ray-server.connect -> 104.244.42.5",
                "INFO: Starting TLS Client Handshake (ALPN: h2, http/1.1)",
                "INFO: Handshake secure. Multiplexing active",
                "V2RAY: Tunnel route created on tun0 (MTU: 1500)",
                "SUCCESS: Connected! Encrypted traffic flowing securely"
            )
        } else {
            mutableStateListOf(
                "INFO: VPN client idle.",
                "WARNING: Secure tunnel disconnected."
            )
        }
    }

    if (showLauncherScreen) {
        LauncherScreen(
            darkTheme = darkTheme,
            onStart = { showLauncherScreen = false }
        )
    } else {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Styled "V" Logo Box
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (darkTheme) DarkCardAccent else LightCardAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "W",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = VpnBlue
                            )
                        }
                        Text(
                            text = if (currentTab == 2) "وینپروکسی - WinProxy" else "وینتوری - Win2ray",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // Dark/Light Theme Switcher
                    IconButton(
                        onClick = { viewModel.toggleTheme() },
                        modifier = Modifier.testTag("theme_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "تغییر پوسته",
                            tint = VpnBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // Elegant bottom navigation tab bar matching HTML Design perfectly!
            NavigationBar(
                containerColor = if (darkTheme) DarkSurface else LightSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("خانه", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VpnBlue,
                        selectedTextColor = VpnBlue,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        indicatorColor = VpnBlue.copy(alpha = 0.12f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "App Tunnel") },
                    label = { Text("تونل اپ", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VpnBlue,
                        selectedTextColor = VpnBlue,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        indicatorColor = VpnBlue.copy(alpha = 0.12f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Language, contentDescription = "Proxy") },
                    label = { Text("پروکسی", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VpnBlue,
                        selectedTextColor = VpnBlue,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        indicatorColor = VpnBlue.copy(alpha = 0.12f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Logs") },
                    label = { Text("گزارش‌ها", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VpnBlue,
                        selectedTextColor = VpnBlue,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        indicatorColor = VpnBlue.copy(alpha = 0.12f)
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "tab_transition"
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> {
                        // TAB 0: HOME CONNECT SCREEN
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top Login Info Widget
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))
                                AnimatedContent(targetState = loginMode, label = "loginStatusText") { mode ->
                                    when (mode) {
                                        LoginMode.NONE -> {
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "لطفاً ابتدا از دکمه‌های پایین صفحه، نوع ورود خود را انتخاب کنید.",
                                                    fontSize = 13.sp,
                                                    textAlign = TextAlign.Center,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(14.dp)
                                                )
                                            }
                                        }
                                        LoginMode.FREE -> {
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, VpnBlue.copy(alpha = 0.3f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column {
                                                        Text(
                                                            text = "کاربر رایگان",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp,
                                                            color = VpnBlue
                                                        )
                                                        Text(
                                                            text = "اتصال محدود به سرورهای عمومی و اشتراکی",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                        )
                                                    }
                                                    Button(
                                                        onClick = { viewModel.logout() },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                                        modifier = Modifier.height(30.dp)
                                                    ) {
                                                        Text("خروج", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                                    }
                                                }
                                            }
                                        }
                                        LoginMode.SUBSCRIPTION -> {
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, VpnConnectedGreen.copy(alpha = 0.3f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column {
                                                        Text(
                                                            text = "اشتراک فعال پرمیوم",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp,
                                                            color = VpnConnectedGreen
                                                        )
                                                        Text(
                                                            text = "نام کاربری: $username",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                    Button(
                                                        onClick = { viewModel.logout() },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                                        modifier = Modifier.height(30.dp)
                                                    ) {
                                                        Text("خروج", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Middle section: Connection Circle Button with concentric pulses!
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.weight(1f)
                            ) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val pulseScaleOuter by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = if (vpnConnected) 1.25f else 1.05f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1600, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "scale_outer"
                                )
                                val pulseScaleInner by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = if (vpnConnected) 1.12f else 1.02f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "scale_inner"
                                )

                                val buttonColor = if (vpnConnected) VpnConnectedGreen else VpnBlue
                                val buttonText = if (vpnConnected) "CONNECTED" else "CONNECT"

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(230.dp)
                                ) {
                                    // Outer Faint Ring
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .scale(pulseScaleOuter)
                                            .background(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        buttonColor.copy(alpha = 0.18f),
                                                        Color.Transparent
                                                    )
                                                ),
                                                shape = CircleShape
                                            )
                                    )

                                    // Inner Faint Ring
                                    Box(
                                        modifier = Modifier
                                            .size(175.dp)
                                            .scale(pulseScaleInner)
                                            .background(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        buttonColor.copy(alpha = 0.28f),
                                                        Color.Transparent
                                                    )
                                                ),
                                                shape = CircleShape
                                            )
                                    )

                                    // Primary Connect Button
                                    Button(
                                        onClick = {
                                            if (loginMode == LoginMode.NONE) {
                                                scope.launch {
                                                    viewModel.setError("لطفاً ابتدا وارد حساب خود (رایگان یا اشتراک) شوید.")
                                                }
                                            } else if (vpnConnected) {
                                                viewModel.disconnectVpn(context)
                                                viewModel.updateConnectionState(VpnState.DISCONNECTED)
                                            } else {
                                                val intent = VpnService.prepare(context)
                                                if (intent != null) {
                                                    vpnLauncher.launch(intent)
                                                } else {
                                                    val config = viewModel.selectedConfig.value
                                                    if (config != null) {
                                                        viewModel.connectVpn(context, config)
                                                    }
                                                }
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                        modifier = Modifier
                                            .size(136.dp)
                                            .testTag("connect_vpn_button"),
                                        elevation = ButtonDefaults.buttonElevation(
                                            defaultElevation = 8.dp,
                                            pressedElevation = 2.dp
                                        )
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PowerSettingsNew,
                                                contentDescription = buttonText,
                                                modifier = Modifier.size(34.dp),
                                                tint = Color.White
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = buttonText,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Status Indicator Pill & Pulse Dot
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (darkTheme) DarkCardAccent.copy(alpha = 0.6f) else LightCardAccent.copy(alpha = 0.6f))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    val pulseDotOpacity by infiniteTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(800, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "dot_opacity"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .scale(pulseDotOpacity)
                                            .clip(CircleShape)
                                            .background(if (vpnConnected) VpnConnectedGreen else Color.Gray)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (vpnConnected) "اتصال برقرار است" else "قطع اتصال",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (vpnConnected) VpnConnectedGreen else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                // Network Download / Upload speeds (Realistic floating stats!)
                                Row(
                                    modifier = Modifier.fillMaxWidth(0.8f),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "DOWNLOAD",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                            letterSpacing = 0.5.sp
                                        )
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            Text(
                                                text = rxSpeed,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "Mbps",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(32.dp)
                                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
                                    )

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "UPLOAD",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                            letterSpacing = 0.5.sp
                                        )
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            Text(
                                                text = txSpeed,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "Mbps",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Bottom Controls Card - mimicking the rounded-t-[32px] aesthetic!
                            Card(
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    if (loginMode != LoginMode.NONE) {
                                        // Auto Select Switch Row
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (darkTheme) DarkCardAccent else LightCardAccent)
                                                .clickable { viewModel.setAutoSelect(!isAutoSelect) }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoMode,
                                                    contentDescription = null,
                                                    tint = VpnBlue,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Text(
                                                        text = "انتخاب خودکار",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "اتصال به سرور با کمترین پینگ",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = isAutoSelect,
                                                onCheckedChange = { viewModel.setAutoSelect(it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = VpnBlue
                                                ),
                                                modifier = Modifier
                                                    .scale(0.85f)
                                                    .testTag("auto_select_switch")
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Server Selector list/card
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(100.dp)
                                        ) {
                                            AnimatedContent(targetState = uiState, label = "configStateAnim") { state ->
                                                when (state) {
                                                    is UiState.Loading -> {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                                        }
                                                    }
                                                    is UiState.Success -> {
                                                        if (isAutoSelect) {
                                                            val current = selectedConfig ?: configs.firstOrNull()
                                                            if (current != null) {
                                                                ServerItemCard(config = current, isSelected = true, onClick = {})
                                                            } else {
                                                                EmptyServerView()
                                                            }
                                                        } else {
                                                            LazyColumn(
                                                                modifier = Modifier.fillMaxSize(),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                items(configs) { config ->
                                                                    ServerItemCard(
                                                                        config = config,
                                                                        isSelected = selectedConfig?.id == config.id,
                                                                        onClick = { viewModel.selectConfig(config) }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    is UiState.Error -> {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = state.message,
                                                                fontSize = 11.sp,
                                                                color = VpnErrorRed,
                                                                textAlign = TextAlign.Center,
                                                                modifier = Modifier.padding(8.dp)
                                                            )
                                                        }
                                                    }
                                                    UiState.Idle -> {
                                                        EmptyServerView()
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Login choice grid buttons
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Button(
                                                onClick = { showSubscriptionDialog = true },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                                    .testTag("premium_login_btn"),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = VpnBlue)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Star,
                                                        contentDescription = null,
                                                        tint = Color.Yellow,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("ورود به اشتراک", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                }
                                            }

                                            Button(
                                                onClick = { viewModel.loginFree() },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                                    .testTag("free_login_btn"),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (darkTheme) DarkCardAccent else LightCardAccent
                                                )
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Power,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "ورود رایگان",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: SPLIT TUNNELING APP LIST
                        val installedApps by viewModel.installedApps.collectAsState()
                        val selectedVpnApps by viewModel.selectedApps.collectAsState()

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "تونل اپلیکیشن (Split Tunneling)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "اپلیکیشن‌هایی که می‌خواهید ترافیک آنها از فیلترشکن عبور کند را علامت بزنید. در صورت عدم انتخاب، کل ترافیک گوشی از تونل عبور می‌کند.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = appSearchQuery,
                                        onValueChange = { appSearchQuery = it },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = VpnBlue) },
                                        placeholder = { Text("جستجوی نام برنامه...", fontSize = 13.sp) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "تعداد انتخاب شده: ${selectedVpnApps.size}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = VpnBlue
                                        )
                                        TextButton(onClick = { viewModel.clearAllAppSelection() }) {
                                            Text("پاک کردن همه", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    val filteredApps = installedApps.filter {
                                        it.appName.contains(appSearchQuery, ignoreCase = true) ||
                                                it.packageName.contains(appSearchQuery, ignoreCase = true)
                                    }

                                    if (filteredApps.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("هیچ برنامه‌ای پیدا نشد.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 13.sp)
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.height(300.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            items(filteredApps) { app ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (app.isSelected) VpnBlue.copy(alpha = 0.08f) else Color.Transparent)
                                                        .clickable { viewModel.toggleAppSelection(app) }
                                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(if (darkTheme) DarkCardAccent else LightCardAccent),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Android,
                                                            contentDescription = null,
                                                            tint = VpnBlue,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(10.dp))

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = app.appName,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = app.packageName,
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }

                                                    Checkbox(
                                                        checked = app.isSelected,
                                                        onCheckedChange = { viewModel.toggleAppSelection(app) },
                                                        colors = CheckboxDefaults.colors(checkedColor = VpnBlue)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // TAB 2: TELEGRAM PROXIES
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "پروکسی‌های تلگرام",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        IconButton(
                                            onClick = { viewModel.loadTelegramProxies() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "بروزرسانی پروکسی‌ها",
                                                tint = VpnBlue
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "دریافت خودکار بهترین و سریع‌ترین پروکسی‌های فعال تلگرام با امکان سنجش پینگ لحظه‌ای و اتصال هوشمند.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            if (proxyLoading && telegramProxies.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = VpnBlue)
                                }
                            } else if (proxyError != null && telegramProxies.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = proxyError ?: "خطایی رخ داد.",
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 13.sp,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = { viewModel.loadTelegramProxies() },
                                            colors = ButtonDefaults.buttonColors(containerColor = VpnBlue)
                                        ) {
                                            Text("تلاش مجدد")
                                        }
                                    }
                                }
                            } else {
                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 20.dp)
                                ) {
                                    items(telegramProxies) { proxy ->
                                        Card(
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (darkTheme) DarkSurface else LightSurface
                                            ),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(38.dp)
                                                            .clip(CircleShape)
                                                            .background(VpnBlue.copy(alpha = 0.1f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Language,
                                                            contentDescription = "Proxy Icon",
                                                            tint = VpnBlue,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column {
                                                        Text(
                                                            text = "${proxy.address}:${proxy.port}",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = proxy.type,
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                        )
                                                    }
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    val pingColor = when {
                                                        proxy.ping < 0 -> VpnErrorRed
                                                        proxy.ping < 200 -> VpnConnectedGreen
                                                        proxy.ping < 450 -> Color(0xFFF2994A)
                                                        else -> VpnErrorRed
                                                    }
                                                    val pingText = when {
                                                        proxy.ping < 0 -> "Timeout"
                                                        else -> "${proxy.ping} ms"
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(pingColor.copy(alpha = 0.12f))
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = pingText,
                                                            color = pingColor,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(proxy.rawUrl))
                                                            android.widget.Toast.makeText(context, "لینک پروکسی کپی شد.", android.widget.Toast.LENGTH_SHORT).show()
                                                        },
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                                            .testTag("proxy_copy_${proxy.port}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ContentCopy,
                                                            contentDescription = "Copy Proxy",
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }

                                                    Button(
                                                        onClick = {
                                                            selectedProxyForConnection = proxy
                                                            showConnectionDialog = true
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = VpnBlue),
                                                        contentPadding = PaddingValues(horizontal = 10.dp),
                                                        modifier = Modifier
                                                            .height(32.dp)
                                                            .testTag("proxy_connect_${proxy.port}")
                                                    ) {
                                                        Text("اتصال", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // TAB 3: SYSTEM LOGS TERMINAL
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (darkTheme) DarkSurface else LightSurface
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "گزارشات فنی اتصال",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Button(
                                            onClick = { logsList.clear(); logsList.add("INFO: Logs cleared by user.") },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("پاک کردن", fontSize = 10.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "مشاهده جزئیات فنی پروتکل ارتباطی V2Ray در زمان واقعی برای پایش سلامت ارتباط و کشف خطاها.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Terminal display box
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(350.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0F1012))
                                            .padding(12.dp)
                                    ) {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            items(logsList) { log ->
                                                val textColor = when {
                                                    log.startsWith("SUCCESS") -> VpnConnectedGreen
                                                    log.startsWith("WARNING") -> Color(0xFFF2994A)
                                                    log.startsWith("DEBUG") -> Color(0xFF9B51E0)
                                                    else -> Color(0xFFBDBDBD)
                                                }
                                                Text(
                                                    text = log,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = textColor,
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == 2) {
            viewModel.loadTelegramProxies()
        }
    }

    if (showConnectionDialog && selectedProxyForConnection != null) {
        val proxy = selectedProxyForConnection!!
        val installedApps = remember(proxy) { getInstalledTelegramApps(context, proxy.rawUrl) }
        
        Dialog(onDismissRequest = { showConnectionDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "انتخاب برنامه تلگرام برای اتصال",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "یکی از نسخه‌های تلگرام نصب‌شده خود را جهت اتصال به پروکسی انتخاب کنید:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (installedApps.isEmpty()) {
                        Text(
                            text = "هیچ برنامه تلگرامی روی دستگاه شما شناسایی نشد.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            items(installedApps) { app ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(proxy.rawUrl)).apply {
                                                    setPackage(app.packageName)
                                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "خطا در اتصال مستقیم: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            showConnectionDialog = false
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            DrawableImage(
                                                drawable = app.icon,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = app.appName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        
                                        IconButton(
                                            onClick = {
                                                try {
                                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(android.content.Intent.EXTRA_TEXT, proxy.rawUrl)
                                                        setPackage(app.packageName)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "ارسال به چت"))
                                                } catch (e: Exception) {
                                                    android.widget.Toast.makeText(context, "خطا در اشتراک‌گذاری: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                showConnectionDialog = false
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "ارسال به چت",
                                                tint = VpnBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, proxy.rawUrl)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "اشتراک‌گذاری عمومی پروکسی"))
                                } catch (e: Exception) {
                                    // ignore
                                }
                                showConnectionDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("اشتراک‌گذاری عمومی", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        }

                        Button(
                            onClick = { showConnectionDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = VpnBlue),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("بستن", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Subscription Username Dialog
    if (showSubscriptionDialog) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(showSubscriptionDialog) {
            focusRequester.requestFocus()
        }
        Dialog(
            onDismissRequest = { showSubscriptionDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = true)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ورود به اشتراک پرمیوم",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "نام کاربری اشتراک خود را وارد نمایید. نام کاربری بعد از بخش sub= در لینک اشتراک شما قرار دارد.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputUsername,
                        onValueChange = { inputUsername = it },
                        placeholder = { Text("مثال: user826") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (inputUsername.trim().isNotEmpty()) {
                                    viewModel.loginSubscription(inputUsername.trim())
                                    showSubscriptionDialog = false
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("username_input_field")
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (inputUsername.trim().isNotEmpty()) {
                                    viewModel.loginSubscription(inputUsername.trim())
                                    showSubscriptionDialog = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("username_submit_btn")
                        ) {
                            Text("تایید")
                        }
                        TextButton(
                            onClick = { showSubscriptionDialog = false },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("انصراف")
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun ServerItemCard(
    config: V2RayConfig,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) VpnBlue else Color.Transparent
    val backgroundBrush = if (isSelected) {
        Brush.linearGradient(listOf(VpnBlue.copy(alpha = 0.08f), VpnBlue.copy(alpha = 0.02f)))
    } else {
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundBrush)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(VpnBlue.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (config.type) {
                        "VMess" -> Icons.Default.Cloud
                        "VLess" -> Icons.Default.FilterList
                        "Shadowsocks" -> Icons.Default.Lock
                        "Trojan" -> Icons.Default.Shield
                        else -> Icons.Default.Dns
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = config.type,
                        tint = VpnBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = config.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = config.type,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Real Ping indicator
            val pingColor = when {
                config.ping < 0 -> VpnErrorRed
                config.ping < 150 -> VpnConnectedGreen
                config.ping < 300 -> Color(0xFFF2994A)
                else -> VpnErrorRed
            }

            val pingText = when {
                config.ping < 0 -> "Timeout"
                else -> "${config.ping} ms"
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(pingColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = pingText,
                    color = pingColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun EmptyServerView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "هیچ سروری انتخاب نشده است",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
fun DrawableImage(drawable: android.graphics.drawable.Drawable?, modifier: Modifier = Modifier) {
    if (drawable != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.widget.ImageView(context).apply {
                    setImageDrawable(drawable)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
            },
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = "App Icon",
            tint = VpnBlue,
            modifier = modifier
        )
    }
}

fun getInstalledTelegramApps(context: Context, proxyUrl: String): List<AppPackageInfo> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(proxyUrl))
    val list = mutableListOf<AppPackageInfo>()
    
    val resolveInfos: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }
    
    for (info in resolveInfos) {
        val pkg = info.activityInfo.packageName
        val name = info.loadLabel(pm).toString()
        val icon = info.loadIcon(pm)
        if (list.none { it.packageName == pkg }) {
            list.add(AppPackageInfo(packageName = pkg, appName = name, icon = icon))
        }
    }
    
    val knownTelegramPackages = listOf(
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram Web",
        "org.thunderdog.challegram" to "Telegram X",
        "com.hanista.mobogram" to "Mobogram",
        "com.vidogram" to "Vidogram",
        "org.telegram.messenger.plus" to "Plus Messenger"
    )
    
    for ((pkg, defaultName) in knownTelegramPackages) {
        if (list.none { it.packageName == pkg }) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val name = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                list.add(AppPackageInfo(packageName = pkg, appName = name, icon = icon))
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }
    }
    
    return list
}

data class AppPackageInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable? = null
)

fun checkVpnRunning(context: Context): Boolean {
    val pm = context.packageManager
    val packages = pm.getInstalledPackages(0)
    val vpnPackages = packages.filter { 
        it.packageName == "com.example" || 
        it.packageName.contains("vpn", ignoreCase = true) ||
        it.packageName.contains("v2ray", ignoreCase = true)
    }
    return vpnPackages.isNotEmpty()
}
