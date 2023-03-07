package ru.nb.medalist.proxybff.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import org.springframework.web.server.session.WebSessionManager
import reactor.core.publisher.Mono
import java.util.*

@Configuration // данный класс будет считан как конфиг для spring контейнера
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity

class SpringSecurityFluxConfig {
	@Value("\${client.url}")
	private val clientURL: String? = null // клиентский URL

	// создается спец. бин, который отвечает за настройки запросов по http (метод вызывается автоматически) Spring контейнером
	@Bean
	fun filterChain(http: ServerHttpSecurity): SecurityWebFilterChain {

		// все сетевые настройки
		http.authorizeExchange()
			.pathMatchers("/bff/**").permitAll() // разрешаем запросы на bff
			.anyExchange().authenticated() // остальной API будет доступен только аутентифицированным пользователям
			.and()
			.csrf().disable() // отключаем встроенную защиту от CSRF атак, т.к. используем свою, из OAUTH2
			.cors() // разрешает выполнять OPTIONS запросы от клиента (preflight запросы) без авторизации

//		http.requiresChannel().anyRequest().requiresSecure() // обязательное исп. HTTPS для всех запросах

		return http.build()
	}

	// отключаем создание куков для сессии
	@Bean
	fun webSessionManager(): WebSessionManager {
		// Emulate SessionCreationPolicy.STATELESS
		return WebSessionManager { Mono.empty() }
	}

	@Bean
	fun corsConfigurationSource(): CorsWebFilter {
		val configuration = CorsConfiguration().apply {
			allowCredentials = true // без этого куки могут не сохраняться
			allowedOrigins = listOf(clientURL)
			allowedHeaders = listOf("*")
			allowedMethods = listOf("*")
		}
		val source = UrlBasedCorsConfigurationSource().apply {
			registerCorsConfiguration("/**", configuration)
		}
		return CorsWebFilter(source)
	}
}