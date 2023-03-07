package ru.nb.medalist.proxybff.webclient

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import ru.nb.medalist.proxybff.controller.RS

@Component // спец. класс для вызова микросервисов пользователей с помощью WebClient
class UserWebClientBuilder(
	@Value("\${resourceserver.url}")
	private val resourceServerURL: String,
) {

	// https://www.baeldung.com/kotlin/spring-boot-kotlin-coroutines
	suspend fun getTestData(uri: String, body: Any, token: String): RS {

		return WebClient.create(resourceServerURL)
			.post()
			.uri(uri)
			.bodyValue(body)
			.header("Authorization", "Bearer $token")
			.retrieve()
			.awaitBody()
	}

}