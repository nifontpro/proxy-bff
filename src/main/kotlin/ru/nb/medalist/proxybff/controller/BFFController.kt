package ru.nb.medalist.proxybff.controller

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import ru.nb.medalist.proxybff.utils.CookieUtils

@RestController
@RequestMapping("/bff") // базовый URI

class BFFController @Autowired constructor(
	// класс-утилита для работы с куками
	private val cookieUtils: CookieUtils,

	@Value("\${keycloak.secret}")
	private val clientSecret: String,

	@Value("\${resourceserver.url}")
	private val resourceServerURL: String,

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

	// просто перенаправляет запрос в Resource Server и добавляет в него access token
	@GetMapping("/data")
	fun data(
		@CookieValue("AT") accessToken: String?,
		@CookieValue("RT") refreshToken: String?
	): ResponseEntity<String> {
		if (accessToken.isNullOrBlank()) return ResponseEntity.ok("AT not found")
		println("--- AT: $accessToken")

		// обязательно нужно добавить заголовок авторизации с access token
		val response = getDataWithAT(accessToken)
		if (response.statusCode == HttpStatus.FORBIDDEN) {
			if (refreshToken.isNullOrBlank()) return ResponseEntity.ok("RT not found, logout")
			println("--- RT: $refreshToken")
			println("--- Get New RT !!!")
			val refreshResponse = refresh(refreshToken)
			val newAccessToken = refreshResponse.body?.access_token
			println("--- RT Body: $refreshResponse")
			return if (newAccessToken != null) {
				val responseHeaders = createCookiesData(refreshResponse)
				val res = getDataWithAT(newAccessToken)
				val body = res.body
				return ResponseEntity(body, responseHeaders, HttpStatus.OK)
			} else {
				ResponseEntity.ok("RT timeout, logout")
			}
		} else {
			return response
		}
	}

	private fun getDataWithAT(accessToken: String): ResponseEntity<String> {
		val headers = HttpHeaders()
		headers.setBearerAuth(accessToken) // слово Bearer будет добавлено автоматически
		val request = HttpEntity<MultiValueMap<String, String>>(headers)
		return restTemplate.exchange("$resourceServerURL/user/data", HttpMethod.GET, request, String::class.java)
	}

	// получение новых токенов на основе старого RefreshToken
	@GetMapping("/refresh")
	fun newAccessToken(@CookieValue("RT") oldRefreshToken: String?): ResponseEntity<String> {
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
		val response = restTemplate.exchange(
			"$keyCloakURI/token", HttpMethod.POST, request, String::class.java
		)
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
	fun refresh(oldRefreshToken: String): ResponseEntity<AuthResponse> {
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

		return restTemplate.exchange("$keyCloakURI/token", HttpMethod.POST, request, AuthResponse::class.java)
	}

	// удаление сессий пользователя внутри KeyCloak и также зануление всех куков
	@GetMapping("/logout")
	fun logout(@CookieValue("IT") idToken: String?): ResponseEntity<String> {

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
		val params: MutableMap<String, String?> = HashMap()
		params["post_logout_redirect_uri"] =
			clientURL // может быть любым, т.к. frontend получает ответ от BFF, а не напрямую от Auth Server
		params["id_token_hint"] = idToken // idToken указывает Auth Server, для кого мы хотим "выйти"
		params["client_id"] = clientId

		// выполняем запрос
		val response = restTemplate.getForEntity(
			urlTemplate,  // шаблон GET запроса
			String::class.java,  // нам ничего не возвращается в ответе, только статус, поэтому можно указать String
			params // какие значения будут подставлены в шаблон GET запроса
		)


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
	fun token(@RequestBody code: String): ResponseEntity<String> { // получаем auth code, чтобы обменять его на токены

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

		// выполняем запрос
		val response = restTemplate.exchange(
			"$keyCloakURI/token", HttpMethod.POST, request, String::class.java
		)
		// мы получаем JSON в виде текста
		try {

			// считать данные из JSON и записать в куки
			val responseHeaders = createCookies(response)

			// отправляем клиенту данные пользователя (и jwt-кук в заголовке Set-Cookie)
			return ResponseEntity.ok().headers(responseHeaders).build()
		} catch (e: JsonProcessingException) {
			e.printStackTrace()
		}

		// если ранее где-то возникла ошибка, то код переместится сюда, поэтому возвращаем статус с ошибкой
		return ResponseEntity.badRequest().build()
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