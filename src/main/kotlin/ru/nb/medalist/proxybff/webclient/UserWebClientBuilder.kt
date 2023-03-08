package ru.nb.medalist.proxybff.webclient

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Component
class UserWebClientBuilder(
	@Value("\${resourceserver.url}")
	private val resourceServerURL: String,
) {

	// https://www.baeldung.com/kotlin/spring-boot-kotlin-coroutines
	suspend fun getUserData(uri: String, body: Any, token: String): Any {

		return WebClient.create(resourceServerURL)
			.post()
			.uri(uri)
			.bodyValue(body)
			.header("Authorization", "Bearer $token")
			.retrieve()
			.awaitBody()
	}

}