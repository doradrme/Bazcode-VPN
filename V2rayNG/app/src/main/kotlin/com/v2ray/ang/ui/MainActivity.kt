package com.v2ray.ang.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.bazshare.ShareService
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import me.drakeet.support.toast.ToastCompat
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMainBinding

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        ShareService.ensureRunning(this)
        binding.btnConnect.setOnClickListener { toggleBazConnection() }
        binding.btnPingAll.setOnClickListener {
            binding.btnPingAll.setBackgroundResource(R.drawable.baz_btn_busy)
            mainViewModel.testAllTcping()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                binding.btnPingAll.setBackgroundResource(R.drawable.baz_btn_ping)
            }, 2500)
        }
        binding.btnFastest.setOnClickListener {
            binding.btnFastest.isEnabled = false
            binding.btnFastest.text = getString(R.string.baz_testing_fastest)
            binding.pbFastest.visibility = View.VISIBLE
            bazConnectFastest()
        }
        binding.fab.setOnClickListener { toggleBazConnection() }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                binding.tvTestState.text = getString(R.string.connection_test_testing)
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)


        val toggle = ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        binding.version.text = "v${BuildConfig.VERSION_NAME} (${Libv2ray.checkVersionX()})"

        setupViewModelObserver()
        migrateLegacy()
    }

    private fun setupViewModelObserver() {
        mainViewModel.updateListAction.observe(this) {
            val index = it ?: return@observe
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { binding.tvTestState.text = it }
        mainViewModel.isRunning.observe(this) {
            val isRunning = it ?: return@observe
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_v)
                binding.btnConnect.text = getString(R.string.baz_disconnect)
                binding.btnConnect.setBackgroundResource(R.drawable.baz_btn_active)
                binding.tvTestState.text = getString(R.string.connection_connected)
                binding.layoutTest.isFocusable = true
            } else {
                binding.fab.setImageResource(R.drawable.ic_v_idle)
                binding.btnConnect.text = getString(R.string.baz_connect)
                binding.btnConnect.setBackgroundResource(R.drawable.baz_btn_idle)
                binding.tvTestState.text = getString(R.string.connection_not_connected)
                binding.layoutTest.isFocusable = false
            }
            binding.pbConnect.visibility = View.GONE
            binding.btnConnect.isEnabled = true
            hideCircle()
        }
        mainViewModel.startListenBroadcast()
    }

    private fun migrateLegacy() {
        GlobalScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
            if (result != null) {
                launch(Dispatchers.Main) {
                    if (result) {
                        toast(getString(R.string.migration_success))
                        mainViewModel.reloadServerList()
                    } else {
                        toast(getString(R.string.migration_fail))
                    }
                }
            }
        }
    }

    fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            binding.pbConnect.visibility = View.GONE
            binding.btnConnect.isEnabled = true
            return
        }
        showCircle()
        V2RayServiceManager.startV2Ray(this)
        hideCircle()
    }

    private fun toggleBazConnection() {
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this)
        } else {
            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = getString(R.string.toast_services_start)
            binding.pbConnect.visibility = View.VISIBLE
            if (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN" == "VPN") {
                val intent = VpnService.prepare(this)
                if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
            } else {
                startV2Ray()
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode(true)
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_manually_vmess -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.VMESS.value).
            setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_manually_vless -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.VLESS.value).setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_manually_trojan -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.TROJAN.value).setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_manually_ss -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.SHADOWSOCKS.value).
            setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_manually_socks -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.SOCKS.value).
            setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }
        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }
        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }
        R.id.import_config_custom_url_scan -> {
            importQRcode(false)
            true
        }

//        R.id.sub_setting -> {
//            startActivity<SubSettingActivity>()
//            true
//        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.baz_free_config -> { bazDownloadFreeConfig(); true }
        R.id.restart_service -> {
            Utils.stopVService(this)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startV2Ray() }, 1000)
            true
        }
        R.id.delete_all -> {
            mainViewModel.serverList.toList().forEach { mainViewModel.removeServer(it) }
            mainViewModel.reloadServerList(); true
        }
        R.id.delete_bad -> {
            val toRemove = mainViewModel.serverList.toList().filter { guid ->
                val aff = MmkvManager.decodeServerAffiliationInfo(guid)
                aff?.testDelayMillis == null || aff.testDelayMillis <= 0L
            }
            toRemove.forEach { mainViewModel.removeServer(it) }
            mainViewModel.reloadServerList()
            toast(if (toRemove.isNotEmpty()) R.string.baz_delete_bad_done else R.string.toast_none_data)
            true
        }
        R.id.sort_tcping -> { mainViewModel.sortByTcping(); true }
        R.id.real_delay -> {
            if (mainViewModel.isRunning.value == true) bazRealDelayAll() else toast(R.string.connection_not_connected)
            true
        }
        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(this, mainViewModel.serverList) == 0) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }

//        R.id.settings -> {
//            startActivity<SettingsActivity>("isRunning" to isRunning)
//            true
//        }
//        R.id.logcat -> {
//            startActivity<LogcatActivity>()
//            true
//        }
        else -> super.onOptionsItemSelected(item)
    }


    /**
     * import config from qrcode
     */
    fun importQRcode(forConfig: Boolean): Boolean {
//        try {
//            startActivityForResult(Intent("com.google.zxing.client.android.SCAN")
//                    .addCategory(Intent.CATEGORY_DEFAULT)
//                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)
//        } catch (e: Exception) {
        RxPermissions(this)
                .request(Manifest.permission.CAMERA)
                .subscribe {
                    if (it)
                        if (forConfig)
                            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                        else
                            scanQRCodeForUrlToCustomConfig.launch(Intent(this, ScannerActivity::class.java))
                    else
                        toast(R.string.toast_permission_denied)
                }
//        }
        return true
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    private val scanQRCodeForUrlToCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importConfigCustomUrl(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    /**
     * import config from clipboard
     */
    fun importClipboard(): Boolean {
        return try {
            val clipboard = Utils.getClipboard(this).trim()
            if (clipboard.isBlank()) {
                toast(R.string.toast_none_data_clipboard); false
            } else if (Utils.isValidUrl(clipboard)) {
                if (MmkvManager.importUrlAsSubscription(clipboard) > 0) {
                    importConfigViaSub()
                } else {
                    bazFetchSubscriptionUrl(clipboard, "clipboard-${clipboard.hashCode()}")
                }
                true
            } else {
                importBatchConfig(clipboard); true
            }
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    fun importBatchConfig(server: String?, subid: String = "") {
        var count = AngConfigManager.importBatchConfig(server, subid)
        if (count <= 0) {
            count = AngConfigManager.importBatchConfig(Utils.decode(server!!), subid)
        }
        if (count > 0) {
            toast(R.string.toast_success)
            mainViewModel.reloadServerList()
        } else {
            toast(R.string.toast_failure)
        }
    }

    fun importConfigCustomClipboard()
            : Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard()
            : Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            GlobalScope.launch(Dispatchers.IO) {
                val configText = try {
                    Utils.getUrlContentWithCustomUserAgent(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    fun importConfigViaSub()
            : Boolean {
        try {
            toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first)
                        || TextUtils.isEmpty(it.second.remarks)
                        || TextUtils.isEmpty(it.second.url)
                ) {
                    return@forEach
                }
                val url = it.second.url
                if (!Utils.isValidUrl(url)) {
                    return@forEach
                }
                Log.d(ANG_PACKAGE, url)
                GlobalScope.launch(Dispatchers.IO) {
                    val configText = try {
                        Utils.getUrlContentWithCustomUserAgent(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        launch(Dispatchers.Main) {
                            toast("\"" + it.second.remarks + "\" " + getString(R.string.toast_failure))
                        }
                        return@launch
                    }
                    launch(Dispatchers.Main) {
                        importBatchConfig(Utils.decode(configText), it.first)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun bazDownloadFreeConfig() {
        toast(R.string.baz_downloading_free)
        val urls = listOf(
            "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
            "https://bazcode.ir/api/modem/free.php",
            "http://bazcode.ir/api/modem/free.php"
        )
        GlobalScope.launch(Dispatchers.IO) {
            var body = ""
            for (u in urls) {
                try {
                    body = Utils.getUrlContentWithCustomUserAgent(u).trim()
                    if (body.isNotBlank()) break
                } catch (e: Exception) { /* try next */ }
            }
            // If API returned JSON, extract config URIs from it
            if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                try {
                    val extracted = mutableListOf<String>()
                    val uriRegex = Regex("(?:vmess|vless|trojan|ss|socks)://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                    uriRegex.findAll(body).forEach { m ->
                        val v = m.value.trimEnd(',', '}', ']')
                        if (v.isNotBlank()) extracted.add(v)
                    }
                    if (extracted.isNotEmpty()) {
                        body = extracted.joinToString("\n")
                    } else {
                        // Try to find a subscription URL in links array
                        val urlRegex = Regex("https?://[^\\s\\\"'<>]+")
                        val subUrl = urlRegex.findAll(body)
                            .map { it.value.trimEnd(',', '}', ']') }
                            .firstOrNull { it.contains("raw.githubusercontent") || it.endsWith(".txt") || it.contains("sub") }
                        if (!subUrl.isNullOrBlank()) {
                            body = subUrl
                        }
                    }
                } catch (e: Exception) { /* keep raw text */ }
            }
            // If all network URLs failed, try bundled configs
            if (body.isBlank()) {
                try {
                    body = assets.open("bundled_free_configs.txt").bufferedReader().readText().trim()
                } catch (e: Exception) { /* no bundled configs */ }
            }
            launch(Dispatchers.Main) {
                if (body.isBlank()) { toast(R.string.baz_free_failed); return@launch }
                if (body.startsWith("http")) {
                    bazFetchSubscriptionUrl(body, "bazcode-free-sub")
                } else {
                    bazImportPayload(body, "bazcode-free")
                }
            }
        }
    }

    private fun bazFetchSubscriptionUrl(url: String, subId: String) {
        GlobalScope.launch(Dispatchers.IO) {
            val body = try { Utils.getUrlContentWithCustomUserAgent(url) } catch (e: Exception) { "" }.trim()
            // If fetched content is JSON, extract URIs
            var text = body
            if (text.isNotBlank() && text.trimStart().startsWith("{")) {
                try {
                    val extracted = mutableListOf<String>()
                    Regex("(?:vmess|vless|trojan|ss|socks)://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                        .findAll(text).forEach { m ->
                            val v = m.value.trimEnd(',', '}', ']')
                            if (v.isNotBlank()) extracted.add(v)
                        }
                    if (extracted.isNotEmpty()) text = extracted.joinToString("\n")
                } catch (e: Exception) { /* keep raw */ }
            }
            launch(Dispatchers.Main) { bazImportPayload(text, subId) }
        }
    }

    private fun bazImportPayload(body: String, subId: String) {
        if (body.isBlank()) { toast(R.string.baz_sub_failed); return }
        // Deduplicate: extract unique URI lines only
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }
        val uniqueLines = LinkedHashSet(lines).toList()
        val dedupedBody = uniqueLines.joinToString("\n")
        var count = AngConfigManager.importBatchConfig(dedupedBody, subId)
        if (count <= 0) {
            val decoded = try { Utils.decode(dedupedBody) } catch (e: Exception) { "" }
            if (decoded.isNotBlank()) count = AngConfigManager.importBatchConfig(decoded, subId)
        }
        // Extract URI schemes from mixed responses
        if (count <= 0) {
            val configs = Regex("(?:vmess|vless|trojan|ss|socks)://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                .findAll(body)
                .map { it.value.trimEnd(',', '}', ']') }
                .joinToString("\n")
            if (configs.isNotBlank()) {
                count = AngConfigManager.importBatchConfig(configs, subId)
            }
        }
        if (count <= 0) {
            val url = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE).find(body)?.value
            if (!url.isNullOrBlank() && Utils.isValidUrl(url)) {
                MmkvManager.importUrlAsSubscription(url)
                bazFetchSubscriptionUrl(url, "sub-${url.hashCode()}")
                return
            }
        }
        if (count > 0) { mainViewModel.reloadServerList(); toast(R.string.baz_free_success) }
        else toast(R.string.baz_sub_failed)
    }

    private fun bazConnectFastest() {
        if (mainViewModel.serverList.isEmpty()) { toast(R.string.toast_none_data); return }
        toast(R.string.baz_testing_fastest)
        GlobalScope.launch(Dispatchers.IO) {
            var best: String? = null; var bestDelay = Long.MAX_VALUE
            for (guid in mainViewModel.serverList) {
                val outbound = MmkvManager.decodeServerConfig(guid)?.getProxyOutbound()
                val host = outbound?.getServerAddress(); val port = outbound?.getServerPort()
                if (!host.isNullOrBlank() && port != null) {
                    val d = try { Utils.tcping(host, port) } catch (e: Exception) { -1L }
                    MmkvManager.encodeServerTestDelayMillis(guid, d)
                    if (d > 0 && d < bestDelay) { bestDelay = d; best = guid }
                }
            }
            launch(Dispatchers.Main) {
                binding.btnFastest.isEnabled = true
                binding.btnFastest.text = getString(R.string.baz_bottom_fastest)
                binding.pbFastest.visibility = View.GONE
                mainViewModel.reloadServerList()
                if (best != null) {
                    mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, best)
                    toast("${getString(R.string.baz_fastest_selected)}: ${bestDelay}ms")
                } else {
                    toast(R.string.toast_failure)
                }
            }
        }
    }

    private fun bazRealDelayAll() {
        val servers = mainViewModel.serverList.toList()
        val originalServer = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty()
        if (servers.isEmpty() || originalServer.isBlank()) {
            toast(R.string.toast_none_data)
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            toast(getString(R.string.baz_real_delay_started, servers.size))
            servers.forEachIndexed { index, guid ->
                binding.tvTestState.text = getString(R.string.baz_real_delay_progress, index + 1, servers.size)
                Utils.stopVService(this@MainActivity)
                waitForVpnState(false, 6000L)
                mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, guid)
                V2RayServiceManager.startV2Ray(this@MainActivity)

                val started = waitForVpnState(true, 12000L)
                val delayResult = if (started) {
                    delay(700L)
                    withContext(Dispatchers.IO) { Utils.testConnectionDelay(10808) }
                } else {
                    -1L
                }
                MmkvManager.encodeServerTestDelayMillis(guid, delayResult)
                mainViewModel.updateListAction.value = mainViewModel.serverList.indexOf(guid)
            }

            Utils.stopVService(this@MainActivity)
            waitForVpnState(false, 6000L)
            mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, originalServer)
            V2RayServiceManager.startV2Ray(this@MainActivity)
            waitForVpnState(true, 12000L)
            binding.tvTestState.text = getString(R.string.connection_connected)
            toast(R.string.baz_real_delay_done)
        }
    }

    private suspend fun waitForVpnState(expected: Boolean, timeoutMs: Long): Boolean {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            if (mainViewModel.isRunning.value == expected) return true
            delay(200L)
        }
        return mainViewModel.isRunning.value == expected
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } catch (ex: android.content.ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        RxPermissions(this)
                .request(Manifest.permission.READ_EXTERNAL_STORAGE)
                .subscribe {
                    if (it) {
                        try {
                            contentResolver.openInputStream(uri).use { input ->
                                importCustomizeConfig(input?.bufferedReader()?.readText())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else
                        toast(R.string.toast_permission_denied)
                }
    }

    /**
     * import customize config
     */
    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            mainViewModel.appendCustomConfigServer(server)
            toast(R.string.toast_success)
            adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(this, "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            return
        }
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun showCircle() {
        try {
            binding.fabProgressCircle?.show()
        } catch (e: Exception) {
        }
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        if (binding.fabProgressCircle.isShown) {
                            binding.fabProgressCircle.hide()
                        }
                    }
        } catch (e: Exception) {
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> startActivity(Intent(this, SubSettingActivity::class.java))
            R.id.vpn_share -> {
                val enabled = !ShareService.isEnabled(this)
                ShareService.setEnabled(this, enabled); ShareService.ensureRunning(this); item.isChecked = enabled
                toast(if (enabled) R.string.baz_share_on else R.string.baz_share_off)
            }
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("isRunning", mainViewModel.isRunning.value == true))
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
