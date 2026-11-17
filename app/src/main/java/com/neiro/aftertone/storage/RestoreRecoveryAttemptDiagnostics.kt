package com.neiro.aftertone.storage

internal enum class RestoreRecoveryAttemptStage(val code: String) {
    ATTEMPT("AT"),
    STORAGE_PREFLIGHT("PF"),
    JOURNAL_READ("JR"),
    JOURNAL_CLASSIFICATION("JC"),
    JOURNAL_OS_ERROR_CLASSIFICATION("OE"),
    STATE_READ("SR"),
    DECODE_PREVIOUS_STATE("DP"),
    DECODE_COMMITTED_STATE("DC"),
    STATE_CLASSIFICATION("SC"),
    VERIFY_ACTIVE_GENERATION("VG"),
    CLEAN_PREVIOUS_GENERATIONS("PG"),
    CLEAN_REPLACED_MEDIA("RM"),
    ROLLBACK_STATE("RB"),
    CLEAN_RESTORE_GENERATION("GN"),
    CLEAN_RESTORE_STAGING("ST"),
    DELETE_JOURNAL("DJ"),
    CLEAN_ORPHAN_JOURNAL_TEMPORARIES("JT"),
    CLEAN_ORPHAN_STAGING("OS"),
    CLEAN_ORPHAN_GENERATIONS("OG"),
    DIRECTORY_SYNC_FILES("SF"),
    DIRECTORY_SYNC_AUDIO("SA"),
    DIRECTORY_SYNC_RESTORE("SD"),
    RECOVERY_RESULT("RR"),
    GATE("GT"),
    ROOT_OPEN_NATIVE_PROBE("RP"),
    PRIVATE_FILES_DIRECTORY_NATIVE_PROBE("PN"),
    PRIVATE_FILES_DIRECTORY_NIO_PROBE("ND");

    companion object {
        fun fromCode(code: String): RestoreRecoveryAttemptStage? = entries.firstOrNull { it.code == code }
    }
}

internal enum class RestoreRecoveryAttemptOutcome(val code: String) {
    STARTED("00"),
    SUCCEEDED("01"),
    FAILED("02"),
    NO_JOURNAL("03"),
    COMMITTED_STATE("04"),
    PREVIOUS_STATE("05"),
    STATE_MISMATCH("06"),
    PENDING("07"),
    BLOCKED("08"),
    OPEN("09"),
    JOURNAL_DIRECTORY_OPEN_FAILURE("10"),
    JOURNAL_MISSING_OR_SPECIAL_FILE("11"),
    JOURNAL_NOT_PRESENT("80"),
    JOURNAL_NON_REGULAR_OR_SYMLINK("81"),
    JOURNAL_ENTRY_DISAPPEARED_OR_REPLACED("82"),
    JOURNAL_SIZE_CHANGE_OR_TRUNCATION("12"),
    JOURNAL_UNSUPPORTED_HEADER_VERSION("13"),
    JOURNAL_BAD_SERIALIZATION("14"),
    JOURNAL_UNSAFE_PATH("15"),
    JOURNAL_BAD_INTEGRITY_METADATA("16"),
    JOURNAL_TRAILING_BYTES("17"),
    JOURNAL_READABLE("18"),
    JOURNAL_SAFE_UNKNOWN("19"),
    JOURNAL_DIRECTORY_PATH_VALIDATION_FAILURE("20"),
    JOURNAL_DIRECTORY_OPEN_FAILURE_SPECIFIC("21"),
    JOURNAL_FILE_OPEN_FAILURE("22"),
    JOURNAL_DIRECTORY_OPEN_SYSCALL_FAILURE("23"),
    JOURNAL_DIRECTORY_DESCRIPTOR_DUPLICATION_FAILURE("24"),
    JOURNAL_DIRECTORY_DESCRIPTOR_STAT_FAILURE("25"),
    JOURNAL_DIRECTORY_PATH_IDENTITY_STAT_FAILURE("26"),
    JOURNAL_PRIVATE_DIRECTORY_PATH_RESOLUTION_FAILURE("27"),
    JOURNAL_PRIVATE_DIRECTORY_ROOT_OPEN_FAILURE("28"),
    JOURNAL_PRIVATE_DIRECTORY_STREAM_UNSUPPORTED("29"),
    JOURNAL_PRIVATE_DIRECTORY_ROOT_CLOSE_FAILURE("30"),
    JOURNAL_PRIVATE_DIRECTORY_COMPONENT_OPEN_FAILURE("31"),
    JOURNAL_PRIVATE_DIRECTORY_PARENT_CLOSE_FAILURE("32"),
    JOURNAL_OS_ACCESS_DENIED("40"),
    JOURNAL_OS_RESOURCE_EXHAUSTION("41"),
    JOURNAL_OS_IO_FAILURE("42"),
    JOURNAL_OS_UNKNOWN("43"),
    JOURNAL_OS_NIO_ACCESS_DENIED("44"),
    JOURNAL_OS_SECURITY_EXCEPTION("45"),
    JOURNAL_OS_NIO_FILESYSTEM_EXCEPTION("46"),
    ROOT_NATIVE_OPEN_SUCCEEDED("50"),
    ROOT_NATIVE_OPEN_ACCESS_DENIED("51"),
    ROOT_NATIVE_OPEN_SECURITY_DENIED("52"),
    ROOT_NATIVE_OPEN_OTHER_FAILURE("53"),
    ROOT_NATIVE_OPEN_SUCCEEDED_CLOSE_FAILED("54"),
    PRIVATE_FILES_DIRECTORY_OPEN_SUCCEEDED("60"),
    PRIVATE_FILES_DIRECTORY_OPEN_ACCESS_DENIED("61"),
    PRIVATE_FILES_DIRECTORY_OPEN_SECURITY_DENIED("62"),
    PRIVATE_FILES_DIRECTORY_OPEN_OTHER_FAILURE("63"),
    PRIVATE_FILES_DIRECTORY_OPEN_SUCCEEDED_CLOSE_FAILED("64"),
    NIO_DIRECTORY_IDENTITY_CONFIRMED("70"),
    NIO_SECURE_STREAM_UNSUPPORTED("71"),
    NIO_DIRECTORY_IDENTITY_UNCONFIRMED("72"),
    NIO_OPEN_ACCESS_DENIED("73"),
    NIO_OPEN_SECURITY_DENIED("74"),
    NIO_OPEN_OTHER_FAILURE("75"),
    NIO_STREAM_CLOSE_FAILED("76");

    companion object {
        fun fromCode(code: String): RestoreRecoveryAttemptOutcome? = entries.firstOrNull { it.code == code }
    }
}

internal data class RestoreRecoveryAttemptEvent(
    val stage: RestoreRecoveryAttemptStage,
    val outcome: RestoreRecoveryAttemptOutcome,
)

internal class RestoreRecoveryAttemptTrace private constructor(
    val events: List<RestoreRecoveryAttemptEvent>,
) {
    fun append(event: RestoreRecoveryAttemptEvent): RestoreRecoveryAttemptTrace =
        RestoreRecoveryAttemptTrace(
            (events.filterNot { it.stage == event.stage } + event).takeLast(MAX_RECOVERY_ATTEMPT_EVENTS),
        )

    fun appendJournalDiagnosis(
        diagnosis: RecoveryJournalReadDiagnosis,
        osErrorCategory: RecoveryJournalOsErrorCategory?,
    ): RestoreRecoveryAttemptTrace = appendJournalDiagnosis(diagnosis, osErrorCategory, null, null)

    fun appendJournalDiagnosis(
        diagnosis: RecoveryJournalReadDiagnosis,
        osErrorCategory: RecoveryJournalOsErrorCategory?,
        rootProbeOutcome: RootDirectoryNativeProbeOutcome?,
    ): RestoreRecoveryAttemptTrace = appendJournalDiagnosis(diagnosis, osErrorCategory, rootProbeOutcome, null)

    fun appendJournalDiagnosis(
        diagnosis: RecoveryJournalReadDiagnosis,
        osErrorCategory: RecoveryJournalOsErrorCategory?,
        rootProbeOutcome: RootDirectoryNativeProbeOutcome?,
        privateFilesDirectoryProbeOutcome: PrivateFilesDirectoryNativeProbeOutcome?,
    ): RestoreRecoveryAttemptTrace = appendJournalDiagnosis(
        diagnosis,
        osErrorCategory,
        rootProbeOutcome,
        privateFilesDirectoryProbeOutcome,
        null,
    )

    fun appendJournalDiagnosis(
        diagnosis: RecoveryJournalReadDiagnosis,
        osErrorCategory: RecoveryJournalOsErrorCategory?,
        rootProbeOutcome: RootDirectoryNativeProbeOutcome?,
        privateFilesDirectoryProbeOutcome: PrivateFilesDirectoryNativeProbeOutcome?,
        privateFilesDirectoryNioProbeOutcome: PrivateFilesDirectoryNioProbeOutcome?,
    ): RestoreRecoveryAttemptTrace {
        val withoutPreviousErrorCategory = RestoreRecoveryAttemptTrace(
            events.filterNot {
                it.stage == RestoreRecoveryAttemptStage.JOURNAL_OS_ERROR_CLASSIFICATION ||
                    it.stage == RestoreRecoveryAttemptStage.ROOT_OPEN_NATIVE_PROBE ||
                    it.stage == RestoreRecoveryAttemptStage.PRIVATE_FILES_DIRECTORY_NATIVE_PROBE ||
                    it.stage == RestoreRecoveryAttemptStage.PRIVATE_FILES_DIRECTORY_NIO_PROBE
            },
        )
        val withDiagnosis = withoutPreviousErrorCategory.append(
            RestoreRecoveryAttemptEvent(RestoreRecoveryAttemptStage.JOURNAL_CLASSIFICATION, diagnosis.traceOutcome),
        )
        val withOsError = osErrorCategory?.let { category ->
            withDiagnosis.append(
                RestoreRecoveryAttemptEvent(RestoreRecoveryAttemptStage.JOURNAL_OS_ERROR_CLASSIFICATION, category.traceOutcome),
            )
        } ?: withDiagnosis
        val withRootProbe = rootProbeOutcome?.let { outcome ->
            withOsError.append(
                RestoreRecoveryAttemptEvent(RestoreRecoveryAttemptStage.ROOT_OPEN_NATIVE_PROBE, outcome.traceOutcome),
            )
        } ?: withOsError
        val withPrivateFilesDirectoryProbe = privateFilesDirectoryProbeOutcome?.let { outcome ->
            withRootProbe.append(
                RestoreRecoveryAttemptEvent(
                    RestoreRecoveryAttemptStage.PRIVATE_FILES_DIRECTORY_NATIVE_PROBE,
                    outcome.traceOutcome,
                ),
            )
        } ?: withRootProbe
        return privateFilesDirectoryNioProbeOutcome?.let { outcome ->
            withPrivateFilesDirectoryProbe.append(
                RestoreRecoveryAttemptEvent(
                    RestoreRecoveryAttemptStage.PRIVATE_FILES_DIRECTORY_NIO_PROBE,
                    outcome.traceOutcome,
                ),
            )
        } ?: withPrivateFilesDirectoryProbe
    }

    fun encode(): String = events.joinToString(".") { event ->
        event.stage.code + event.outcome.code
    }

    val displayCode: String?
        get() = encode().takeIf(String::isNotEmpty)?.let { "DIA-${it.replace('.', '-')}" }

    companion object {
        fun empty(): RestoreRecoveryAttemptTrace = RestoreRecoveryAttemptTrace(emptyList())

        fun decode(encoded: String?): RestoreRecoveryAttemptTrace {
            if (encoded.isNullOrEmpty()) return empty()
            val events = encoded.takeLast(MAX_RECOVERY_ATTEMPT_TRACE_LENGTH)
                .split('.')
                .mapNotNull { token ->
                    if (token.length != 4) return@mapNotNull null
                    val stage = RestoreRecoveryAttemptStage.fromCode(token.substring(0, 2))
                        ?: return@mapNotNull null
                    val outcome = RestoreRecoveryAttemptOutcome.fromCode(token.substring(2, 4))
                        ?: return@mapNotNull null
                    RestoreRecoveryAttemptEvent(stage, outcome)
                }
            val latestByStage = mutableListOf<RestoreRecoveryAttemptEvent>()
            events.forEach { event ->
                latestByStage.removeAll { it.stage == event.stage }
                latestByStage += event
            }
            return RestoreRecoveryAttemptTrace(latestByStage.takeLast(MAX_RECOVERY_ATTEMPT_EVENTS))
        }
    }
}

internal class RestoreRecoveryDiagnosticTracePersistenceException : RuntimeException()

private const val MAX_RECOVERY_ATTEMPT_EVENTS = 20
private const val MAX_RECOVERY_ATTEMPT_TRACE_LENGTH = MAX_RECOVERY_ATTEMPT_EVENTS * 5 - 1
