package io.estilix.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Spec platform/personal-data-protection, "Masking format" and "Object printed in a log". */
class PersonalDataTest {

    @Test
    fun `mask a name`() {
        assertThat(PersonName("Ana Paula Souza").masked()).isEqualTo("A*** P**** S****")
    }

    @Test
    fun `short names keep at least three stars so their length is hidden`() {
        assertThat(PersonName("Bo").masked()).isEqualTo("B***")
        assertThat(PersonName("  Lia   Mar ").masked()).isEqualTo("L*** M***")
    }

    @Test
    fun `mask an e-mail`() {
        assertThat(Email("ana.souza@gmail.com").masked()).isEqualTo("a***@gmail.com")
    }

    @Test
    fun `malformed e-mail is fully hidden`() {
        assertThat(Email("not-an-email").masked()).isEqualTo("***")
    }

    @Test
    fun `mask a phone`() {
        assertThat(Phone("+55 85 99876-5432").masked()).isEqualTo("***********32")
    }

    @Test
    fun `other personal text is fully replaced`() {
        assertThat(PersonalText("alérgica a amônia").masked()).isEqualTo("***")
    }

    @Test
    fun `toString never prints the value, also inside data classes and templates`() {
        data class Customer(val name: PersonName, val email: Email, val phone: Phone)
        val customer = Customer(PersonName("Ana Paula Souza"), Email("ana.souza@gmail.com"), Phone("+55 85 99876-5432"))

        val printed = "$customer / ${customer.name}"

        assertThat(printed).doesNotContain("Ana", "Souza", "ana.souza", "gmail", "99876", "5432")
        assertThat(printed).contains("***")
    }

    @Test
    fun `equality is by type and value`() {
        assertThat(Email("a@b.com")).isEqualTo(Email("a@b.com")).isNotEqualTo(PersonalText("a@b.com"))
    }
}
