package com.agent.arena

import android.content.Context
import org.json.JSONObject

/**
 * 간단한 명령은 LLM(LMArena) 왕복 없이 로컬에서 즉시 해석/실행한다.
 * 모호하거나 복합 명령이면 null 을 반환해 LLM 경로로 넘긴다.
 */
object LocalIntents {

    data class Plan(val commands: List<JSONObject>)

    private val compound = Regex("(고\\s|고$|하고|그리고|다음에|한 뒤|한뒤|이후|,|\\band\\b|\\bthen\\b)")

    private fun cmd(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }
    private fun one(vararg p: Pair<String, Any>) = Plan(listOf(cmd(*p)))

    private val upWords = Regex("(올려|올리|키워|키우|높여|높이|크게|\\bup\\b|increase|louder|raise|brighter|밝게|밝아)")
    private val downWords = Regex("(내려|내리|줄여|줄이|낮춰|낮추|작게|\\bdown\\b|decrease|quieter|lower|reduce|dim|darker|어둡게)")

    private fun amount(t: String): Double = when {
        Regex("(많이|확|훨씬)").containsMatchIn(t) -> 40.0
        Regex("(조금|살짝|약간)").containsMatchIn(t) -> 10.0
        else -> 20.0
    }

    fun plan(input: String, ctx: Context): Plan? {
        val t = input.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 50 || compound.containsMatchIn(t)) return null

        // ---- 시스템 내비게이션
        if (Regex("^(뒤로|뒤로 ?가기|뒤로 가줘|back)$").matches(t)) return one("action" to "key", "key" to "back")
        if (Regex("^(홈|홈으로|홈 화면|홈으로 가줘|home)$").matches(t)) return one("action" to "key", "key" to "home")
        if (Regex("(최근 ?앱|최근 ?작업|recents)").containsMatchIn(t)) return one("action" to "key", "key" to "recents")
        if (Regex("(스크린샷|screenshot|화면 ?캡[처쳐])").containsMatchIn(t)) return one("action" to "key", "key" to "screenshot")
        if (Regex("(알림창|알림 ?내려|notifications)").containsMatchIn(t)) return one("action" to "key", "key" to "notifications")
        if (Regex("(빠른 ?설정|quick settings)").containsMatchIn(t)) return one("action" to "key", "key" to "quick_settings")

        // ---- 볼륨
        if (Regex("(소리|볼륨|음량|volume|사운드)").containsMatchIn(t)) {
            val stream = when {
                "벨소리" in t || "ring" in t -> "ring"
                "알림" in t || "notification" in t -> "notification"
                "알람" in t || "alarm" in t -> "alarm"
                "통화" in t -> "call"
                else -> "music"
            }
            val num = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                Regex("(음소거|무음|mute|조용히)").containsMatchIn(t) -> one("action" to "volume", "stream" to stream, "mute" to true)
                num != null && num in 0..100 -> one("action" to "volume", "stream" to stream, "set" to num)
                Regex("(최대|맥스|\\bmax\\b|가장 크게)").containsMatchIn(t) -> one("action" to "volume", "stream" to stream, "set" to 100)
                Regex("(최소|가장 작게)").containsMatchIn(t) -> one("action" to "volume", "stream" to stream, "set" to 0)
                upWords.containsMatchIn(t) -> one("action" to "volume", "stream" to stream, "delta_percent" to amount(t))
                downWords.containsMatchIn(t) -> one("action" to "volume", "stream" to stream, "delta_percent" to -amount(t))
                else -> null
            }
        }

        // ---- 밝기
        if (Regex("(밝기|brightness|화면.*(밝게|어둡게))").containsMatchIn(t)) {
            val num = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                num != null && num in 1..100 -> one("action" to "brightness", "set" to num)
                Regex("(최대|맥스|\\bmax\\b)").containsMatchIn(t) -> one("action" to "brightness", "set" to 100)
                Regex("(최소)").containsMatchIn(t) -> one("action" to "brightness", "set" to 1)
                upWords.containsMatchIn(t) -> one("action" to "brightness", "delta_percent" to amount(t))
                downWords.containsMatchIn(t) -> one("action" to "brightness", "delta_percent" to -amount(t))
                else -> null
            }
        }

        // ---- 손전등
        if (Regex("(손전등|플래시|후레쉬|랜턴|flashlight|torch)").containsMatchIn(t)) {
            return when {
                Regex("(꺼|끄|끔|\\boff\\b)").containsMatchIn(t) -> one("action" to "torch", "on" to false)
                Regex("(켜|켬|\\bon\\b)").containsMatchIn(t) -> one("action" to "torch", "on" to true)
                else -> null
            }
        }

        // ---- 미디어
        if (Regex("(다음 ?(곡|노래|트랙)|next (song|track))").containsMatchIn(t)) return one("action" to "media", "key" to "next")
        if (Regex("(이전 ?(곡|노래|트랙)|previous (song|track)|prev (song|track))").containsMatchIn(t)) return one("action" to "media", "key" to "previous")
        if (Regex("^(음악|노래)? ?(일시 ?정지|멈춰|정지|pause|stop)( ?해줘| ?해| ?줘)?$").matches(t)) return one("action" to "media", "key" to "pause")
        if (Regex("^(음악|노래)? ?(재생|계속 ?재생|다시 ?재생|play)( ?해줘| ?해| ?줘)?$").matches(t)) return one("action" to "media", "key" to "play")

        // ---- 타이머
        if (Regex("(타이머|timer)").containsMatchIn(t)) {
            var sec = 0
            Regex("(\\d+)\\s*(시간|hours?|hrs?)").find(t)?.let { sec += it.groupValues[1].toInt() * 3600 }
            Regex("(\\d+)\\s*(분|minutes?|mins?)").find(t)?.let { sec += it.groupValues[1].toInt() * 60 }
            Regex("(\\d+)\\s*(초|seconds?|secs?)").find(t)?.let { sec += it.groupValues[1].toInt() }
            if (sec > 0 && !Regex("(취소|삭제|해제|cancel)").containsMatchIn(t)) return one("action" to "set_timer", "seconds" to sec)
            return null
        }

        // ---- 알람 (설정만)
        if (Regex("(알람|alarm)").containsMatchIn(t) &&
            !Regex("(삭제|지워|취소|해제|끄|꺼|\\boff\\b|delete|cancel|소리|볼륨)").containsMatchIn(t) &&
            Regex("(맞춰|설정|등록|해줘|\\bset\\b|만들|깨워|추가)").containsMatchIn(t)
        ) {
            val m = Regex("(오전|오후|아침|저녁|밤|새벽|am|pm)?\\s*(\\d{1,2})\\s*(?:시|:)\\s*(?:(\\d{1,2})\\s*분?|(반))?").find(t)
            if (m != null) {
                var h = m.groupValues[2].toInt()
                val min = if (m.groupValues[4].isNotEmpty()) 30 else m.groupValues[3].toIntOrNull() ?: 0
                val pre = m.groupValues[1]
                if (pre in listOf("오후", "저녁", "밤", "pm") && h < 12) h += 12
                if (pre in listOf("오전", "아침", "새벽", "am") && h == 12) h = 0
                if (h in 0..23 && min in 0..59) return one("action" to "set_alarm", "hour" to h, "minute" to min)
            }
            return null
        }

        // ---- 앱 열기 (설치된 앱으로 확인될 때만)
        val open = Regex("^(.+?)\\s*(?:앱|어플|애플리케이션)?\\s*(?:을|를)?\\s*(?:열어 ?줘|열어|켜 ?줘|켜|실행 ?해 ?줘|실행해|실행|띄워 ?줘|띄워)$").find(t)
            ?: Regex("^(?:open|launch|start) (.+)$").find(t)
        if (open != null) {
            val name = open.groupValues[1].trim().removeSuffix("앱").trim()
            val app = DeviceActions.findApp(ctx, name) ?: return null
            return one("action" to "launch_app", "package" to app.second)
        }
        return null
    }
}
