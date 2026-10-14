package com.cuscus.wifiaudiostreaming

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Quiet lifecycle policy fixture, for an isolated emulator test window only. */
@RunWith(AndroidJUnit4::class)
class BackgroundIntentDeviceTest {
    @Test fun ordinaryServiceDestructionPreservesPersistedRole() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("audiobridge_runtime_mode", Context.MODE_PRIVATE)
        val taskPrefs = context.getSharedPreferences("wfas_task_runtime", Context.MODE_PRIVATE)
        instrumentation.runOnMainSync { RoleSelectionGate.initialize(context) }
        val original = RoleSelectionGate.role.value
        val oldTaskTime = taskPrefs.getLong("task_removed_at", 0)
        try {
            instrumentation.runOnMainSync {
                RoleSelectionGate.initialize(context)
                StreamingActionReceiver.clearTaskRemovedStop(context)
                for (role in listOf(RoleSelectionGate.Role.SENDER, RoleSelectionGate.Role.RECEIVER)) {
                    if (role == RoleSelectionGate.Role.SENDER) RoleSelectionGate.selectSender(context)
                    else RoleSelectionGate.selectReceiver(context)
                    StreamingActionReceiver.handleStoppedWithTask(context)
                    assertEquals(role, RoleSelectionGate.role.value)
                    assertEquals(role.name, prefs.getString("role", null))
                    assertFalse(StreamingActionReceiver.hasPendingTaskRemovedStop(context))
                }
                StreamingActionReceiver.handleTaskRemoved(context)
                assertTrue(RoleSelectionGate.isOffSelected())
                assertTrue(StreamingActionReceiver.hasPendingTaskRemovedStop(context))
            }
        } finally {
            instrumentation.runOnMainSync {
                when (original) {
                    RoleSelectionGate.Role.SENDER -> RoleSelectionGate.selectSender(context)
                    RoleSelectionGate.Role.RECEIVER -> RoleSelectionGate.selectReceiver(context)
                    RoleSelectionGate.Role.OFF -> RoleSelectionGate.selectOff(context)
                }
                val editor = taskPrefs.edit()
                if (oldTaskTime > 0) editor.putLong("task_removed_at", oldTaskTime)
                else editor.remove("task_removed_at")
                check(editor.commit()) { "Cannot restore task-removal fixture state" }
            }
        }
    }
}
