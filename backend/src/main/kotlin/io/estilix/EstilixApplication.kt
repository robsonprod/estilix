package io.estilix

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EstilixApplication

fun main(args: Array<String>) {
    runApplication<EstilixApplication>(*args)
}
