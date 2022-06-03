// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.zip.ZipInputStream
import kotlin.io.path.*

val V2RayZipURL = URL("https://github.com/v2fly/v2ray-core/releases/download/v4.45.0/v2ray-windows-64.zip")
val V2RayBinPath = Paths.get("v2ray.exe")
val V2RayConfigPath = Paths.get("config.json")
val ConfigEncoding = Charset.forName("UTF-8")
val logger = Logger.getLogger("V2RayLauncher")

fun startDownloadingBin(v2rayBinStatus: MutableState<V2RayBinStatus>){
    val binStatus = v2rayBinStatus.value
    if(binStatus !is V2RayBinNotExist) return
    val downloadThread = Thread {
        try {
            val urlStream = if(binStatus.proxyURL != null) {
                val proxyURL = URL(binStatus.proxyURL)
                val proxyProtocol = when(proxyURL.protocol){
                    "http" -> Proxy.Type.HTTP
                    "socks4", "socks5" -> Proxy.Type.SOCKS
                    else -> throw IllegalArgumentException("Unknown protocol: ${proxyURL.protocol}")
                }
                val sa = InetSocketAddress(proxyURL.host, proxyURL.port)
                val proxy = Proxy(proxyProtocol, sa)
                V2RayZipURL.openConnection(proxy).getInputStream()
            } else {
                V2RayZipURL.openStream()
            }
            logger.info("V2Ray Downloaded")
            val zipFile = ZipInputStream(urlStream)
            var zipEntry = zipFile.nextEntry
            while (zipEntry != null) {
                if(zipEntry.name == V2RayBinPath.fileName.toString()){
                    V2RayBinPath.writeBytes(zipFile.readBytes())
                    break
                }
                zipEntry = zipFile.nextEntry
            }
            zipFile.close()
            logger.info("V2Ray Extracted")
            v2rayBinStatus.value = V2RayBinComplete
        } catch (err: java.lang.Exception) {
            v2rayBinStatus.value = V2RayBinDownloadingError(err.message?:"Unknown Error", binStatus.proxyURL)
            logger.info("V2Ray Downloading Failed: ${err.message}")
            throw err
        }
    }
    logger.info("Start Downloading V2Ray")
    downloadThread.start()
    v2rayBinStatus.value = V2RayBinDownloading
}

fun killV2Ray(process: Process){
    process.destroy()
    process.waitFor()
}

fun startV2Ray(runningStatus: MutableState<V2RayInstanceStatus>){
    val running = runningStatus.value
    if(running is V2RayInstanceRunning){
        killV2Ray(running.process)
    }
    val cmd = V2RayBinPath.absolutePathString()
    val conf = V2RayConfigPath.absolutePathString()
    val pb = ProcessBuilder(cmd, "-c", conf)
    pb.inheritIO()
    val process = pb.start()
    val runner = Thread {
        process.waitFor()
        val code = process.exitValue()
        runningStatus.value = V2RayInstanceStopped(code)
    }
    runner.start()
    runningStatus.value = V2RayInstanceRunning(process, runner)
}

fun stopV2Ray(runningStatus: MutableState<V2RayInstanceStatus>){
    val running = runningStatus.value
    if(running is V2RayInstanceRunning){
        running.process.destroy()
        logger.info("V2Ray Stopped.")
    }
    runningStatus.value = V2RayInstanceStopped(0)
}

@Composable
@Preview
fun App() {
    val v2rayBinStatus = remember { mutableStateOf<V2RayBinStatus>(V2RayBinUnknown) }
    val v2rayConfigContent = remember { mutableStateOf<String>("") }
    val v2rayInstanceStatus = remember { mutableStateOf<V2RayInstanceStatus>(V2RayInstanceStopped(0)) }
    val v2rayInstanceOutput = remember { mutableStateListOf<String>() }

    LaunchedEffect(v2rayBinStatus.value) {
        if(v2rayBinStatus.value is V2RayBinUnknown){
            v2rayBinStatus.value = if(V2RayBinPath.exists()) {
                V2RayBinComplete
            } else {
                V2RayBinNotExist(null)
            }
        }
    }

    SideEffect {
        if(V2RayConfigPath.exists()){
            v2rayConfigContent.value = V2RayConfigPath.readText(ConfigEncoding)
        } else {
            V2RayConfigPath.writeText("", ConfigEncoding)
            v2rayConfigContent.value = ""
        }
    }

    LaunchedEffect(v2rayBinStatus.value, v2rayInstanceStatus.value) {
        if(v2rayBinStatus.value !is V2RayBinComplete) {
            val runningStatus = v2rayInstanceStatus.value
            if(runningStatus is V2RayInstanceRunning) {
                runningStatus.process.destroy()
                withContext(Dispatchers.IO) {
                    runningStatus.process.waitFor(5, TimeUnit.SECONDS)
                }
                v2rayInstanceStatus.value = V2RayInstanceStopped(0)
            }
            return@LaunchedEffect
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val running = v2rayInstanceStatus.value
            if(running is V2RayInstanceRunning){
                logger.info("Stopping V2Ray.")
                killV2Ray(running.process)
            }
            logger.info("Exiting.")
        }
    }

    MaterialTheme {
        Row() {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth() ) {
                when(val binStatus = v2rayBinStatus.value) {
                    is V2RayBinUnknown -> Text("Initializing...")
                    is V2RayBinNotExist, is V2RayBinDownloadingError -> {
                        Text("V2Ray Not Found.")
                        val proxyURL = when(binStatus) {
                            is V2RayBinNotExist -> binStatus.proxyURL
                            is V2RayBinDownloadingError -> binStatus.proxyURL
                            else -> null
                        }
                        if(binStatus is V2RayBinDownloadingError){
                            Text(binStatus.message, color= Color.Red)
                        }
                        TextField(
                            value=proxyURL ?: "",
                            label={Text("Proxy URL:")},
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            onValueChange={v2rayBinStatus.value = V2RayBinNotExist(it)})
                        Button(onClick = {
                            startDownloadingBin(v2rayBinStatus)
                        }){
                            Text("Download V2Ray")
                        }
                    }
                    is V2RayBinDownloading -> { Text("Downloading V2Ray") }
                    is V2RayBinComplete ->  {
                        TextField(
                            value = v2rayConfigContent.value,
                            singleLine = false,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                            label={Text("V2Ray Config:")},
                            onValueChange = {
                                v2rayConfigContent.value = it
                                V2RayConfigPath.writeText(it, ConfigEncoding)
                            }
                        )
                        when(val runningStatus = v2rayInstanceStatus.value){
                            is V2RayInstanceRunning -> {
                                Button(onClick = {
                                    stopV2Ray(v2rayInstanceStatus)
                                }) {
                                    Text("Stop V2Ray")
                                }
                            }
                            is V2RayInstanceStopped -> {
                                if(runningStatus.exitCode != 0 && runningStatus.exitCode != 1){
                                    Text("Failed to launch V2Ray: ${runningStatus.exitCode}", color=Color.Red)
                                }
                                Button(onClick = {
                                    startV2Ray(v2rayInstanceStatus)
                                }) {
                                    Text("Start V2Ray")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun main() = application {
    Window(title= "V2Ray Launcher", onCloseRequest = ::exitApplication) {
        App()
    }
}
