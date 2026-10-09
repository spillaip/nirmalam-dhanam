package com.nirmalamgroup.nirmalamdhanam

/** Top-level destinations owned by the app shell rather than MainActivity. */
internal enum class DhanamDestination {
    HOME,
    TRANSACTIONS,
    REPORTS,
    INVESTMENTS,
    NET_WORTH,
    INSIGHTS,
    SETTINGS,
}

/** Two peer views inside the investment area. */
internal enum class InvestmentSection {
    OVERVIEW,
    PERFORMANCE,
}
