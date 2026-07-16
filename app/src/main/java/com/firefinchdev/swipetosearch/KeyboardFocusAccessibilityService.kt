package com.firefinchdev.swipetosearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

@SuppressLint("AccessibilityPolicy")
class KeyboardFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyboardFocusService"
        private const val TARGET_ID_NAME = "search_src_text"
        private const val FOCUS_COOLDOWN_MS = 400L
        private const val CONTENT_CHANGED_DEBOUNCE_MS = 50L
        const val PREF_NAME = "app_prefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    private var currentLauncherPackage: String? = null
    private var cachedFullViewId: String? = null
    private var lastFocusAttemptTime = 0L
    private var isDrawerSessionActive = false
    private var isServiceEnabledInApp = true

    private lateinit var stabilityHandler: Handler
    private val contentChangedRunnable = Runnable { processFocusAttempt() }

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == KEY_SERVICE_ENABLED) {
                isServiceEnabledInApp = sharedPreferences.getBoolean(KEY_SERVICE_ENABLED, true)
            }
        }

    override fun onServiceConnected() {
        stabilityHandler = Handler(mainLooper)

        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        isServiceEnabledInApp = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        identifyDefaultLauncher()

        // Configure service info dynamically to filter only launcher events
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            // Filter events to only the launcher package — huge perf win
            packageNames = currentLauncherPackage?.let { arrayOf(it) }
        }
    }

    private fun identifyDefaultLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = resolveInfo?.activityInfo?.packageName
        if (pkg != null) {
            currentLauncherPackage = pkg
            cachedFullViewId = "$pkg:id/$TARGET_ID_NAME"
        } else {
            Log.w(TAG, "Could not identify a default launcher.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (currentLauncherPackage == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                stabilityHandler.removeCallbacks(contentChangedRunnable)
                processFocusAttempt()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                stabilityHandler.removeCallbacks(contentChangedRunnable)
                stabilityHandler.postDelayed(contentChangedRunnable, CONTENT_CHANGED_DEBOUNCE_MS)
            }
        }
    }

    private fun processFocusAttempt() {
        if (!isServiceEnabledInApp) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFocusAttemptTime > FOCUS_COOLDOWN_MS) {
            attemptFocus(currentTime)
        }
    }

    private fun attemptFocus(currentTime: Long) {
        val rootNode = rootInActiveWindow ?: return
        val viewId = cachedFullViewId ?: return

        try {
            val foundNodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!foundNodes.isNullOrEmpty()) {
                if (!isDrawerSessionActive) {
                    val targetNode = foundNodes[0]
                    val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) {
                        lastFocusAttemptTime = currentTime
                        isDrawerSessionActive = true
                    }
                }
                for (node in foundNodes) node.recycle()
            } else {
                if (isDrawerSessionActive) {
                    isDrawerSessionActive = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding search bar", e)
        } finally {
            rootNode.recycle()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isServiceEnabledInApp || !isDrawerSessionActive) return false

        val isEnterOrSearch = event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_SEARCH ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        if (isEnterOrSearch && event.action == KeyEvent.ACTION_UP) {
            val handled = launchFirstSearchResult()
            if (handled) return true
        }
        return false
    }

    /**
     * Walks the accessibility tree of the active window to find the first
     * clickable app-result node (list item / icon) and clicks it.
     * Returns true if a node was found and clicked.
     */
    private fun launchFirstSearchResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            findFirstClickableResult(root)?.let { node ->
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                clicked
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error launching first search result", e)
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * BFS through the tree to find the first node that is:
     *  - clickable
     *  - not the search field itself (we skip the search_src_text node)
     *  - not a keyboard / IME node
     */
    private fun findFirstClickableResult(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName ?: ""

            // Skip the search bar itself
            if (viewId.endsWith(TARGET_ID_NAME)) {
                node.recycle()
                continue
            }

            if (node.isClickable && node.isVisibleToUser) {
                // Return a fresh reference — caller must recycle
                return AccessibilityNodeInfo.obtain(node).also { node.recycle() }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return null
    }

    override fun onInterrupt() {
        stabilityHandler.removeCallbacks(contentChangedRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stabilityHandler.removeCallbacks(contentChangedRunnable)
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }
}