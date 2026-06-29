package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ShieldActive
import com.example.ui.theme.ShieldInactive
import com.example.ui.theme.CoralRed
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import com.example.ui.theme.CardSurface
import com.example.ui.theme.DeepSlateBackground
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: AdBlockViewModel by viewModels()

    // Activity launcher for system VPN permission prompt
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startAdBlockVpn()
        } else {
            Toast.makeText(this, "VPN permission is required to block ads", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init database / shared prefs
        AdBlockManager.init(this)

        // Observe events to start or stop VPN service
        lifecycleScope.launch {
            viewModel.eventFlow.collectLatest { event ->
                when (event) {
                    is VpnEvent.RequestStart -> {
                        val vpnIntent = VpnService.prepare(this@MainActivity)
                        if (vpnIntent != null) {
                            vpnPrepareLauncher.launch(vpnIntent)
                        } else {
                            startAdBlockVpn()
                        }
                    }
                    is VpnEvent.RequestStop -> {
                        stopAdBlockVpn()
                    }
                }
            }
        }

        // Request notifications permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    AdBlockAppContent(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun startAdBlockVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAdBlockVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
fun AdBlockAppContent(
    viewModel: AdBlockViewModel,
    modifier: Modifier = Modifier
) {
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val totalBlocked by viewModel.totalBlocked.collectAsStateWithLifecycle()
    val totalAllowed by viewModel.totalAllowed.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentFilter by viewModel.logFilter.collectAsStateWithLifecycle()
    
    val dnsProfile by viewModel.dnsProfile.collectAsStateWithLifecycle()
    val customDnsIp by viewModel.customDnsIp.collectAsStateWithLifecycle()
    val customBlocklist by viewModel.customBlocklist.collectAsStateWithLifecycle()
    val customAllowlist by viewModel.customAllowlist.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(AppTab.DASHBOARD) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App Header / Hero Banner
        AppHeader(
            isRunning = isRunning,
            onSettingsClick = { activeTab = AppTab.SETTINGS }
        )

        // Main view content based on active tab
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (activeTab) {
                AppTab.DASHBOARD -> {
                    DashboardScreen(
                        isRunning = isRunning,
                        totalBlocked = totalBlocked,
                        totalAllowed = totalAllowed,
                        duration = duration,
                        dnsProfile = dnsProfile,
                        customDnsIp = customDnsIp,
                        onToggle = { viewModel.toggleVpn() },
                        onReset = { viewModel.resetStats() },
                        onManageRulesClick = { activeTab = AppTab.CUSTOM_LISTS }
                    )
                }
                AppTab.LOGS -> {
                    LogsScreen(
                        logs = logs,
                        searchQuery = searchQuery,
                        currentFilter = currentFilter,
                        onSearchChange = { viewModel.updateSearchQuery(it) },
                        onFilterChange = { viewModel.updateLogFilter(it) },
                        onClearLogs = { viewModel.clearLogs() },
                        onAddToBlock = { viewModel.addCustomBlock(it) },
                        onAddToAllow = { viewModel.addCustomAllow(it) }
                    )
                }
                AppTab.CUSTOM_LISTS -> {
                    CustomListsScreen(
                        customBlocklist = customBlocklist,
                        customAllowlist = customAllowlist,
                        onAddBlock = { viewModel.addCustomBlock(it) },
                        onRemoveBlock = { viewModel.removeCustomBlock(it) },
                        onAddAllow = { viewModel.addCustomAllow(it) },
                        onRemoveAllow = { viewModel.removeCustomAllow(it) }
                    )
                }
                AppTab.SETTINGS -> {
                    SettingsScreen(
                        dnsProfile = dnsProfile,
                        customDnsIp = customDnsIp,
                        onSaveProfile = { profile, customIp ->
                            viewModel.changeDnsProfile(profile, customIp)
                        }
                    )
                }
            }
        }

        // Modern Navigation Bar
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            NavigationBarItem(
                selected = activeTab == AppTab.DASHBOARD,
                onClick = { activeTab = AppTab.DASHBOARD },
                icon = { Icon(Icons.Default.Shield, contentDescription = "Dashboard") },
                label = { Text("Shield") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF062E6F),
                    selectedTextColor = ShieldActive,
                    indicatorColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ),
                modifier = Modifier.testTag("nav_dashboard")
            )
            NavigationBarItem(
                selected = activeTab == AppTab.LOGS,
                onClick = { activeTab = AppTab.LOGS },
                icon = { Icon(Icons.Default.List, contentDescription = "Logs") },
                label = { Text("Queries") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF062E6F),
                    selectedTextColor = ShieldActive,
                    indicatorColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ),
                modifier = Modifier.testTag("nav_logs")
            )
            NavigationBarItem(
                selected = activeTab == AppTab.CUSTOM_LISTS,
                onClick = { activeTab = AppTab.CUSTOM_LISTS },
                icon = { Icon(Icons.Default.FilterAlt, contentDescription = "Rules") },
                label = { Text("Rules") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF062E6F),
                    selectedTextColor = ShieldActive,
                    indicatorColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ),
                modifier = Modifier.testTag("nav_rules")
            )
            NavigationBarItem(
                selected = activeTab == AppTab.SETTINGS,
                onClick = { activeTab = AppTab.SETTINGS },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF062E6F),
                    selectedTextColor = ShieldActive,
                    indicatorColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ),
                modifier = Modifier.testTag("nav_settings")
            )
        }
    }
}

enum class AppTab {
    DASHBOARD, LOGS, CUSTOM_LISTS, SETTINGS
}

@Composable
fun AppHeader(
    isRunning: Boolean,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Elegant Shield Logo Container
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ShieldActive, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Shield Logo",
                    tint = Color(0xFF062E6F),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "AdShield Pro",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextWhite,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = if (isRunning) "System Protected" else "Protection Paused",
                    fontSize = 12.sp,
                    color = if (isRunning) ShieldActive else TextMuted
                )
            }
        }
        
        // Settings / Accent button
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(48.dp)
                .background(CardSurface, CircleShape)
                .testTag("header_settings_button")
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = TextWhite.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun DashboardScreen(
    isRunning: Boolean,
    totalBlocked: Int,
    totalAllowed: Int,
    duration: String,
    dnsProfile: String,
    customDnsIp: String,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onManageRulesClick: () -> Unit
) {
    val totalQueries = totalBlocked + totalAllowed
    val blockRate = if (totalQueries > 0) (totalBlocked.toFloat() / totalQueries.toFloat() * 100).toInt() else 0

    val sweepAngleAnimated by animateFloatAsState(
        targetValue = if (isRunning) 360f else 0f,
        animationSpec = tween(2000)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        // Shield Trigger Circle
        item {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .testTag("shield_toggle_button_outer"),
                contentAlignment = Alignment.Center
            ) {
                // Pulsing Effect (represented by a soft glowing larger circle)
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .background(
                            color = if (isRunning) ShieldActive.copy(alpha = 0.08f) else Color.Transparent,
                            shape = CircleShape
                        )
                )
                
                // Main toggle button
                Surface(
                    shape = CircleShape,
                    color = CardSurface,
                    border = BorderStroke(4.dp, if (isRunning) ShieldActive else ShieldInactive),
                    modifier = Modifier
                        .size(192.dp)
                        .clickable { onToggle() }
                        .testTag("shield_toggle_button")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield Indicator",
                            tint = if (isRunning) ShieldActive else TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isRunning) "ACTIVE" else "PAUSED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) ShieldActive else TextMuted,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // System Protected / Status Text
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = if (isRunning) "System Protected" else "Defense Paused",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRunning) "Blocking ads across 42 applications" else "DNS & Host Layer Filtering is disabled",
                    fontSize = 14.sp,
                    color = TextMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // Grid of statistics
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "ADS BLOCKED",
                    value = totalBlocked.toString(),
                    icon = Icons.Default.Block,
                    tint = CoralRed,
                    subtext = "$blockRate% blocking rate"
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "QUERIES RESOLVED",
                    value = totalQueries.toString(),
                    icon = Icons.Default.CompareArrows,
                    tint = AccentCyan,
                    subtext = "Allowed: $totalAllowed"
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }

        // Active App List Preview Card (Manage App Rules shortcut)
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onManageRulesClick() }
                    .testTag("manage_rules_shortcut_card"),
                shape = RoundedCornerShape(28.dp),
                color = ShieldInactive, // background #2D2F31
                border = BorderStroke(1.dp, CardSurface) // subtle framing
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        // App List icon container
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF44474E), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterAlt,
                                contentDescription = "Manage App Rules",
                                tint = TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Manage App Rules",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite
                            )
                            Text(
                                text = "DNS & Host Layer Filtering",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                    Text(
                        text = "›",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light,
                        color = TextWhite.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }

        // Configuration Summary Card
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = "DNS Server",
                                tint = ShieldActive,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ACTIVE DNS PROFILE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        Text(
                            text = "RESET STATS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CoralRed,
                            modifier = Modifier
                                .clickable { onReset() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val profileName = when (dnsProfile) {
                        "cloudflare" -> "Cloudflare Family DNS"
                        "google" -> "Google DNS"
                        "custom" -> "Custom DNS Profile"
                        else -> "AdGuard Filtering DNS"
                    }
                    
                    val dnsIp = when (dnsProfile) {
                        "cloudflare" -> "1.1.1.3"
                        "google" -> "8.8.8.8"
                        "custom" -> customDnsIp
                        else -> "94.140.14.14"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = profileName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = "Upstream server: $dnsIp",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .background(ShieldActive.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "PORT 53",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ShieldActive,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    subtext: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, ShieldInactive) // Sleek border color (#2D2F31)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = ShieldActive, // Gorgeous brand accent (#A8C7FA)
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtext,
                fontSize = 12.sp,
                color = TextMuted
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(
    logs: List<DnsLog>,
    searchQuery: String,
    currentFilter: LogFilter,
    onSearchChange: (String) -> Unit,
    onFilterChange: (LogFilter) -> Unit,
    onClearLogs: () -> Unit,
    onAddToBlock: (String) -> Unit,
    onAddToAllow: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Search Domain Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("logs_search_input"),
            placeholder = { Text("Search resolution logs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ShieldActive,
                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Query Logs Filters row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilterChip(
                    label = "All",
                    selected = currentFilter == LogFilter.ALL,
                    onClick = { onFilterChange(LogFilter.ALL) }
                )
                LogFilterChip(
                    label = "Blocked",
                    selected = currentFilter == LogFilter.BLOCKED,
                    onClick = { onFilterChange(LogFilter.BLOCKED) },
                    activeColor = CoralRed
                )
                LogFilterChip(
                    label = "Allowed",
                    selected = currentFilter == LogFilter.ALLOWED,
                    onClick = { onFilterChange(LogFilter.ALLOWED) },
                    activeColor = ShieldActive
                )
            }

            TextButton(
                onClick = onClearLogs,
                colors = ButtonDefaults.textButtonColors(contentColor = TextMuted)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear logs", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Query List View
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Empty Log",
                        tint = TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No DNS resolutions registered",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                    Text(
                        text = "Toggle the Shield on or filter different queries",
                        fontSize = 12.sp,
                        color = TextMuted.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("logs_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogItemRow(
                        log = log,
                        onAddToBlock = onAddToBlock,
                        onAddToAllow = onAddToAllow,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
fun LogFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    activeColor: Color = AccentCyan
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) activeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) activeColor else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) activeColor else TextMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun LogItemRow(
    log: DnsLog,
    onAddToBlock: (String) -> Unit,
    onAddToAllow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val format = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (log.isBlocked) CoralRed else ShieldActive, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = log.domain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Text(
                    text = format.format(Date(log.timestamp)),
                    fontSize = 11.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
                Spacer(modifier = Modifier.height(10.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RESOLUTION PATH",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (log.isBlocked) "Blocked by Shield Filter" else "Resolved by ${log.upstream}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (log.isBlocked) CoralRed else ShieldActive
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!log.isBlocked) {
                            Button(
                                onClick = { onAddToBlock(log.domain) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CoralRed.copy(alpha = 0.15f), contentColor = CoralRed),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Block", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { onAddToAllow(log.domain) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ShieldActive.copy(alpha = 0.15f), contentColor = ShieldActive),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Allow", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomListsScreen(
    customBlocklist: Set<String>,
    customAllowlist: Set<String>,
    onAddBlock: (String) -> Unit,
    onRemoveBlock: (String) -> Unit,
    onAddAllow: (String) -> Unit,
    onRemoveAllow: (String) -> Unit
) {
    var filterTab by remember { mutableStateOf(0) } // 0 = Blocklist, 1 = Allowlist
    var inputDomain by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Simple Tab Switcher
        TabRow(
            selectedTabIndex = filterTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = ShieldActive,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[filterTab]),
                    color = ShieldActive
                )
            }
        ) {
            Tab(
                selected = filterTab == 0,
                onClick = { filterTab = 0 },
                text = { Text("Custom Blocklist", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            )
            Tab(
                selected = filterTab == 1,
                onClick = { filterTab = 1 },
                text = { Text("Custom Allowlist", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Input and Add Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputDomain,
                onValueChange = { inputDomain = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("rule_domain_input"),
                placeholder = { Text("Add domain e.g. customads.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (inputDomain.isNotEmpty()) {
                            if (filterTab == 0) onAddBlock(inputDomain) else onAddAllow(inputDomain)
                            inputDomain = ""
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ShieldActive,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            IconButton(
                onClick = {
                    if (inputDomain.isNotEmpty()) {
                        if (filterTab == 0) onAddBlock(inputDomain) else onAddAllow(inputDomain)
                        inputDomain = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(ShieldActive, RoundedCornerShape(12.dp))
                    .testTag("rule_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule", tint = DeepSlateBackground)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Rules List
        val currentList = if (filterTab == 0) customBlocklist.toList() else customAllowlist.toList()

        if (currentList.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (filterTab == 0) Icons.Outlined.GppBad else Icons.Outlined.GppGood,
                        contentDescription = "Empty Rule",
                        tint = TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (filterTab == 0) "No Custom Block Rules Added" else "No Custom Allow Rules Added",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "Add domains above to customize your shield behavior",
                        fontSize = 12.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("rules_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(currentList, key = { it }) { domain ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)),
                        modifier = Modifier.animateItem()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (filterTab == 0) CoralRed else ShieldActive, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = domain,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite
                                )
                            }
                            
                            IconButton(
                                onClick = { if (filterTab == 0) onRemoveBlock(domain) else onRemoveAllow(domain) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove rule",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    dnsProfile: String,
    customDnsIp: String,
    onSaveProfile: (String, String) -> Unit
) {
    var selectedProfile by remember { mutableStateOf(dnsProfile) }
    var inputCustomIp by remember { mutableStateOf(customDnsIp) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "UPSTREAM DNS RESOLVER",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        // Profile: AdGuard (Recommended)
        item {
            DnsProfileCard(
                title = "AdGuard Filtering DNS (Recommended)",
                description = "Aggressive system-wide ad, tracking, and malware blocking. Safely clean apps on downstream channels.",
                ipText = "Primary Server: 94.140.14.14",
                selected = selectedProfile == "adguard",
                onClick = {
                    selectedProfile = "adguard"
                    onSaveProfile("adguard", inputCustomIp)
                }
            )
        }

        // Profile: Cloudflare Family
        item {
            DnsProfileCard(
                title = "Cloudflare Family DNS",
                description = "Fast, privacy-respecting DNS query protection. Blocks known malware and malicious tracker systems.",
                ipText = "Primary Server: 1.1.1.3",
                selected = selectedProfile == "cloudflare",
                onClick = {
                    selectedProfile = "cloudflare"
                    onSaveProfile("cloudflare", inputCustomIp)
                }
            )
        }

        // Profile: Google DNS
        item {
            DnsProfileCard(
                title = "Google standard DNS",
                description = "No upstream DNS filtering. Ad blocking is fully managed locally by your custom blocklist rules.",
                ipText = "Primary Server: 8.8.8.8",
                selected = selectedProfile == "google",
                onClick = {
                    selectedProfile = "google"
                    onSaveProfile("google", inputCustomIp)
                }
            )
        }

        // Profile: Custom
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (selectedProfile == "custom") ShieldActive.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (selectedProfile == "custom") 2.dp else 1.dp,
                    color = if (selectedProfile == "custom") ShieldActive else ShieldInactive
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedProfile = "custom" }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Custom Primary Resolver",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Enter any custom IPv4 DNS resolver IP to route queries.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                        RadioButton(
                            selected = selectedProfile == "custom",
                            onClick = { selectedProfile = "custom" },
                            colors = RadioButtonDefaults.colors(selectedColor = ShieldActive)
                        )
                    }

                    if (selectedProfile == "custom") {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = inputCustomIp,
                            onValueChange = {
                                inputCustomIp = it
                                onSaveProfile("custom", it)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_custom_dns_input"),
                            placeholder = { Text("e.g. 1.1.1.1") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ShieldActive,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        // About / Tech specs
        item {
            Text(
                text = "ABOUT THE SHIELD",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, ShieldInactive),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How does this app block ads?",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This app establishes a secure local VPN connection to intercept Port 53 DNS queries. Known advertising, pop-up, tracker, and telemetric domains are immediately blocked and returned as 0.0.0.0. Genuine requests are quickly forwarded to secure upstream DNS resolvers.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "It operates 100% locally and does not collect or transmit any of your personal DNS resolution logs.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ShieldActive
                    )
                }
            }
        }
    }
}

@Composable
fun DnsProfileCard(
    title: String,
    description: String,
    ipText: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (selected) ShieldActive.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) ShieldActive else ShieldInactive
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = ipText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ShieldActive,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = ShieldActive)
            )
        }
    }
}
