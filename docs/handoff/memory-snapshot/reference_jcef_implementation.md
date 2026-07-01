# JCEF Implementation Reference for IntelliJ Plugin Chat/Agent UI

Research date: 2026-03-19

## 1. Core API Classes and Method Signatures

### JBCefApp - Availability Check
```kotlin
// ALWAYS check before creating a browser
if (JBCefApp.isSupported()) {
    // create JCEF browser
} else {
    // fall back to Swing panel (MissingJcefPanel)
}
```

### JBCefBrowserBuilder - Browser Creation
```kotlin
// Builder pattern (preferred for 2025.1+)
val browser = JBCefBrowser.createBuilder()
    .setOffScreenRendering(true)       // OSR mode - better perf, renders in separate thread
    .setCreateImmediately(false)       // defer creation until needed
    .setEnableOpenDevToolsMenuItem(true) // right-click > DevTools
    .setUrl(null)                      // set URL later
    .setClient(customClient)           // optional custom JBCefClient
    .setMouseWheelEventEnable(true)    // enable mouse wheel
    .setWindowlessFramerate(30)        // OSR frame rate
    .build()

// Simple creation
val browser = JBCefBrowser()
val browser = JBCefBrowser("https://example.com")
```

### JBCefBrowserBase - Key Methods
```kotlin
// Loading content
browser.loadURL("https://file+.myapp.com/index.html")
browser.loadHTML("<html>...</html>")
browser.loadHTML("<html>...</html>", "https://dummy-url.com")  // with fake URL for policies

// Getting components
browser.getComponent()        // -> JComponent (add to Swing panel)
browser.getCefBrowser()       // -> CefBrowser (for executeJavaScript)
browser.getJBCefClient()      // -> JBCefClient (for adding handlers)

// Lifecycle
browser.createImmediately()   // force browser creation now
browser.openDevtools()        // open Chrome DevTools
browser.dispose()             // cleanup
browser.isDisposed()          // check state
browser.isOffScreenRendering() // check OSR mode

// Appearance
browser.setPageBackgroundColor(Color.WHITE)
browser.setOpenLinksInExternalBrowser(true)
```

### JBCefJSQuery - JavaScript-to-Kotlin Communication
```kotlin
// Create (must pass JBCefBrowserBase)
val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

// Add handler (Kotlin side receives string from JS)
jsQuery.addHandler { message: String ->
    // Process the message (typically JSON)
    val data = Json.decodeFromString<MyMessage>(message)
    handleMessage(data)

    // Return null for no response, or Response for success/error
    null  // or JBCefJSQuery.Response("success")
    // or JBCefJSQuery.Response(null, 1, "error message")
}

// Inject into JavaScript (generates the cefQuery call code)
val jsCode = jsQuery.inject("msg")  // simple - default empty callbacks
val jsCode = jsQuery.inject(
    "msg",                                    // JS variable containing the string to send
    "function(response) { onSuccess(response) }",  // success callback
    "function(code, msg) { onError(code, msg) }"   // error callback
)

// Response inner class
JBCefJSQuery.Response("success data")           // success
JBCefJSQuery.Response(null, 404, "Not found")   // error
response.isSuccess()
response.response()
response.errCode()
response.errMsg()

// Cleanup
jsQuery.dispose()
```

### JBCefClient - Handler Registration
```kotlin
val client = browser.jbCefClient

// IMPORTANT: Set JS query pool size for high-volume messaging
client.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 200)

// Add handlers
client.addLoadHandler(myLoadHandler, browser.cefBrowser)
client.addRequestHandler(myRequestHandler, browser.cefBrowser)
client.addLifeSpanHandler(myLifeSpanHandler, browser.cefBrowser)
client.addFocusHandler(myFocusHandler, browser.cefBrowser)
client.addDownloadHandler(myDownloadHandler, browser.cefBrowser)
```

## 2. Bidirectional Communication Patterns

### Kotlin -> JavaScript (sending data, streaming tokens)
```kotlin
// Execute arbitrary JS in the browser
fun sendToWebview(messageType: String, data: Any?, messageId: String = uuid()) {
    val json = gson.toJson(BrowserMessage(messageType, messageId, data))
    val jsCode = """window.postMessage($json, "*");"""
    browser.cefBrowser.executeJavaScript(jsCode, browser.cefBrowser.url, 0)
}

// Alternative: dispatch CustomEvent (Cody pattern)
fun postMessageHostToWebview(jsonMessage: String) {
    val code = """
        (() => {
            let e = new CustomEvent('message');
            e.data = $jsonMessage;
            window.dispatchEvent(e);
        })()
    """.trimIndent()
    browser.cefBrowser.executeJavaScript(code, "app://postMessage", 0)
}

// For streaming tokens: call executeJavaScript repeatedly per token
fun streamToken(token: String) {
    val escaped = gson.toJson(token)  // properly escape
    browser.cefBrowser.executeJavaScript(
        "window.appendToken($escaped);",
        "app://stream", 0
    )
}
```

### JavaScript -> Kotlin (user input, button clicks)
```kotlin
// 1. Create JBCefJSQuery
val viewToHost = JBCefJSQuery.create(browser as JBCefBrowserBase)
viewToHost.addHandler { query: String ->
    // Parse JSON message from webview
    val obj = JsonParser.parseString(query).asJsonObject
    when (obj["type"]?.asString) {
        "userMessage" -> handleUserMessage(obj["text"].asString)
        "buttonClick" -> handleButtonClick(obj["action"].asString)
    }
    JBCefJSQuery.Response(null)
}

// 2. Inject the bridge function into JS (do this in onLoadEnd)
val bridgeScript = """
    window.postToPlugin = function(message) {
        const msg = JSON.stringify(message);
        ${viewToHost.inject("msg")}
    }
"""
browser.cefBrowser.executeJavaScript(bridgeScript, url, 0)

// 3. JavaScript side calls:
// window.postToPlugin({ type: 'userMessage', text: 'Hello' })
```

### VS Code API Emulation Pattern (Cody approach)
```kotlin
// Cody emulates the VS Code extension API so the same webview code works
val apiScript = """
    globalThis.acquireVsCodeApi = (function() {
        let state = ${initialStateJSON};
        return () => {
            return Object.freeze({
                postMessage: function(message) {
                    ${jsQuery.inject("JSON.stringify({what: 'postMessage', value: message})")}
                },
                setState: function(newState) {
                    ${jsQuery.inject("JSON.stringify({what: 'setState', value: newState})")}
                    state = newState;
                    return newState;
                },
                getState: function() { return state; }
            });
        };
    })();
"""
```

## 3. Loading Local HTML Resources from Plugin JAR

### Approach A: Custom CefSchemeHandlerFactory (Continue.dev pattern)
```kotlin
// Register a custom scheme that maps to resources in the JAR
CefApp.getInstance().registerSchemeHandlerFactory(
    "http", "myapp", MySchemeHandlerFactory()
)

// Factory creates resource handlers
class MySchemeHandlerFactory : CefSchemeHandlerFactory {
    override fun create(browser: CefBrowser?, frame: CefFrame?,
                       schemeName: String, request: CefRequest): CefResourceHandler {
        return MyResourceHandler()
    }
}

class MyResourceHandler : CefResourceHandler {
    private var inputStream: InputStream? = null
    private var mimeType: String = "text/plain"

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        val path = request.url.replace("http://myapp", "webview/")
        val resource = javaClass.classLoader.getResource(path)
        inputStream = resource?.openStream()
        mimeType = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "text/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "text/plain"
        }
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, length: IntRef, redirect: StringRef) {
        response.mimeType = mimeType
        response.status = if (inputStream != null) 200 else 404
        length.set(inputStream?.available() ?: 0)
    }

    override fun readResponse(out: ByteArray, bytesToRead: Int,
                             bytesRead: IntRef, callback: CefCallback): Boolean {
        val stream = inputStream ?: return false
        val available = stream.available()
        if (available <= 0) { stream.close(); return false }
        val read = stream.read(out, 0, minOf(available, bytesToRead))
        bytesRead.set(read)
        return true
    }

    override fun cancel() { inputStream?.close() }
}

// Load the page
browser.loadURL("http://myapp/index.html")
```

**Resource directory structure:**
```
src/main/resources/
  webview/
    index.html
    app.js
    app.css
    assets/
      icons/...
```

### Approach B: CefLocalRequestHandler (simpler, JB-provided)
```kotlin
val requestHandler = CefLocalRequestHandler("http", "localhost")
browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)

// Register individual resources
requestHandler.addResource("/index.html") {
    CefStreamResourceHandler(
        javaClass.getResourceAsStream("/webview/index.html"),
        "text/html", this
    )
}
requestHandler.addResource("/app.js") {
    CefStreamResourceHandler(
        javaClass.getResourceAsStream("/webview/app.js"),
        "text/javascript", this
    )
}

browser.loadURL("http://localhost/index.html")
```

### Approach C: CefRequestHandler with file serving (Cody pattern)
```kotlin
// Cody uses a pseudo host URL and serves resources from the plugin directory
// This is more complex but supports serving from disk (not just JAR)
browser.jbCefClient.addRequestHandler(
    MyRequestHandler(apiScript), browser.cefBrowser
)

// In getResourceRequestHandler():
override fun getResourceRequestHandler(...): CefResourceRequestHandler? {
    if (request.url.startsWith(PSEUDO_HOST_URL)) {
        disableDefaultHandling?.set(true)
        return MyResourceRequestHandler()
    }
    return null
}
```

### Approach D: loadHTML with inline content
```kotlin
// Simplest approach for small UIs - inline everything
val html = """
<!DOCTYPE html>
<html>
<head>
    <style>${loadResourceAsString("/webview/app.css")}</style>
</head>
<body>
    <div id="app"></div>
    <script>${loadResourceAsString("/webview/app.js")}</script>
</body>
</html>
"""
browser.loadHTML(html)
```

## 4. Theme Integration

### Detecting Theme Changes
```kotlin
// Pattern from Cody's WebThemeController
class ThemeController(parentDisposable: Disposable) {
    private var themeChangeListener: ((WebTheme) -> Unit)? = null

    init {
        // Listen for LookAndFeel changes
        UIManager.addPropertyChangeListener { event ->
            if (event.propertyName == "lookAndFeel") {
                invokeLater { themeChangeListener?.invoke(getTheme()) }
            }
        }

        // Listen for UISettings changes (font size, etc.)
        ApplicationManager.getApplication()
            .messageBus
            .connect(parentDisposable)
            .subscribe(UISettingsListener.TOPIC, UISettingsListener { _ ->
                invokeLater { themeChangeListener?.invoke(getTheme()) }
            })
    }

    fun getTheme(): WebTheme {
        // Extract all color variables from UIManager
        val colorVariables = UIManager.getDefaults()
            .filterValues { it is Color }
            .mapKeys { toCSSVariableName(it.key.toString()) }
            .mapValues { toCSSColor(it.value as Color) }

        val fontSize = "${UISettings.getInstance().fontSize}px"
        return WebTheme(isDarkTheme(), colorVariables + ("--font-size" to fontSize))
    }

    fun isDarkTheme(): Boolean {
        // Brightness-based detection (reliable)
        val bg = UIUtil.getPanelBackground()
        val brightness = sqrt(
            bg.red * bg.red * 0.299 +
            bg.green * bg.green * 0.587 +
            bg.blue * bg.blue * 0.114
        )
        return brightness < 128
    }

    private fun toCSSColor(c: Color) = "rgb(${c.red} ${c.green} ${c.blue} / ${c.alpha / 255.0})"
    private fun toCSSVariableName(key: String) = "--jb-${key.replace(Regex("[^-_a-zA-Z0-9]"), "-")}"
}
```

### Pushing Theme to Webview
```kotlin
fun updateTheme(theme: WebTheme) {
    val code = """
        (() => {
            let e = new CustomEvent('message');
            e.data = {
                type: 'themeChanged',
                isDark: ${theme.isDark},
                cssVariables: ${gson.toJson(theme.variables)}
            };
            window.dispatchEvent(e);
        })()
    """.trimIndent()
    browser.cefBrowser.executeJavaScript(code, "app://theme", 0)
}
```

### JavaScript Side Theme Handling
```javascript
// Listen for theme updates from plugin
window.addEventListener('message', (event) => {
    if (event.data.type === 'themeChanged') {
        const root = document.documentElement;
        for (const [key, value] of Object.entries(event.data.cssVariables)) {
            root.style.setProperty(key, value);
        }
        document.body.classList.toggle('dark', event.data.isDark);
    }
});
```

## 5. Browser Lifecycle Management

### Service-Level Management (recommended)
```kotlin
@Service(Service.Level.PROJECT)
class ChatBrowserService(val project: Project) : Disposable {
    private var browser: ChatBrowser? = null

    init { load() }

    private fun load(): ChatBrowser? {
        if (browser != null) return browser
        if (!JBCefApp.isSupported()) return null

        val newBrowser = ChatBrowser(project)
        Disposer.register(this, newBrowser)  // auto-dispose with service
        browser = newBrowser
        return browser
    }

    fun reload() {
        val old = browser
        browser = null
        old?.let {
            ApplicationManager.getApplication().invokeLater { Disposer.dispose(it) }
        }
        load()
    }

    override fun dispose() {
        browser?.let { Disposer.dispose(it) }
        browser = null
    }
}
```

### Browser Class Disposal
```kotlin
class ChatBrowser(private val project: Project) : Disposable {
    private val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(true)
        .build()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    override fun dispose() {
        Disposer.dispose(jsQuery)    // dispose query FIRST
        Disposer.dispose(browser)    // then dispose browser
    }
}
```

### CefLoadHandler - Wait for Page Load
```kotlin
// CRITICAL: Inject JS bridge only AFTER page loads
browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
    override fun onLoadingStateChange(
        browser: CefBrowser?, isLoading: Boolean,
        canGoBack: Boolean, canGoForward: Boolean
    ) {
        if (!isLoading) {
            // Page finished loading - safe to inject JS
            injectJavaScriptBridge()
        }
    }
}, browser.cefBrowser)

// Alternative: onLoadEnd for specific frame
override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
    if (frame?.isMain == true) {
        injectJavaScriptBridge()
    }
}
```

## 6. JCEF Availability and Fallback

### Checking Availability
```kotlin
// Simple check
if (!JBCefApp.isSupported()) {
    return MissingJcefPanel()  // Swing fallback
}

// Cody's more thorough check
class VerifyJavaBootRuntimeVersion {
    companion object {
        fun isCurrentRuntimeMissingJcef(): Boolean {
            return !JBCefApp.isSupported()
        }
    }
}
```

### Fallback Panel (Cody pattern)
```kotlin
class MissingJcefPanel : JPanel(GridBagLayout()) {
    init {
        val description = JTextPane().apply {
            text = "JCEF is required but not available in your current runtime."
        }
        val button = JButton("Choose Runtime with JCEF...").apply {
            addActionListener { RuntimeChooserUtil.showRuntimeChooserPopup() }
        }
        add(description, constraints)
        add(button, constraints)
    }
}
```

## 7. Resource File Structure (Gradle)

```
module-name/
  src/main/
    kotlin/
      com/workflow/orchestrator/core/ui/web/
        AgentBrowser.kt              # JBCefBrowser wrapper
        AgentBrowserService.kt       # Project-level service
        ThemeController.kt           # Theme detection + push
        ResourceSchemeHandler.kt     # Custom scheme for JAR resources
        AgentToolWindowFactory.kt    # ToolWindow integration
    resources/
      webview/                       # HTML/CSS/JS for the chat UI
        index.html
        chat.js                      # or bundled React app
        chat.css
        assets/
          icons/
      META-INF/
        plugin.xml
```

## 8. Key Gotchas and Best Practices

1. **JS_QUERY_POOL_SIZE**: Set `client.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 200)` for high-volume messaging (streaming tokens). Default is too low.

2. **OSR Mode**: Use `setOffScreenRendering(true)` for better performance and compatibility. Renders in separate thread, prevents UI blocking.

3. **Page Load Timing**: NEVER execute JavaScript before the page loads. Use `CefLoadHandlerAdapter.onLoadingStateChange(isLoading=false)` or `onLoadEnd()`.

4. **Disposal Order**: Dispose JBCefJSQuery BEFORE JBCefBrowser. Register both with Disposer for automatic cleanup.

5. **External Links**: Use `CefLifeSpanHandler.onBeforePopup()` to intercept link clicks and open in system browser via `BrowserOpener.openInBrowser()`.

6. **Focus Issues**: Multiple JCEF instances can cause focus flickering. See Cody's `patchBrowserFocusHandler()` workaround (IJPL-158952).

7. **executeJavaScript is fire-and-forget**: No return value. Use JBCefJSQuery for JS->Kotlin responses.

8. **String Escaping**: Always JSON-serialize data before injecting into JS to prevent injection bugs. Use `gson.toJson()` or `Json.encodeToString()`.

9. **Thread Safety**: `loadURL()` and `loadHTML()` are safe from any thread. `executeJavaScript()` is also thread-safe. UI component access must be on EDT.

10. **Blank Webview Recovery**: Implement a `reload()` method that disposes and recreates the browser for when the webview freezes or goes blank.

## 9. Reference Implementations

- **Continue.dev**: `extensions/intellij/src/main/kotlin/.../browser/ContinueBrowser.kt` - Simple, clean pattern with custom scheme handler
- **Sourcegraph Cody**: `src/main/kotlin/com/sourcegraph/cody/ui/web/WebUIProxy.kt` - Complex, VS Code API emulation, request handler pattern
- **Both plugins**: OSR mode, JSON message passing, custom resource handlers, theme integration
