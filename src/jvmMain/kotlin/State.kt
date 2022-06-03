sealed interface V2RayBinStatus
object V2RayBinUnknown : V2RayBinStatus
data class V2RayBinNotExist(val proxyURL: String?): V2RayBinStatus
object V2RayBinDownloading: V2RayBinStatus
data class V2RayBinDownloadingError(val message: String, val proxyURL: String?): V2RayBinStatus
object V2RayBinComplete: V2RayBinStatus

sealed interface V2RayInstanceStatus
data class V2RayInstanceStopped(val exitCode: Int = 0): V2RayInstanceStatus
data class V2RayInstanceRunning(val process: Process, val runner: Thread): V2RayInstanceStatus
