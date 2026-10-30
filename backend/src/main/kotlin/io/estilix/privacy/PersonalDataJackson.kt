package io.estilix.privacy

import io.estilix.tenancy.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * Decides, per request, how personal data leaves the API:
 * - salon members: the real value (their role rules are enforced before data is loaded);
 * - platform support without break-glass: masked;
 * - platform support with an active break-glass session on this salon: real value, counted so the
 *   request can be audited.
 */
class PersonalDataSerializer : ValueSerializer<PersonalData>() {

    override fun serialize(value: PersonalData, gen: JsonGenerator, ctxt: SerializationContext) {
        val ctx = TenantContext.current()
        when {
            !ctx.support -> gen.writeString(value.value)
            ctx.breakGlassActive -> {
                ctx.unmaskedReads.incrementAndGet()
                gen.writeString(value.value)
            }
            else -> gen.writeString(value.masked())
        }
    }
}

@Configuration
class PersonalDataJacksonConfiguration {

    @Bean
    fun personalDataModule(): JacksonModule =
        SimpleModule("estilix-personal-data").addSerializer(PersonalData::class.java, PersonalDataSerializer())
}
