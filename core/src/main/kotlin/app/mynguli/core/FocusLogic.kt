package app.mynguli.core

object FocusLogic {
    fun start(
        title: String,
        durationMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): FocusSession {
        StateValidation.focusMinutes(durationMinutes)
        StateValidation.timestamp(nowMillis)
        val session = FocusSession(
            title = title.trim(),
            durationMinutes = durationMinutes,
            startedAtMillis = nowMillis,
            endsAtMillis = Math.addExact(nowMillis, durationMinutes * 60_000L),
        )
        StateValidation.focus(session)
        return session
    }

    /** Persisted deadlines survive process death; a backward wall-clock change never adds more than one full session. */
    fun remainingMillis(session: FocusSession, nowMillis: Long): Long {
        val duration = session.durationMinutes * 60_000L
        return (session.pausedRemainingMillis ?: (session.endsAtMillis - nowMillis)).coerceIn(0, duration)
    }

    fun pause(session: FocusSession, nowMillis: Long = System.currentTimeMillis()): FocusSession {
        StateValidation.focus(session)
        StateValidation.timestamp(nowMillis)
        return if (session.pausedRemainingMillis != null) session else
            session.copy(pausedRemainingMillis = remainingMillis(session, nowMillis))
    }

    fun resume(session: FocusSession, nowMillis: Long = System.currentTimeMillis()): FocusSession {
        StateValidation.focus(session)
        StateValidation.timestamp(nowMillis)
        val remaining = session.pausedRemainingMillis ?: return session
        return session.copy(
            endsAtMillis = Math.addExact(nowMillis, remaining),
            pausedRemainingMillis = null,
        ).also(StateValidation::focus)
    }

    fun complete(session: FocusSession, nowMillis: Long = System.currentTimeMillis()): FocusRecord {
        StateValidation.focus(session)
        StateValidation.timestamp(nowMillis)
        require(remainingMillis(session, nowMillis) == 0L) { "The focus interval has not finished." }
        return FocusRecord(
            id = session.id,
            title = session.title,
            minutes = session.durationMinutes,
            completedAtMillis = nowMillis,
        )
    }
}
