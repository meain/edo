package com.edo.app.data

import java.util.Locale

/**
 * Running token and cost totals for a chat or a scheduled run. [inputTokens] counts everything
 * sent, cached or not. [costUsd] sums the turns whose price is known; [partial] is set when some
 * turn had no price (unknown model or no price table yet), so the real cost is higher.
 */
data class UsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val partial: Boolean = false,
) {
    val isEmpty: Boolean get() = inputTokens == 0L && outputTokens == 0L

    operator fun plus(o: UsageTotals) = UsageTotals(
        inputTokens + o.inputTokens, outputTokens + o.outputTokens, costUsd + o.costUsd, partial || o.partial,
    )

    /** "12.3k in · 1.2k out · $0.04", or "… · $0.04+" when part of the cost is unknown. */
    fun label(): String = buildString {
        append(formatTokens(inputTokens)).append(" in · ").append(formatTokens(outputTokens)).append(" out")
        costLabel()?.let { append(" · ").append(it) }
    }

    /** "$0.04", "$0.04+" for a lower bound, or null when nothing is priced. */
    fun costLabel(): String? = when {
        costUsd > 0 -> formatCost(costUsd) + if (partial) "+" else ""
        else -> null
    }
}

fun ThreadEntity.usage() = UsageTotals(inputTokens, outputTokens, costUsd, costPartial)

fun ScheduleRunEntity.usage() = UsageTotals(inputTokens, outputTokens, costUsd, costPartial)

/** Lifetime usage of every run of this schedule. */
fun ScheduleEntity.totalUsage() = UsageTotals(totalInputTokens, totalOutputTokens, totalCostUsd, totalCostPartial)

fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

fun formatCost(usd: Double): String =
    if (usd < 0.01) String.format(Locale.US, "$%.4f", usd) else String.format(Locale.US, "$%.2f", usd)
