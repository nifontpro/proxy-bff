package ru.nb.medalist.proxybff

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ProxyBffApplication

fun main(args: Array<String>) {
	runApplication<ProxyBffApplication>(*args)
}
