package ru.nb.medalist.proxybff.controller

@Suppress("PropertyName")
data class AuthResponse(
	val access_token: String,
	val refresh_token: String,
	val expires_in: Long,
	val refresh_expires_in: Long,
	val token_type: String,
	val id_token: String,
	val session_state: String,
	val scope: String,
)
