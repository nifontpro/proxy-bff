package ru.nb.medalist.proxybff.controller

import com.fasterxml.jackson.core.JsonProcessingException
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import org.springframework.web.reactive.function.client.awaitBody
import ru.nb.medalist.proxybff.utils.CookieUtils
import ru.nb.medalist.proxybff.webclient.UserWebClientBuilder

@RestController
@RequestMapping("/bff")
class BFFController(
	private val cookieUtils: CookieUtils,
	private val webClient: UserWebClientBuilder,

	@Value("\${keycloak.credentials.secret}") private val clientSecret: String,
	@Value("\${keycloak.url}") private val keyCloakURI: String,
	@Value("\${client.url}") private val clientURL: String,
	@Value("\${keycloak.client-id}") private val clientId: String,
	@Value("\${keycloak.grant-type.code}") private val grantTypeCode: String,
	@Value("\${keycloak.grant-type.refresh}") private val grantTypeRefresh: String,
) {

	private val keycloakClient = WebClient.create(keyCloakURI)

	@GetMapping("test")
	suspend fun test() = "Test endpoint"

	@PostMapping("/data")
	suspend fun data(
		@CookieValue("AT") accessToken: String?,
		@CookieValue("RT") refreshToken: String?,
		@RequestBody body: RS
	): ResponseEntity<Any> {
		val uri = "/user/data"
		return requestToResource(uri, accessToken, refreshToken, body)
	}

	@PostMapping("/admin_data")
	suspend fun adminData(
		@CookieValue("AT") accessToken: String?,
		@CookieValue("RT") refreshToken: String?,
		@RequestBody body: RS
	): ResponseEntity<Any> {
		val uri = "/admin/data"
		return requestToResource(uri, accessToken, refreshToken, body)
	}

	suspend fun requestToResource(
		uri: String,
		accessToken: String?,
		refreshToken: String?,
		requestBody: RS
	): ResponseEntity<Any> {

		if (refreshToken.isNullOrBlank()) {
			return ResponseEntity(RS("RT not found, logout"), HttpStatus.PROXY_AUTHENTICATION_REQUIRED)
		} else {
			log.info { "> Refresh token present" }
			log.info { refreshToken }
		}

		accessToken?.let {
			log.info { "> Access token present" }
		}

		val response = accessToken?.let {
			getDataFromRS(uri, it, requestBody)
		}

		if (response == null || response.statusCode == HttpStatus.UNAUTHORIZED) {
			return try {
				log.info { "> Get new Auth Data (refresh)" }
				val refreshResponse = refresh(refreshToken)
				val newAccessToken = refreshResponse.access_token
				val responseHeaders = cookieUtils.createCookies(refreshResponse)
				log.info { "> Again get Data from RS after refresh" }
				val newResponse = getDataFromRS(uri, newAccessToken, requestBody)
				val body = newResponse.body
				ResponseEntity(body, responseHeaders, newResponse.statusCode)
			} catch (e: WebClientResponseException) {
				ResponseEntity(RS("RT timeout, logout"), HttpStatus.PROXY_AUTHENTICATION_REQUIRED)
			}
		} else {
			return response
		}
	}

	suspend fun getDataFromRS(uri: String, accessToken: String, requestBody: RS): ResponseEntity<Any> {
		return try {
			val response = webClient.getUserData(uri = uri, body = requestBody, accessToken = accessToken)
			ResponseEntity.ok(response)
		} catch (e: WebClientResponseException) {
			log.error { e.message }
			ResponseEntity(RS(e.message ?: "RS error"), e.statusCode)
		}
	}

	// получение новых токенов на основе старого RefreshToken
	suspend fun refresh(oldRefreshToken: String): AuthResponse {

		val urlEncodedHeaders = HttpHeaders().apply {
			contentType = MediaType.APPLICATION_FORM_URLENCODED
		}

		val mapForm: MultiValueMap<String, String> = LinkedMultiValueMap()
		mapForm.add("grant_type", grantTypeRefresh)
		mapForm.add("client_id", clientId)
		mapForm.add("client_secret", clientSecret)
		mapForm.add("refresh_token", oldRefreshToken)

		return postKeycloakRequest(uri = "/token", body = mapForm, headers = urlEncodedHeaders)
	}

	/**
		Получение access token от лица клиента.
		Сами токены сохраняться в браузере не будут, а только будут передаваться в куках
		таким образом к ним не будет доступа из кода браузера (защита от XSS атак)
	 */
	@PostMapping("/token")
	suspend fun token(@RequestBody code: String): ResponseEntity<String> {

		log.info { "> START: Get tokens after auth, code: $code" }

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
		mapForm.add("redirect_uri", "$clientURL/login/redirect")

		val authResponse = postKeycloakRequest(uri = "/token", body = mapForm, headers = headers)

		return try {
			val responseHeaders = cookieUtils.createCookies(authResponse)
			log.info { "> END: Get tokens after auth: OK." }
			ResponseEntity.ok().headers(responseHeaders).build()
		} catch (e: JsonProcessingException) {
			log.error { e.message }
			ResponseEntity.badRequest().build()
		}
	}

	/**
	 * Удаление сессий пользователя внутри KeyCloak и также обнуление всех куков
	 */
	@GetMapping("/logout")
	suspend fun logout(@CookieValue("IT") idToken: String?): ResponseEntity<String> {

		if (idToken.isNullOrBlank()) {
			log.error { "> Logout: IT not present" }
			return ResponseEntity.badRequest().build()
		}

		keycloakClient
			.get()
			.uri {
				it.path("/logout")
					.queryParam("id_token_hint", idToken)
					.build()
			}
			.retrieve()
			.awaitBodilessEntity()

		val responseHeaders = cookieUtils.clearCookies()
		return ResponseEntity.ok().headers(responseHeaders).build()
	}

	suspend fun postKeycloakRequest(uri: String, body: Any, headers: HttpHeaders): AuthResponse {
		return keycloakClient
			.post()
			.uri(uri)
			.bodyValue(body)
			.headers { it.addAll(headers) }
			.retrieve()
			.awaitBody()
	}

	companion object {
		const val ID_TOKEN_COOKIE_KEY = "IT"
		const val REFRESH_TOKEN_COOKIE_KEY = "RT"
		const val ACCESS_TOKEN_COOKIE_KEY = "AT"

		private val log = KotlinLogging.logger {}
	}
}

data class RS(
	val res: String
)