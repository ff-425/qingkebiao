package com.qingkebiao.timetable

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 上课前提醒。
 *
 * 用 AlarmManager 的精确闹钟，不用 WorkManager 定时轮询：提醒必须准点，
 * WorkManager 的周期任务系统可以推迟几分钟甚至更久，"提前 15 分钟"变成
 * "上课了才响"就没意义了。
 *
 * 每次只排接下来 [SLOTS] 节课的闹钟，响一个就重排一次。理由：
 * 一学期两百多节课，全排上去既浪费又会撞上系统对闹钟数量的限制，
 * 而且课表一改就全作废。
 *
 * 排的时候走 [Derived.sessionsOn]，所以**调休是算数的**——
 * 放假那天不会响，调过来的课会按新日期响。
 */
object Reminders {

    private const val CHANNEL_ID = "class-reminder"
    private const val ACTION_FIRE = "com.qingkebiao.timetable.action.REMIND"
    /** 固定槽位数。用固定的 requestCode 池，取消时不用记住上次排了哪些。 */
    private const val SLOTS = 12

    private const val EX_TITLE = "title"
    private const val EX_LOC = "loc"
    private const val EX_START = "start"
    private const val EX_END = "end"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "上课前提醒你一次"
                enableVibration(true)
            }
        )
    }

    /** 系统层面允许发通知吗（Android 13 起是运行时权限，用户也可能手动关掉）。 */
    fun canPost(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** 允许排精确闹钟吗。不允许的话提醒会被系统推迟，甚至完全不响。 */
    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /** 跳到"闹钟和提醒"那个系统开关页。 */
    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 跳到本应用的通知设置页。 */
    fun openNotificationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun slotIntent(ctx: Context, slot: Int, s: Session?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("$ACTION_FIRE.$slot")
        if (s != null) {
            i.putExtra(EX_TITLE, s.title)
            i.putExtra(EX_LOC, s.location)
            i.putExtra(EX_START, s.start)
            i.putExtra(EX_END, s.end)
        }
        return PendingIntent.getBroadcast(
            ctx, slot, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        for (slot in 0 until SLOTS) am.cancel(slotIntent(ctx, slot, null))
    }

    /**
     * 按当前课表重排所有提醒。关掉提醒、课表变了、响过一次、开机、
     * 每 15 分钟的后台刷新——这些时候都会调一次，重复调用是安全的。
     */
    fun reschedule(ctx: Context, tt: Timetable, now: Long = System.currentTimeMillis()) {
        cancelAll(ctx)
        if (!tt.remindEnabled || tt.sessions.isEmpty()) return
        ensureChannel(ctx)

        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val lead = tt.remindMinutes.coerceIn(0, 180) * 60_000L
        val upcoming = Derived(tt).upcoming(now, SLOTS)

        var slot = 0
        for (s in upcoming) {
            if (slot >= SLOTS) break
            val at = s.start - lead
            // 已经过了提醒点的就别补一条了，上课前十分钟弹"还有15分钟"很怪
            if (at <= now) continue
            val pi = slotIntent(ctx, slot, s)
            runCatching {
                if (canExact(ctx)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    // 没有精确闹钟权限就退化成不精确的，总比不响强，
                    // 界面上会告诉用户为什么可能会晚
                    am.set(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }
            slot++
        }
    }

    fun notifyNow(ctx: Context, title: String, location: String, start: Long, end: Long) {
        ensureChannel(ctx)
        if (!canPost(ctx)) return
        // 四舍五入，不是截断。设了提前 15 分钟却弹"14 分钟后上课"，
        // 看着就像个 bug —— 闹钟本来就会差那么十几秒
        val mins = Math.round((start - System.currentTimeMillis()) / 60_000.0).toInt()
        val head = when {
            mins > 0 -> "$mins 分钟后上课"
            else -> "该上课了"
        }
        val body = buildString {
            append(title)
            if (location.isNotBlank()) append(" · $location")
            append(" · ${start.hhmm()}–${end.hhmm()}")
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(head)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx).notify(start.hashCode(), n)
        }
    }

    /** 设置里的"试一条"，用来确认这台手机上到底能不能收到。 */
    fun testNotify(ctx: Context, leadMinutes: Int) {
        ensureChannel(ctx)
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "真正的提醒长这样：$leadMinutes 分钟后上课 · 高等数学 · 翔宇楼503 · 08:10–09:45"
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("测试提醒 · 能看到就说明没被拦")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1, n) }
    }
}

/** 闹钟响了：发通知，然后把后面的重新排一遍。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty()
        if (title.isNotBlank()) {
            Reminders.notifyNow(
                context, title,
                intent.getStringExtra("loc").orEmpty(),
                intent.getLongExtra("start", 0L),
                intent.getLongExtra("end", 0L)
            )
        }
        // 用掉一个槽位就补上后面的，保证队列一直是满的
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Reminders.reschedule(context, Store.load(context)) }
            pending.finish()
        }
    }
}

/** 开机后闹钟会被系统清空，重排一次。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Reminders.reschedule(context, Store.load(context)) }
            pending.finish()
        }
    }
}
