import com.mdimension.jchronic.Chronic
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.apache.*
import io.ktor.client.response.*
import io.ktor.content.TextContent
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.util.*

@Suppress("EXPERIMENTAL_API_USAGE")
fun Application.main() {

    // Token used for authenticating requests from Slack or null for disabling authentication.
    // Defaults to the value passed on the 'application.conf' file
    val slackToken: String? = environment.config.propertyOrNull("slackToken")?.getString()

    // Http client used for sending HTTP requests. Defaults to an Apache implementation
    val httpClient = HttpClient(Apache)

    // In memory cache of menus (so we don't flood the restaurant with requests). The keys are pairs
    // of year / week of the year and the values are menus of that week
    val menuCache = mutableMapOf<Pair<Int, Int>, String?>()

    // Install content negotiation for parsing form params
    install(ContentNegotiation)

    // Setup routes
    routing {
        post("/") {
            // Get form params
            val params = call.receiveOrNull<Parameters>()
            val token = params?.get("token")
            val text = params?.get("text")
            val responseUrl = params?.get("response_url")

            // Fail with 403 if token is invalid
            if (slackToken != null && token != slackToken) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            // Fail with 400 if response URL wasn't passed as a param
            if (responseUrl == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            // Respond with 200 right away. A delayed message will be sent later
            call.respond(HttpStatusCode.OK)


            // Get delayed message to send back
            val delayedMessage = run messageToSend@{

                // Try to parse input text as a date (return negative message in case of failure)
                val date = if (text.isNullOrBlank()) {
                    Calendar.getInstance()
                } else {
                    try {
                        Chronic.parse(text).beginCalendar
                    } catch (ignored: Exception) {
                        return@messageToSend "Couldn't parse the input date"
                    }
                }
                val year = date.get(Calendar.YEAR)
                val week = date.get(Calendar.WEEK_OF_YEAR)
                val day = date.get(Calendar.DAY_OF_MONTH)

                // Define possible URLs (sometimes different URL formats are used by the restaurant)
                val possibleUrls = listOf(
                    "http://www.ox-linz.at/fileadmin/Mittagsmenue/$year/OX_Linz_A5_Wochenmenue__KW$week.pdf",
                    "http://www.ox-linz.at/fileadmin/Mittagsmenue/OX_Linz_A5_Wochenmenue__KW$week.pdf"
                )

                // Try to get menu from cache if possible. Otherwise request PDF from restaurant and
                // extract its text
                val menuText = menuCache.getOrPut(year to week) {
                    var toCache: String? = null
                    for (url in possibleUrls) {
                        try {
                            val menuData: ByteArray = httpClient.call(url).response.readBytes()
                            val menuDocument = PDDocument.load(menuData)
                            toCache = menuDocument.use { PDFTextStripper().getText(menuDocument) }
                            break
                        } catch (ignored: Exception) {}
                    }
                    toCache
                }

                // If menu couldn't be fetched, respond negatively
                if (menuText == null) {
                    return@messageToSend "There's no menu available for the given date"
                }

                // Extract day entry from menu. An example of menu file can be found at
                // http://www.ox-linz.at/fileadmin/Mittagsmenue/OX_Linz_A5_Wochenmenue__KW3.pdf
                val startRegex = """.*// $day([. ]).*""".toRegex()
                val endRegex = """(?m)(^\s*$|.*Änderungen vorbehalten!.*)""".toRegex()
                val dayMenu = menuText.lines().dropLast(1)
                    .dropWhile { !it.matches(startRegex) }
                    .takeWhile { !it.matches(endRegex) }
                    .joinToString(separator = "\n") { it.trim() }
                    .trim()

                // If there's no entry, respond negatively
                return@messageToSend if (dayMenu.isBlank()) {
                    "There's no menu available for the given date"
                } else {
                    dayMenu
                }
            }

            // Send delayed message
            httpClient.call(responseUrl) {
                method = HttpMethod.Post
                body = TextContent(
                    text = """{"response_type": "ephemeral","text":"$delayedMessage"}""",
                    contentType = ContentType.Application.Json
                )
            }
        }
    }
}
