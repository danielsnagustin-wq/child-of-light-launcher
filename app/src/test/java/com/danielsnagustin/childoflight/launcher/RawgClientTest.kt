/**
 * Tests del cliente RAWG sin acceso a red.
 *
 * Se utiliza un fetcher falso que devuelve fixtures JSON predefinidos
 * según la URL solicitada y cuenta cuántas veces ha sido llamado.
 *
 * Los tests cubren:
 * 1. Parseo correcto de resultados de búsqueda.
 * 2. No se llama a la red cuando la clave o la query están vacías.
 * 3. Búsqueda que prioriza coincidencia exacta (case‑insensitive).
 * 4. Parseo de detalles del juego, incluyendo fallback a descripción HTML.
 * 5. Manejo de claves vacías, fallos de red y JSON mal formado.
 * 6. Validación de que parseJson lanza IllegalArgumentException con JSON inválido.
 *
 * No se modifica ningún otro archivo del proyecto.
 */
package com.danielsnagustin.childoflight.launcher

import org.junit.Assert.*
import org.junit.Test
import com.danielsnagustin.childoflight.launcher.online.RawgClient
import com.danielsnagustin.childoflight.launcher.online.parseJson

/**
 * Fake fetcher que simula la respuesta del API RAWG.
 *
 * @property callCount contador de cuántas veces se invoca el fetcher.
 * @property detailsJson JSON que se debe devolver para los detalles del juego.
 */
private class FakeFetcher(
    private val detailsJson: String = DETAILS_JSON,
    private val searchJson: String = SEARCH_JSON
) : (String) -> String? {

    var callCount = 0
        private set

    override fun invoke(url: String): String? {
        callCount++
        return when {
            "/games?" in url -> searchJson
            "/games/" in url -> detailsJson
            else -> null
        }
    }
}

/** Fixtures JSON exactas que deben usarse en los tests. */
private const val SEARCH_JSON = """
{
  "count": 2,
  "next": null,
  "previous": null,
  "results": [
    {
      "id": 9999,
      "slug": "child-of-light-ii",
      "name": "Child of Light II",
      "released": null,
      "background_image": null,
      "genres": []
    },
    {
      "id": 1675,
      "slug": "child-of-light",
      "name": "Child of Light",
      "released": "2014-04-29",
      "background_image": "https://example.com/child-of-light.jpg",
      "genres": [
        { "id": 51, "name": "Indie" },
        { "id": 5, "name": "RPG" }
      ]
    }
  ]
}
"""

private const val DETAILS_JSON = """
{
  "id": 1675,
  "slug": "child-of-light",
  "name": "Child of Light",
  "description_raw": "Child of Light is a platforming role-playing game about Aurora.",
  "description": "<p>Child of Light is a platforming role-playing game about Aurora.</p>",
  "released": "2014-04-29",
  "background_image": "https://example.com/child-of-light.jpg",
  "genres": [
    { "id": 51, "name": "Indie" },
    { "id": 5, "name": "RPG" }
  ],
  "rating": 4.2,
  "playtime": 10
}
"""

private const val DETAILS_HTML_JSON = """
{
  "id": 1675,
  "slug": "child-of-light",
  "name": "Child of Light",
  "description_raw": null,
  "description": "<p>Un juego <b>bonito</b>.</p>",
  "released": null,
  "background_image": null,
  "genres": []
}
"""

class RawgClientTest {

    @Test
    fun searchParsesResults() {
        val fetcher = FakeFetcher()
        val client = RawgClient(fetcher)
        val results = client.searchGames(query = "child of light", apiKey = "clave")
        assertEquals(2, results.size)

        val first = results[0]
        assertEquals(9999, first.id)
        assertEquals("Child of Light II", first.name)
        assertNull(first.released)
        assertNull(first.backgroundImageUrl)
        assertTrue(first.genres.isEmpty())

        val second = results[1]
        assertEquals(1675, second.id)
        assertEquals("Child of Light", second.name)
        assertEquals("2014-04-29", second.released)
        assertEquals("https://example.com/child-of-light.jpg", second.backgroundImageUrl)
        assertEquals(listOf("Indie", "RPG"), second.genres)
    }

    @Test
    fun searchWithBlankKeyDoesNotCallNetwork() {
        val fetcher = FakeFetcher()
        val client = RawgClient(fetcher)
        val results = client.searchGames(query = "child of light", apiKey = "")
        assertTrue(results.isEmpty())
        assertEquals(0, fetcher.callCount)
    }

    @Test
    fun searchWithBlankQueryDoesNotCallNetwork() {
        val fetcher = FakeFetcher()
        val client = RawgClient(fetcher)
        val results = client.searchGames(query = "", apiKey = "clave")
        assertTrue(results.isEmpty())
        assertEquals(0, fetcher.callCount)
    }

    @Test
    fun findPrefersExactCaseInsensitiveMatch() {
        val fetcher = FakeFetcher()
        val client = RawgClient(fetcher)
        val game = client.findGameDetails(title = "child of light", apiKey = "clave")
        assertNotNull(game)
        assertEquals(1675, game!!.id)
        assertEquals("Child of Light", game.name)
    }

    @Test
    fun detailsParsesDescriptionRaw() {
        val fetcher = FakeFetcher(detailsJson = DETAILS_JSON)
        val client = RawgClient(fetcher)
        val details = client.getGameDetails(id = 1675, apiKey = "clave")
        assertNotNull(details)
        assertEquals(
            "Child of Light is a platforming role-playing game about Aurora.",
            details!!.description
        )
        assertEquals(listOf("Indie", "RPG"), details.genres)
        assertEquals("2014-04-29", details.released)
        assertEquals("https://example.com/child-of-light.jpg", details.backgroundImageUrl)
    }

    @Test
    fun detailsFallsBackToStrippedHtmlDescription() {
        val fetcher = FakeFetcher(detailsJson = DETAILS_HTML_JSON)
        val client = RawgClient(fetcher)
        val details = client.getGameDetails(id = 1675, apiKey = "clave")
        assertNotNull(details)
        assertEquals("Un juego bonito.", details!!.description)
        assertTrue(details.genres.isEmpty())
        assertNull(details.released)
        assertNull(details.backgroundImageUrl)
    }

    @Test
    fun detailsWithBlankKeyDoesNotCallNetwork() {
        val fetcher = FakeFetcher()
        val client = RawgClient(fetcher)
        val details = client.getGameDetails(id = 1675, apiKey = "")
        assertNull(details)
        assertEquals(0, fetcher.callCount)
    }

    @Test
    fun findWithBlankKeyDoesNotCallNetwork() {
        val fetcher = FakeFetcher()
        val client = RawgClient(fetcher)
        val game = client.findGameDetails(title = "Child of Light", apiKey = "")
        assertNull(game)
        assertEquals(0, fetcher.callCount)
    }

    @Test
    fun networkFailureReturnsNullOrEmpty() {
        val failingFetcher = object : (String) -> String? {
            var callCount = 0
            override fun invoke(url: String): String? {
                callCount++
                return null
            }
        }
        val client = RawgClient(failingFetcher)
        val search = client.searchGames(query = "any", apiKey = "clave")
        assertTrue(search.isEmpty())
        val details = client.getGameDetails(id = 1, apiKey = "clave")
        assertNull(details)
    }

    @Test
    fun malformedJsonReturnsNullOrEmptySinCrash() {
        val badFetcher = object : (String) -> String? {
            var callCount = 0
            override fun invoke(url: String): String? {
                callCount++
                return "{no es json"
            }
        }
        val client = RawgClient(badFetcher)
        val search = client.searchGames(query = "any", apiKey = "clave")
        assertTrue(search.isEmpty())
        val details = client.getGameDetails(id = 1, apiKey = "clave")
        assertNull(details)
    }

    @Test
    fun parseJsonRejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            parseJson("{no es json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseJson("-")
        }
    }
}
