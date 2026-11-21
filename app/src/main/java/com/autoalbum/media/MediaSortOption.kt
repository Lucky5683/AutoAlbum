package com.autoalbum.media

enum class SortField(val displayName: String) {
    DATE_ADDED("Date Added"),
    DATE_MODIFIED("Date Modified"),
    NAME("Filename"),
    DURATION("Duration"),
    SIZE("File Size")
}

enum class SortOrder(val displayName: String) {
    DESCENDING("Descending (Newest / Largest)"),
    ASCENDING("Ascending (Oldest / Smallest)")
}

