package com.autoalbum.automation.gallery

object GalleryRegistry {
    private val adapters = listOf(
        GooglePhotosAdapter(),
        VivoGalleryAdapter(),
        SamsungGalleryAdapter(),
        AndroidFilesAdapter(),
        GenericGalleryAdapter()
    )

    /**
     * Resolves an adapter for the given active package.
     * Returns null for any unrelated application (e.g. banking, messaging, system apps),
     * ensuring AutoAlbum strictly ignores and never inspects unrelated screens.
     */
    fun findAdapter(packageName: String): GalleryAdapter? {
        return adapters.firstOrNull { it.isSupported(packageName) }
    }

    fun isSupportedPackage(packageName: String): Boolean {
        return findAdapter(packageName) != null
    }

    /**
     * Resets session state across all registered gallery adapters.
     */
    fun resetAllSessions() {
        adapters.forEach { it.resetSession() }
    }
}
