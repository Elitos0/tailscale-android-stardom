// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.util.Log
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TSLogTest {
  private data class LogEntry(
      val priority: Int,
      val tag: String,
      val msg: String,
      val tr: Throwable?
  )

  private val recordedLogs = mutableListOf<LogEntry>()
  private val daemonLogs = mutableListOf<Pair<String?, String>>()

  private val originalDebugProvider = TSLog.isDebugProvider
  private val originalXiaomiProvider = TSLog.isXiaomiProvider
  private val originalLibtailscaleWrapper = TSLog.libtailscaleWrapper

  @Before
  fun setUp() {
    recordedLogs.clear()
    daemonLogs.clear()

    Log.sink =
        Log.LogSink { priority, tag, msg, tr -> recordedLogs.add(LogEntry(priority, tag, msg, tr)) }

    val mockWrapper = mock<TSLog.LibtailscaleWrapper>()
    whenever(mockWrapper.sendLog(anyString(), anyString())).thenAnswer { invocation ->
      val tag = invocation.getArgument<String>(0)
      val msg = invocation.getArgument<String>(1)
      daemonLogs.add(tag to msg)
      null
    }
    TSLog.libtailscaleWrapper = mockWrapper
  }

  @After
  fun tearDown() {
    Log.sink = null
    TSLog.isDebugProvider = originalDebugProvider
    TSLog.isXiaomiProvider = originalXiaomiProvider
    TSLog.libtailscaleWrapper = originalLibtailscaleWrapper
  }

  @Test
  fun debugBuildNonXiaomiUsesDebugAndVerboseLevelsWithoutDuplicates() {
    TSLog.isDebugProvider = { true }
    TSLog.isXiaomiProvider = { false }

    TSLog.d("TagD", "debug message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.DEBUG, recordedLogs[0].priority)
    assertEquals("TagD", recordedLogs[0].tag)
    assertEquals("debug message", recordedLogs[0].msg)

    recordedLogs.clear()
    TSLog.v("TagV", "verbose message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.VERBOSE, recordedLogs[0].priority)
    assertEquals("TagV", recordedLogs[0].tag)

    recordedLogs.clear()
    TSLog.i("TagI", "info message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.INFO, recordedLogs[0].priority)

    recordedLogs.clear()
    TSLog.w("TagW", "warn message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.WARN, recordedLogs[0].priority)

    recordedLogs.clear()
    TSLog.e("TagE", "error message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.ERROR, recordedLogs[0].priority)
  }

  @Test
  fun debugBuildXiaomiPromotesDebugAndVerboseToInfoWithoutDuplicateWarningOrError() {
    TSLog.isDebugProvider = { true }
    TSLog.isXiaomiProvider = { true }

    TSLog.d("TagD", "debug on xiaomi")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.INFO, recordedLogs[0].priority)
    assertEquals("TagD", recordedLogs[0].tag)
    assertEquals("debug on xiaomi", recordedLogs[0].msg)

    recordedLogs.clear()
    TSLog.v("TagV", "verbose on xiaomi")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.INFO, recordedLogs[0].priority)
    assertEquals("TagV", recordedLogs[0].tag)

    recordedLogs.clear()
    TSLog.i("TagI", "info message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.INFO, recordedLogs[0].priority)

    recordedLogs.clear()
    TSLog.w("TagW", "warn on xiaomi")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.WARN, recordedLogs[0].priority)

    recordedLogs.clear()
    TSLog.e("TagE", "error on xiaomi")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.ERROR, recordedLogs[0].priority)
  }

  @Test
  fun releaseBuildDoesNotPromoteVerboseOrDebugToInfo() {
    TSLog.isDebugProvider = { false }
    TSLog.isXiaomiProvider = { true }

    TSLog.d("TagD", "release debug")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.DEBUG, recordedLogs[0].priority)

    recordedLogs.clear()
    TSLog.v("TagV", "release verbose")
    // Should NOT be logged as INFO in release
    assertFalse(recordedLogs.any { it.priority == Log.INFO })

    recordedLogs.clear()
    TSLog.w("TagW", "release warn")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.WARN, recordedLogs[0].priority)

    recordedLogs.clear()
    TSLog.e("TagE", "release error")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.ERROR, recordedLogs[0].priority)
  }

  @Test
  fun warningAndErrorWithThrowableDoNotDuplicateLogcatEntries() {
    TSLog.isDebugProvider = { true }
    TSLog.isXiaomiProvider = { true }

    val error = IOException("disk error")
    TSLog.w("TagW", "warning with error", error)
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.WARN, recordedLogs[0].priority)
    assertEquals("TagW", recordedLogs[0].tag)
    assertEquals(error, recordedLogs[0].tr)

    recordedLogs.clear()
    val error2 = RuntimeException("crash")
    TSLog.e("TagE", "error with exception", error2)
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.ERROR, recordedLogs[0].priority)
    assertEquals("TagE", recordedLogs[0].tag)
    assertEquals(error2, recordedLogs[0].tr)
  }

  @Test
  fun defaultTagIsUsedWhenNull() {
    TSLog.isDebugProvider = { false }
    TSLog.isXiaomiProvider = { false }

    TSLog.d(message = "default tag debug")
    assertEquals(1, recordedLogs.size)
    assertEquals(TSLog.TAG, recordedLogs[0].tag)
  }

  @Test
  fun logsPropagateToLibtailscaleWrapper() {
    TSLog.isDebugProvider = { true }
    TSLog.isXiaomiProvider = { false }

    TSLog.d("DaemonTagD", "debug daemon msg")
    TSLog.i("DaemonTagI", "info daemon msg")
    TSLog.w("DaemonTagW", "warn daemon msg")
    TSLog.e("DaemonTagE", "error daemon msg")

    assertEquals(4, daemonLogs.size)
    assertEquals("DaemonTagD" to "debug daemon msg", daemonLogs[0])
    assertEquals("DaemonTagI" to "info daemon msg", daemonLogs[1])
    assertEquals("DaemonTagW" to "warn daemon msg", daemonLogs[2])
    assertEquals("DaemonTagE" to "error daemon msg", daemonLogs[3])
  }

  @Test
  fun javaCallerOverloadsWorkCorrectly() {
    TSLog.isDebugProvider = { true }
    TSLog.isXiaomiProvider = { false }

    // Java caller calling 2-arg error without throwable
    TSLog.e("JavaTag", "java error message")
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.ERROR, recordedLogs[0].priority)
    assertEquals("JavaTag", recordedLogs[0].tag)
    assertEquals("java error message", recordedLogs[0].msg)

    recordedLogs.clear()
    val tr = Exception("java error")
    TSLog.e("JavaTag", "java error message with throwable", tr)
    assertEquals(1, recordedLogs.size)
    assertEquals(Log.ERROR, recordedLogs[0].priority)
    assertEquals(tr, recordedLogs[0].tr)
  }
}
