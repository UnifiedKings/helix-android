package com.example.helixapp

import com.example.helixapp.data.LibraryRepository
import com.example.helixapp.data.SearchResults
import com.example.helixapp.data.SimilarArtists

/** Base for test fakes: override what a test needs; anything else fails loudly. */
open class FakeLibraryRepository : LibraryRepository {
    override suspend fun history(event: String?, offset: Int, limit: Int): HistoryPage = unused()
    override suspend fun album(browseId: String): AlbumView = unused()
    override suspend fun search(query: String): SearchResults = unused()
    override suspend fun searchArtists(query: String, limit: Int): List<SearchArtist> = unused()
    override suspend fun artist(browseId: String): ArtistDetailUi = unused()
    override suspend fun artistPopular(browseId: String, limit: Int): List<SearchSong> = unused()
    override suspend fun artistAlbums(browseId: String): List<SearchAlbum> = unused()
    override suspend fun similarArtists(browseId: String): SimilarArtists = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by this test")
}
