package ru.nb.medalist.proxybff.security
//
//import org.springframework.beans.factory.annotation.Value
//import org.springframework.boot.autoconfigure.EnableAutoConfiguration
//import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
//import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
//import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
//import org.springframework.context.annotation.Bean
//import org.springframework.context.annotation.Configuration
//import org.springframework.security.config.annotation.web.builders.HttpSecurity
//import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
//import org.springframework.security.config.http.SessionCreationPolicy
//import org.springframework.security.web.SecurityFilterChain
//import org.springframework.web.cors.CorsConfiguration
//import org.springframework.web.cors.CorsConfigurationSource
//import org.springframework.web.cors.UrlBasedCorsConfigurationSource
//import java.util.*
//
//@Configuration // данный класс будет считан как конфиг для spring контейнера
//@EnableWebSecurity // включает механизм защиты адресов, которые настраиваются в SecurityFilterChain
////@EnableMethodSecurity
//@EnableAutoConfiguration(
//	exclude = [DataSourceAutoConfiguration::class,
//		DataSourceTransactionManagerAutoConfiguration::class,
//		HibernateJpaAutoConfiguration::class]
//)
//
//class SpringSecurityConfig {
//	@Value("\${client.url}")
//	private val clientURL: String? = null // клиентский URL
//
//	// создается спец. бин, который отвечает за настройки запросов по http (метод вызывается автоматически) Spring контейнером
//	@Bean
//	@Throws(Exception::class)
//	fun filterChain(http: HttpSecurity): SecurityFilterChain {
//
//		// все сетевые настройки
//		http.authorizeHttpRequests()
//			.requestMatchers("/bff/**").permitAll() // разрешаем запросы на bff
//			.anyRequest().authenticated() // остальной API будет доступен только аутентифицированным пользователям
//			.and()
//			.csrf().disable() // отключаем встроенную защиту от CSRF атак, т.к. используем свою, из OAUTH2
//			.cors() // разрешает выполнять OPTIONS запросы от клиента (preflight запросы) без авторизации
////		http.requiresChannel().anyRequest().requiresSecure() // обязательное исп. HTTPS для всех запросах
//
//		// отключаем создание куков для сессии
//		http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//		return http.build()
//	}
//
//	//	 все эти настройки обязательны для корректного сохранения куков в браузере
//	@Bean
//	fun corsConfigurationSource(): CorsConfigurationSource {
//		val configuration = CorsConfiguration()
//		configuration.allowCredentials = true // без этого куки могут не сохраняться
//		configuration.allowedOrigins = listOf(clientURL)
////		configuration.allowedOrigins = listOf("*")
//		configuration.allowedHeaders = mutableListOf("*")
//		configuration.allowedMethods = mutableListOf("*")
//		val source = UrlBasedCorsConfigurationSource()
//		source.registerCorsConfiguration("/**", configuration)
//		return source
//	}
//}