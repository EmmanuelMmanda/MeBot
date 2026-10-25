package mebot.nguli.com.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = AlarmController(context)
        try {
            when (intent.action) {
                AlarmController.ACTION_FIRE -> controller.receiveAlarm(
                    intent.getStringExtra(AlarmController.EXTRA_ALARM) ?: return,
                    intent.getStringExtra(AlarmController.EXTRA_TOKEN) ?: return,
                )
                AlarmController.ACTION_CONTINUE -> controller.receiveContinuation(
                    intent.getStringExtra(AlarmController.EXTRA_SESSION) ?: return,
                    intent.getLongExtra(AlarmController.EXTRA_DEADLINE, -1),
                )
                AlarmController.ACTION_RESUME -> controller.resumeSession(intent.getStringExtra(AlarmController.EXTRA_SESSION) ?: return)
                AlarmController.ACTION_SNOOZE -> controller.snooze(intent.getStringExtra(AlarmController.EXTRA_SESSION) ?: return)
                AlarmController.ACTION_EMERGENCY -> controller.emergencyStop(intent.getStringExtra(AlarmController.EXTRA_SESSION) ?: return)
                AlarmController.ACTION_DISMISS -> controller.dismiss(intent.getStringExtra(AlarmController.EXTRA_SESSION) ?: return)
            }
        } catch (failure: RuntimeException) {
            controller.log("receiver_error", intent.getStringExtra(AlarmController.EXTRA_ALARM) ?: "", failure.javaClass.simpleName)
        }
    }
}

class AlarmRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = AlarmController(context)
        try {
            controller.restore(intent.action ?: "system")
        } catch (failure: RuntimeException) {
            controller.log("restore_error", "", failure.javaClass.simpleName)
        }
    }
}
