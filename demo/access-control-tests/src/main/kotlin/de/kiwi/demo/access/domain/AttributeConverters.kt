package de.kiwi.demo.access.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.net.URI

@Converter(autoApply = true)
class UriAttributeConverter : AttributeConverter<URI, String> {
    override fun convertToDatabaseColumn(uri: URI?): String? = uri?.toString()
    override fun convertToEntityAttribute(value: String?): URI? = value?.let { URI.create(it) }
}

@Converter(autoApply = true)
class UserRolesConverter : AttributeConverter<UserAccountRoles, String> {
    override fun convertToDatabaseColumn(attribute: UserAccountRoles?) = attribute?.serialize()
    override fun convertToEntityAttribute(dbData: String?) = dbData?.let { UserAccountRoles.deserialize(it) }
}
