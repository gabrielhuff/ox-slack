import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit.WireMockRule
import io.ktor.config.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import org.junit.Before
import org.junit.Rule

@Suppress("EXPERIMENTAL_API_USAGE")
class ApplicationTest {

    @get:Rule
    var wireMockRule = WireMockRule(8089)

    @Before
    fun setup() {
        stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)))
    }

    @Test
    fun `provides menu ad specific date`() = withTestApplication({
        (environment.config as MapApplicationConfig).apply {
            put("slackToken", "test_token")
        }
        main()
    }) {

        handleRequest(HttpMethod.Post, "/") {
            addHeader("Content-Type", "application/x-www-form-urlencoded")
            setBody("token=test_token&text=February 7 2019&response_url=http://localhost:8089")
        }.run {
            assertEquals(HttpStatusCode.OK, response.status())
            verify(exactly(1), postRequestedFor(anyUrl())
                .withHeader("Content-Type", matching("application/json"))
                .withRequestBody(equalTo("""
                {"response_type": "ephemeral","text":"Donnerstag // 7. Februar
                Rindsuppe mit Eiernockerl
                Knuspriger Schweinsbraten mit Semmelknödel und warmen Krautsalat
                Schinken-Käseröllchen mit Kartoffeln und Sauce Tartar"}
                """.trimIndent()))
            )
        }
    }

    @Test
    fun `handles menu unavailability`() = withTestApplication({
        (environment.config as MapApplicationConfig).apply {
            put("slackToken", "test_token")
        }
        main()
    }) {
        handleRequest(HttpMethod.Post, "/") {
            addHeader("Content-Type", "application/x-www-form-urlencoded")
            setBody("token=test_token&text=100 years ago&response_url=http://localhost:8089")
        }.run {
            assertEquals(HttpStatusCode.OK, response.status())
            verify(exactly(1), postRequestedFor(anyUrl())
                .withHeader("Content-Type", matching("application/json"))
                .withRequestBody(equalTo("""
                {"response_type": "ephemeral","text":"There's no menu available for the given date"}
                """.trimIndent()))
            )
        }
    }

    @Test
    fun `refuses invalid dates`() = withTestApplication({
        (environment.config as MapApplicationConfig).apply {
            put("slackToken", "test_token")
        }
        main()
    }) {
        handleRequest(HttpMethod.Post, "/") {
            addHeader("Content-Type", "application/x-www-form-urlencoded")
            setBody("token=test_token&text=invalid_date&response_url=http://localhost:8089")
        }.run {
            assertEquals(HttpStatusCode.OK, response.status())
            verify(exactly(1), postRequestedFor(anyUrl())
                .withHeader("Content-Type", matching("application/json"))
                .withRequestBody(equalTo("""
                {"response_type": "ephemeral","text":"Couldn't parse the input date"}
                """.trimIndent()))
            )
        }
    }

    @Test
    fun `invalid tokens are refused`() = withTestApplication({
        (environment.config as MapApplicationConfig).apply {
            put("slackToken", "test_token")
        }
        main()
    }) {
        handleRequest(HttpMethod.Post, "/") {
            addHeader("Content-Type", "application/x-www-form-urlencoded")
            setBody("token=invalid_token&text=test_text&response_url=http://localhost:8089")
        }.run {
            assertFalse(response.status()!!.isSuccess())
            verify(exactly(0), postRequestedFor(anyUrl()))
        }
    }
}