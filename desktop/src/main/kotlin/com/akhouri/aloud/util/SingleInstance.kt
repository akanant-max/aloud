package com.akhouri.aloud.util

import com.akhouri.aloud.AppPaths
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Keeps Aloud to one running copy. Starting it again brings the running one to the front
 * instead of opening a second library on the same files.
 */
object SingleInstance {

  private var lock: FileLock? = null
  private var server: ServerSocket? = null

  /** True if this is the only copy running; then [onShow] runs whenever another copy starts. */
  fun claim(onShow: () -> Unit): Boolean {
    val channel = runCatching { RandomAccessFile(AppPaths.instanceLock, "rw").channel }.getOrNull() ?: return true
    val acquired = runCatching { channel.tryLock() }.getOrNull()
    if (acquired == null) {
      signalRunningCopy()
      return false
    }
    lock = acquired
    runCatching {
      val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
      server = socket
      AppPaths.instancePort.writeText(socket.localPort.toString())
      thread(isDaemon = true, name = "single-instance") {
        while (!socket.isClosed) {
          runCatching { socket.accept().use { onShow() } }
        }
      }
    }
    return true
  }

  private fun signalRunningCopy() {
    runCatching {
      val port = AppPaths.instancePort.readText().trim().toInt()
      Socket(InetAddress.getLoopbackAddress(), port).close()
    }
  }
}
