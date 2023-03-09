package ru.nb.medalist.proxybff.utils

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import ru.nb.medalist.proxybff.controller.AuthResponse
import ru.nb.medalist.proxybff.controller.BFFController

/**
 * Утилита для работы с куками
 * Напоминание: кук jwt создается на сервере и управляется только сервером (создается, удаляется) - "server-side cookie"
 * На клиенте этот кук нельзя считать с помощью JavaScript (т.к. стоит флаг httpOnly) - для безопасности и защиты от XSS атак.
 * Смотрите более подробные комментарии в методе создания кука.
 * Также, обязательно канал должен быть HTTPS, чтобы нельзя было дешифровать данные запросов между клиентом (браузером) и сервером
 */
@Component
class CookieUtils(
	@Value("\${cookie.domain}")
	private val cookieDomain: String // тот домен, который будет прописываться в сервер-куке при его создании на бэкенде
) {

	fun createCookies(body: AuthResponse): HttpHeaders {
		// получаем значения токенов из корневого элемента JSON
		val accessToken = body.access_token
		val idToken = body.id_token
		val refreshToken = body.refresh_token

		// Сроки действия для токенов берем также из JSON
		// Куки станут неактивные в то же время, как выйдет срок действия токенов в KeyCloak
		val accessTokenDuration = body.expires_in
		val refreshTokenDuration = body.refresh_expires_in

		// создаем куки, которые браузер будет отправлять автоматически на BFF при каждом запросе
		val accessTokenCookie = createCookie(BFFController.ACCESS_TOKEN_COOKIE_KEY, accessToken, accessTokenDuration)
		val refreshTokenCookie = createCookie(BFFController.REFRESH_TOKEN_COOKIE_KEY, refreshToken, refreshTokenDuration)
		// задаем такой же срок, что и AT
		val idTokenCookie = createCookie(BFFController.ID_TOKEN_COOKIE_KEY, idToken, accessTokenDuration)

		// чтобы браузер применил куки к браузеру - указываем их в заголовке Set-Cookie в response
		val responseHeaders = HttpHeaders()
		responseHeaders.add(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, idTokenCookie.toString())
		return responseHeaders
	}

	/**
	 * Обнуляем все куки, чтобы браузер их удалил у себя
	 */
	fun clearCookies(): HttpHeaders {
		// Обнуляем куки, которые отправляем обратно клиенту в response, тогда браузер автоматически удалит их
		val accessTokenCookie = deleteCookie(BFFController.ACCESS_TOKEN_COOKIE_KEY)
		val refreshTokenCookie = deleteCookie(BFFController.REFRESH_TOKEN_COOKIE_KEY)
		val idTokenCookie = deleteCookie(BFFController.ID_TOKEN_COOKIE_KEY)

		// чтобы браузер применил куки к браузеру - указываем их в заголовке Set-Cookie в response
		val responseHeaders = HttpHeaders()
		responseHeaders.add(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
		responseHeaders.add(HttpHeaders.SET_COOKIE, idTokenCookie.toString())
		return responseHeaders
	}

	/**
	 * создает server-side cookie со значением jwt.
	 * Важно: этот кук сможет считывать только сервер,
	 * клиент не сможет с помощью JS или другого клиентского кода (сделано для безопасности)
	 */
	private fun createCookie(
		name: String,
		value: String,
		durationInSeconds: Long
	): HttpCookie { // jwt - значение для кука
		return ResponseCookie // настройки кука
			.from(name, value) // название и значение кука
			.maxAge(durationInSeconds) // 86400 сек = 1 сутки
			.sameSite("None") // запрет на отправку кука на сервер, если выполняется межсайтовый запрос (доп. защита от CSRF атак) - кук будет отправляться только если пользователь сам набрал URL в адресной строке
			.httpOnly(true) // кук будет доступен для считывания только на сервере (на клиенте НЕ будет доступен с помощью JavaScript - тем самым защищаемся от XSS атак)
//			.secure(true) // кук будет передаваться браузером на backend только если канал будет защищен (https)
			.domain(cookieDomain) // для какого домена действует кук (перед отправкой запроса на backend - браузер "смотрит" на какой домен он отправляется - и если совпадает со значением из кука - тогда прикрепляет кук к запросу)
			.path("/") // кук будет доступен для всех URL
			.build()

		/* примечание: все настройки кука (domain, path и пр.) - влияют на то, будет ли браузер отправлять их при запросе.

            Браузер сверяет URL запроса (который набрали в адресной строке или любой ajax запрос с формы) с параметрами кука.
            И если есть хотя бы одно несовпадение (например domain или path) - кук отправлен не будет.

          */
	}

	/**
	 * Обнуляет (удаляет) кук
	 */
	private fun deleteCookie(name: String): HttpCookie {
		return ResponseCookie.from(name, "") // пустое значение
			.maxAge(0) // кук с нулевым сроком действия браузер удалит автоматически
//			.sameSite(SameSiteCookies.STRICT.value) // запрет на отправку кука, если запрос пришел со стороннего сайта (доп. защита от CSRF атак) - кук будет отправляться только если пользователь набрал URL в адресной строке
//			.sameSite("Strict") // запрет на отправку кука, если запрос пришел со стороннего сайта (доп. защита от CSRF атак) - кук будет отправляться только если пользователь набрал URL в адресной строке
			.httpOnly(true) // кук будет доступен для считывания только на сервере (на клиенте НЕ будет доступен с помощью JavaScript - тем самым защищаемся от XSS атак)
			.secure(true) // кук будет передаваться браузером на backend только если канал будет защищен (https)
			.domain(cookieDomain)
			.path("/") // кук будет доступен на любой странице
			.build()
	}

}