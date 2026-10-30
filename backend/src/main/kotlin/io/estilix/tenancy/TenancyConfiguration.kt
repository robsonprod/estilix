package io.estilix.tenancy

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

@Configuration
@EnableTransactionManagement
class TenancyConfiguration {

    /** Replaces Spring Boot's default transaction manager so every transaction carries the tenant. */
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager = TenantTransactionManager(dataSource)
}
