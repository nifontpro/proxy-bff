package ru.nb.medalist.proxybff.controller

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import ru.nb.medalist.proxybff.utils.CookieUtils
import ru.nb.medalist.proxybff.webclient.UserWebClientBuilder

@RestController
@RequestMapping("/bff") // базовый URI
class BFFController(
	// класс-утилита для работы с куками
	private val cookieUtils: CookieUtils,
	private val webClient: UserWebClientBuilder,

	@Value("\${keycloak.secret}")
	private val clientSecret: String,

	@Value("\${keycloak.url}")
	private val keyCloakURI: String,

	@Value("\${client.url}")
	private val clientURL: String,

	@Value("\${keycloak.clientid}")
	private val clientId: String,

	@Value("\${keycloak.granttype.code}")
	private val grantTypeCode: String,

	@Value("\${keycloak.granttype.refresh}")
	private val grantTypeRefresh: String,
) {

	@GetMapping("/data")
	suspend fun data(
		@CookieValue("AT") accessToken: String?,
		@CookieValue("RT") refreshToken: String?,
//		@RequestBody body: RS
	): ResponseEntity<RS> {
		val uri = "/user/data"
		return requestToResource(uri, accessToken, refreshToken)
	}

	@GetMapping("/admin_data")
	suspend fun adminData(
		@CookieValue("AT") accessToken: String?,
		@CookieValue("RT") refreshToken: String?,
//		@RequestBody body: RS
	): ResponseEntity<RS> {
		val uri = "/admin/data"
		return requestToResource(uri, accessToken, refreshToken)
	}

	suspend fun requestToResource(
		uri: String,
		accessToken: String?,
		refreshToken: String?
	): ResponseEntity<RS> {
		val response = accessToken?.let {
			getDataWithAT(uri, it)
		}

		println("--- Response Status code: ${response?.statusCode}")

		if (response == null || response.statusCode == HttpStatus.UNAUTHORIZED) {
			if (refreshToken.isNullOrBlank()) return ResponseEntity(RS("RT not found, logout"), HttpStatus.PROXY_AUTHENTICATION_REQUIRED)
			println("--- Get new RT from keycloak")
			val refreshResponse = refresh(refreshToken)
			val newAccessToken = refreshResponse.body?.access_token
			return if (newAccessToken != null) {
				val responseHeaders = createCookiesData(refreshResponse)
				val res = getDataWithAT(uri, newAccessToken)
				if (res.statusCode != HttpStatus.OK) return ResponseEntity(RS("RS Error"), HttpStatus.INTERNAL_SERVER_ERROR)
				val body = res.body
				ResponseEntity(body, responseHeaders, HttpStatus.OK)
			} else {
				ResponseEntity(RS("RT timeout, logout"), HttpStatus.PROXY_AUTHENTICATION_REQUIRED)
			}
		} else {
			println("Response OK from RS")
			return response
		}
	}

	suspend fun getDataWithAT(uri: String, accessToken: String): ResponseEntity<RS> {
		return try {
			val res = webClient.getTestData(uri = uri, body = RS(res = "Test body"), token = accessToken)
			ResponseEntity.ok(res)
		} catch (e: WebClientResponseException) {
			println(e.message)
			println("---Status code WCE: ${e.statusCode}")
			ResponseEntity(RS("Internal error in Resource server"), e.statusCode)
		}
	}

	// получение новых токенов на основе старого RefreshToken
	@GetMapping("/refresh")
	suspend fun newAccessToken(@CookieValue("RT") oldRefreshToken: String?): ResponseEntity<String> {
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

		// параметры запроса
		val mapForm: MultiValueMap<String, String> = LinkedMultiValueMap()
		mapForm.add("grant_type", grantTypeRefresh)
		mapForm.add("client_id", clientId)
		mapForm.add("client_secret", clientSecret)
		mapForm.add("refresh_token", oldRefreshToken)

		// собираем запрос для выполнения
		val request = HttpEntity(mapForm, headers)

		// выполняем запрос (можно применять разные методы, не только exchange)
		val response = withContext(Dispatchers.IO) {
			restTemplate.exchange(
				"$keyCloakURI/token", HttpMethod.POST, request, String::class.java
			)
		}
		try {

			// создаем куки для ответа в браузер
			val responseHeaders = createCookies(response)

			// отправляем клиенту ответ со всеми куками (которые запишутся в браузер автоматически)
			// значения куков с новыми токенами перезапишутся в браузер
			return ResponseEntity.ok().headers(responseHeaders).build()
		} catch (e: JsonProcessingException) {
			e.printStackTrace()
		}

		// если ранее где-то возникла ошибка, то код переместится сюда, поэтому возвращаем статус с ошибкой
		return ResponseEntity.badRequest().build()
	}

	// получение новых токенов на основе старого RefreshToken
	suspend fun refresh(oldRefreshToken: String): ResponseEntity<AuthResponse> {
		val urlEncodedHeaders = HttpHeaders().apply {
			contentType = MediaType.APPLICATION_FORM_URLENCODED
		}

		// параметры запроса
		val mapForm: MultiValueMap<String, String> = LinkedMultiValueMap()
		mapForm.add("grant_type", grantTypeRefresh)
		mapForm.add("client_id", clientId)
		mapForm.add("client_secret", clientSecret)
		mapForm.add("refresh_token", oldRefreshToken)

		// собираем запрос для выполнения
		val request = HttpEntity(mapForm, urlEncodedHeaders)

		// выполняем запрос (можно применять разные методы, не только exchange)

		return withContext(Dispatchers.IO) {
			restTemplate.exchange("$keyCloakURI/token", HttpMethod.POST, request, AuthResponse::class.java)
		}
	}

	// удаление сессий пользователя внутри KeyCloak и также зануление всех куков
	@GetMapping("/logout")
	suspend fun logout(@CookieValue("IT") idToken: String?): ResponseEntity<String> {

		if (idToken.isNullOrBlank()) return ResponseEntity.badRequest().build()
		// 1. закрыть сессии в KeyCloak для данного пользователя
		// 2. занулить куки в браузере

		// чтобы корректно выполнить GET запрос с параметрами - применяем класс UriComponentsBuilder
		val urlTemplate = UriComponentsBuilder.fromHttpUrl("$keyCloakURI/logout")
			.queryParam("post_logout_redirect_uri", "{post_logout_redirect_uri}")
			.queryParam("id_token_hint", "{id_token_hint}")
			.queryParam("client_id", "{client_id}")
			.encode()
			.toUriString()

		// конкретные значения, которые будут подставлены в параметры GET запроса
		val params: MutableMap<String, String> = HashMap()
		params["post_logout_redirect_uri"] =
			clientURL // может быть любым, т.к. frontend получает ответ от BFF, а не напрямую от Auth Server
		params["id_token_hint"] = idToken // idToken указывает Auth Server, для кого мы хотим "выйти"
		params["client_id"] = clientId

		// выполняем запрос
		val response = withContext(Dispatchers.IO) {
			restTemplate.getForEntity(
				urlTemplate,  // шаблон GET запроса
				String::class.java,  // нам ничего не возвращается в ответе, только статус, поэтому можно указать String
				params // какие значения будут подставлены в шаблон GET запроса
			)
		}


		// если KeyCloak вернул 200-ОК, значит сессии пользователя успешно закрыты и можно обнулять куки
		if (response.statusCode === HttpStatus.OK) {

			// занулить значения и сроки годности всех куков (тогда браузер их удалит автоматически)
			val responseHeaders = clearCookies()

			// отправляем клиенту ответ с куками, которые автоматически применятся к браузеру
			return ResponseEntity.ok().headers(responseHeaders).build()
		}
		return ResponseEntity.badRequest().build()
	}

	// получение access token от лица клиента
	// но сами токены сохраняться в браузере не будут, а только будут передаваться в куках
	// таким образом к ним не будет доступа из кода браузера (защита от XSS атак)
	@PostMapping("/token")
	suspend fun token(@RequestBody code: String): ResponseEntity<String> { // получаем auth code, чтобы обменять его на токены

		// 1. обменять auth code на токены
		// 2. сохранить токены в защищенные куки
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

		// параметры запроса
		val mapForm: MultiValueMap<String, String> = LinkedMultiValueMap()
		mapForm.add("grant_type", grantTypeCode)
		mapForm.add("client_id", clientId)
		mapForm.add("client_secret", clientSecret)
		mapForm.add("code", code)

		// В случае работы клиента через BFF - этот redirect_uri может быть любым, т.к. мы не открываем окно вручную, а значит не будет автоматического перехода в redirect_uri
		// Клиент получает ответ в объекте ResponseEntity
		// НО! Значение все равно передавать нужно, без этого grant type не сработает и будет ошибка.
		// Значение обязательно должно быть с адресом и портом клиента, например https://localhost:8080  иначе будет ошибка Incorrect redirect_uri, потому что изначально запрос на авторизацию выполнялся именно с адреса клиента
		mapForm.add("redirect_uri", "$clientURL/login/redirect")

		// добавляем в запрос заголовки и параметры
		val request = HttpEntity(mapForm, headers)

		val response = withContext(Dispatchers.IO) {
			restTemplate.exchange(
				"$keyCloakURI/token", HttpMethod.POST, request, AuthResponse::class.java
			)
		}
		return try {
			val responseHeaders = createCookiesData(response)
			ResponseEntity.ok().headers(responseHeaders).build()
		} catch (e: JsonProcessingException) {
			e.printStackTrace()
			ResponseEntity.badRequest().build()
		}
	}

	// создание куков для response
	@Throws(JsonProcessingException::class)
	private fun createCookies(response: ResponseEntity<String>): HttpHeaders {

		// парсер JSON
		val mapper = ObjectMapper()

		// сначала нужно получить корневой элемент JSON
		val root = mapper.readTree(response.body)

		// получаем значения токенов из корневого элемента JSON
		val accessToken = root["access_token"].asText()
		val idToken = root["id_token"].asText()
		val refreshToken = root["refresh_token"].asText()

		// Сроки действия для токенов берем также из JSON
		// Куки станут неактивные в то же время, как выйдет срок действия токенов в KeyCloak
		val accessTokenDuration = root["expires_in"].asLong()
		val refreshTokenDuration = root["refresh_expires_in"].asLong()

		// создаем куки, которые браузер будет отправлять автоматически на BFF при каждом запросе
		val accessTokenCookie = cookieUtils.createCookie(ACCESS_TOKEN_COOKIE_KEY, accessToken, accessTokenDuration)
		val refreshTokenCookie = cookieUtils.createCookie(REFRESH_TOKEN_COOKIE_KEY, refreshToken, refreshTokenDuration)
		val idTokenCookie =
			cookieUtils.createCookie(ID_TOKEN_COOKIE_KEY, idToken, accessTokenDuration) // задаем такой же срок, что и AT

		// чтобы браузер применил куки к браузеру - указываем их в заголовке Set-Cookie в response
		val responseHeaders = HttpHeaders()
		responseHeaders.add(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, idTokenCookie.toString())
		return responseHeaders
	}

	private fun createCookiesData(response: ResponseEntity<AuthResponse>): HttpHeaders {

		val body = response.body ?: return HttpHeaders()

		// получаем значения токенов из корневого элемента JSON
		val accessToken = body.access_token
		val idToken = body.id_token
		val refreshToken = body.refresh_token

		// Сроки действия для токенов берем также из JSON
		// Куки станут неактивные в то же время, как выйдет срок действия токенов в KeyCloak
		val accessTokenDuration = body.expires_in
		val refreshTokenDuration = body.refresh_expires_in

		// создаем куки, которые браузер будет отправлять автоматически на BFF при каждом запросе
		val accessTokenCookie = cookieUtils.createCookie(ACCESS_TOKEN_COOKIE_KEY, accessToken, accessTokenDuration)
		val refreshTokenCookie = cookieUtils.createCookie(REFRESH_TOKEN_COOKIE_KEY, refreshToken, refreshTokenDuration)
		val idTokenCookie =
			cookieUtils.createCookie(ID_TOKEN_COOKIE_KEY, idToken, accessTokenDuration) // задаем такой же срок, что и AT

		// чтобы браузер применил куки к браузеру - указываем их в заголовке Set-Cookie в response
		val responseHeaders = HttpHeaders()
		responseHeaders.add(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, idTokenCookie.toString())
		return responseHeaders
	}

	// зануляет все куки, чтобы браузер их удалил у себя
	private fun clearCookies(): HttpHeaders {
		// зануляем куки, которые отправляем обратно клиенту в response, тогда браузер автоматически удалит их
		val accessTokenCookie = cookieUtils.deleteCookie(ACCESS_TOKEN_COOKIE_KEY)
		val refreshTokenCookie = cookieUtils.deleteCookie(REFRESH_TOKEN_COOKIE_KEY)
		val idTokenCookie = cookieUtils.deleteCookie(ID_TOKEN_COOKIE_KEY)

		// чтобы браузер применил куки к бразуеру - указываем их в заголовке Set-Cookie в response
		val responseHeaders = HttpHeaders()
		responseHeaders.add(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, idTokenCookie.toString())
		return responseHeaders
	}

	companion object {
		// можно также использовать WebClient вместо RestTemplate, если нужны асинхронные запросы
		private val restTemplate = RestTemplate() // для выполнения веб запросов на KeyCloak
		const val ID_TOKEN_COOKIE_KEY = "IT"
		const val REFRESH_TOKEN_COOKIE_KEY = "RT"
		const val ACCESS_TOKEN_COOKIE_KEY = "AT"
	}
}

data class RS(
	val res: String
)
